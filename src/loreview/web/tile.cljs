(ns loreview.web.tile
  "The tile engine (SPEC 11.2). Every tile lives under [:tiles <instance-id>] and every
  handler takes the instance id — nothing is registered per tile. A tile is loaded from its
  descriptor (Loreview), then run against its plan (Loregate, directly, as the viewer)."
  (:require [clojure.string :as str]
            [loreview.web.render :as render]
            [re-frame.core :as rf]))

(defn- in-tile [db iid f & args] (apply update-in db [:tiles iid] f args))

(defn- defaults [query]
  (into {} (for [[k d] (:params query) :when (contains? d :default)] [k (:default d)])))

(rf/reg-event-fx
 ::load
 (fn [{:keys [db]} [_ iid id v mosaic]]
   {:db (assoc-in db [:tiles iid] {:status :loading :mosaic mosaic})
    :http {:url (str "api/tiles/" (js/encodeURIComponent (subs (str id) 1)) "/versions/" v)
           :token (:user db)
           :on-ok [::descriptor-received iid] :on-error [::failed iid]}}))

(rf/reg-event-fx
 ::descriptor-received
 (fn [{:keys [db]} [_ iid d]]
   {:db (in-tile db iid assoc :descriptor d :params (defaults (:tile/query d)))
    :dispatch [::run iid]}))

(rf/reg-event-fx
 ::set-param
 (fn [{:keys [db]} [_ iid k v]]
   {:db (in-tile db iid assoc-in [:params k] v)
    :dispatch [::run iid]}))

(rf/reg-event-fx
 ::run
 (fn [{:keys [db]} [_ iid]]
   (let [{:keys [descriptor params]} (get-in db [:tiles iid])]
     {:db (in-tile db iid assoc :status :loading)
      ;; Loregate sits on the same origin, behind the proxy (SPEC 6.1); the query travels
      ;; with the plan id so Loregate can rebuild a plan it no longer holds
      :http {:method "POST"
             :url (str "loregate/api/v1/plans/" (js/encodeURIComponent (:plan/id descriptor)) "/run")
             :body {:query (:tile/query descriptor) :params params}
             :token (:user db)
             :on-ok [::rows-received iid] :on-error [::failed iid]}})))

(rf/reg-event-fx
 ::select
 ;; a gesture on a row: each emitted event carries that row's value of its column onto the
 ;; mosaic's bus (SPEC 5.4). Values only, never rows; an empty value is not emitted.
 (fn [{:keys [db]} [_ iid row]]
   (let [{:keys [mosaic descriptor]} (get-in db [:tiles iid])]
     {:db (in-tile db iid assoc :selected row)
      :dispatch-n (for [{:keys [event column]} (get-in descriptor [:tile/events :emits])
                        :let [v (get row column)]
                        :when (and mosaic (some? v))]
                    [:bus/publish mosaic event v])})))

