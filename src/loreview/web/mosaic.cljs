(ns loreview.web.mosaic
  "A mosaic (SPEC 11.3): tiles placed on a 12-column grid, and a bus for live interaction.
  A tile's emitted value is published under [:mosaic :bus]; every tile of that mosaic
  listening for the event gets it as a parameter and re-runs. Values only — data
  dependencies between tiles never go through the bus."
  (:require [loreview.web.tile :as tile]
            [re-frame.core :as rf]))

(defn instance-id [mosaic-id {:tile/keys [id version]}] [mosaic-id id version])

(rf/reg-event-fx
 :mosaic/load
 (fn [{:keys [db]} [_ id]]
   {:db (assoc db :mosaic {:id id :status :loading :bus {}})
    :http {:url (str "api/mosaics/" (js/encodeURIComponent (subs (str id) 1)))
           :token (:user db) :on-ok [:mosaic/received id] :on-error [:mosaic/failed id]}}))

(rf/reg-event-fx
 :mosaic/received
 (fn [{:keys [db]} [_ id m]]
   {:db (update db :mosaic assoc :status :ok :data m)
    :dispatch-n (for [t (:mosaic/tiles m)]
                  [::tile/load (instance-id id t) (:tile/id t) (:tile/version t) id])}))

(rf/reg-event-db :mosaic/failed (fn [db [_ _ e]] (update db :mosaic assoc :status :error :error (:error e))))

(rf/reg-event-fx
 :bus/publish
 (fn [{:keys [db]} [_ mosaic-id event value]]
   {:db (assoc-in db [:mosaic :bus event] value)
    :dispatch-n (for [[iid t] (:tiles db)
                      :when (= mosaic-id (:mosaic t))
                      {e :event p :param} (get-in t [:descriptor :tile/events :listens])
                      :when (= e event)]
                  [::tile/set-param iid p value])}))

(rf/reg-sub :mosaic (fn [db] (:mosaic db)))

(defn page []
  (let [{:keys [id status data error]} @(rf/subscribe [:mosaic])]
    (case status
      :ok [:<>
           [:h1 (:mosaic/title data)]
           [:div.mosaic
            (for [{:keys [x y w h] :as t} (:mosaic/tiles data)
                  :let [iid (instance-id id t)]]
              ^{:key iid}
              [:div.cell {:style {:grid-column (str (inc x) " / span " w)
                                  :grid-row (str (inc y) " / span " h)}}
               [tile/tile-card iid]])]]
      :error [:p.error (tile/error-text error)]
      [:p.muted "Loading…"])))