(defn decode
  "Loregate's wire values back into what the renderers use, column by column: decimals
  arrive as strings (to keep their precision on the wire) and become numbers for display
  and charts; dates stay \"YYYY-MM-DD\" strings; as-of times become Dates."
  [{:keys [columns] :as r}]
  (let [decimals (into #{} (comp (filter #(= :decimal (:type %))) (map :name)) columns)
        num (fn [v] (if (string? v) (js/parseFloat v) v))]
    (-> r
        (update :rows (fn [rows] (mapv (fn [row] (reduce #(update %1 %2 num) row (filter #(contains? row %) decimals))) rows)))
        (update-in [:notes :as-of] #(update-vals (or % {}) (fn [t] (js/Date. t)))))))

(rf/reg-event-db ::rows-received (fn [db [_ iid r]] (in-tile db iid assoc :status :ok :result (decode r) :error nil)))
(rf/reg-event-db ::failed (fn [db [_ iid e]] (in-tile db iid assoc :status :error :error (:error e))))

;; --- view model -------------------------------------------------------------

(def ^:private error-texts
  {:plan/stale "This tile is being updated. Try again shortly."
   :plan/not-found "This tile's plan is not available."
   :params/invalid "One of the settings is not valid."
   :source/timeout "A data source did not answer in time."
   :source/unavailable "A data source is unavailable."
   :source/schema-mismatch "A data source answered in an unexpected shape."
   :source/row-cap-exceeded "There is too much data for this tile."
   :budget/busy "Too many tiles are loading at once. Try again shortly."
   :network/unreachable "The data service cannot be reached."
   :not-found "This tile does not exist."})

(defn error-text [e]
  (or (error-texts e)
      (when (and (keyword? e) (= "auth" (namespace e))) "You are not signed in.")
      "This tile could not be loaded."))

(defn messages
  "What the run's notes mean for the viewer: an empty column or a short list must explain
  itself, and two people may see different totals on the same tile."
  [{:keys [hidden partial rows-filtered truncated]}]
  (concat (for [a (sort hidden)] (str (render/label a) " is hidden from you"))
          (for [a (sort partial)] (str (render/label a) " may be hidden on some rows"))
          (when (seq rows-filtered) ["Rows may be limited by your access"])
          (when truncated ["Not all rows are shown"])))

(rf/reg-sub ::tile (fn [db [_ iid]] (get-in db [:tiles iid])))

(rf/reg-sub ::status (fn [[_ iid]] (rf/subscribe [::tile iid])) (fn [t] (:status t)))

(rf/reg-sub
 ::view-model
 (fn [[_ iid]] (rf/subscribe [::tile iid]))
 (fn [{:keys [descriptor result status error params mosaic]}]
   (let [as-of (vals (get-in result [:notes :as-of]))]
     {:title (:tile/title descriptor)
      :view (:tile/view descriptor)
      :columns (or (:columns result) (:plan/output descriptor))
      :rows (:rows result)
      :param-defs (get-in descriptor [:tile/query :params])
      :params params
      :status status
      :error (when error (error-text error))
      :messages (messages (:notes result))
      :as-of (when (seq as-of) (apply min-key #(.getTime %) as-of))
      :health (:health descriptor)
      :lineage (not-empty (:tile/lineage descriptor))
      :selectable? (boolean (and mosaic (seq (get-in descriptor [:tile/events :emits]))))})))

;; --- view -------------------------------------------------------------------

(defn- param-control [iid k {:keys [type options]} value]
  (let [commit #(rf/dispatch [::set-param iid k %])
        parse (case type :int js/parseInt :decimal js/parseFloat identity)
        on-commit (fn [e] (let [s (.. e -target -value)]
                            (when-not (str/blank? s) (commit (parse s)))))]
    [:label.param (render/label k)
     (cond
       options [:select {:value (str value) :on-change #(commit (parse (.. % -target -value)))}
                (for [o options] ^{:key (str o)} [:option {:value (str o)} (str o)])]
       (= :boolean type) [:input {:type "checkbox" :checked (boolean value)
                                  :on-change #(commit (.. % -target -checked))}]
       :else
       ;; semi-controlled: committed on blur or Enter, so typing does not run the tile per key
       [:input {:key (str value)
                :type (case type (:int :decimal) "number" :date "date" :datetime "datetime-local" "text")
                :default-value (str value)
                :on-blur on-commit
                :on-key-down #(when (= "Enter" (.-key %)) (on-commit %))}])]))

(defn tile-card [iid]
  (let [{:keys [title view columns rows param-defs params status error messages as-of health lineage selectable?]}
        @(rf/subscribe [::view-model iid])]
    [:section.tile {:data-status (some-> status name)}
     [:header
      [:h2 (or title "…")]
      (when (and health (not= :valid health))
        [:span.badge {:class (name health)} (if (= :stale health) "Updating" "Needs repair")])]
     (when (seq param-defs)
       [:div.params (for [[k d] param-defs] ^{:key k} [param-control iid k d (get params k)])])
     (cond
       error [:p.error error]
       (nil? rows) [:p.muted "Loading…"]
       (empty? rows) [:p.muted.empty-result "Nothing to show."]
       :else [render/render-view {:view view :columns columns :rows rows
                                  :on-select (when selectable? #(rf/dispatch [::select iid %]))}])
     (when (seq messages)
       [:ul.notes (for [m messages] ^{:key m} [:li m])])
     [:footer
      (when as-of [:span.muted "Data as of " (.toLocaleString as-of)])
      (when lineage [:details [:summary "Where this comes from"] [:p lineage]])]]))
