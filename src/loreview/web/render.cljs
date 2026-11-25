(ns loreview.web.render
  "Renderers (SPEC 11.2): one method per view type, each given the view, the output
  contract's columns and the rows. A view type with no renderer yet falls back to the table."
  (:require ["vega-embed" :default embed]
            [clojure.string :as str]
            [reagent.core :as r]))

(defn label
  "A readable name for a column or attribute keyword. ponytail: from the keyword itself;
  titles from the ontology come with describe (phase 4)."
  [k]
  (str/capitalize (str/replace (name k) "-" " ")))

(defn- currency [unit]
  (when (= "currency" (some-> unit namespace)) (str/upper-case (name unit))))

(defn format-value [{:keys [type unit]} v]
  (cond
    (nil? v) nil
    (and (number? v) (currency unit)) (.toLocaleString v js/undefined #js {:style "currency" :currency (currency unit)})
    (number? v) (.toLocaleString v)
    (#{:date :datetime} type) (str v)
    :else (str v)))

(defmulti render-view (fn [{:keys [view]}] (:type view)))

(defmethod render-view :table [{:keys [view columns rows on-select]}]
  (let [by-name (into {} (map (juxt :name identity)) columns)
        shown (map by-name (or (:columns view) (map :name columns)))]
    [:table.data
     [:thead [:tr (for [c shown] ^{:key (:name c)} [:th {:class (when (#{:int :decimal} (:type c)) "num")} (label (:name c))])]]
     [:tbody
      (for [[i row] (map-indexed vector rows)]
        ^{:key i}
        [:tr (when on-select {:class "selectable" :on-click #(on-select row)})
         (for [c shown
                   :let [v (format-value c (get row (:name c)))]]
               ^{:key (:name c)}
               [:td {:class (str (when (#{:int :decimal} (:type c)) "num") (when (nil? v) " empty"))}
                (or v "—")])])]]))

(defmethod render-view :default [vm] (render-view (assoc vm :view {:type :table})))

(defn- field [k] (subs (str k) 1))            ; :biz/client-name → "biz/client-name"

(defn- vega
  "A Vega-Lite chart. Redrawn only when the spec changes; `on-click` gets the clicked
  item's datum (a JS object) and may change between renders without a redraw."
  [spec on-click]
  (let [el (atom nil)
        view (atom nil)
        click (atom on-click)
        draw! (fn [s]
                (when @el
                  (some-> @view .finalize)
                  (-> (embed @el (clj->js s) #js {:actions false :renderer "svg"})
                      (.then (fn [res]
                               (reset! view (.-view res))
                               (.addEventListener (.-view res) "click"
                                                  (fn [_ item] (when-let [d (some-> item .-datum)]
                                                                 (when-let [f @click] (f d))))))))))]
    (r/create-class
     {:display-name "vega"
      :component-did-mount (fn [_] (draw! spec))
      :component-did-update (fn [this old-argv]
                              (let [new-spec (second (r/argv this))]
                                (when (not= new-spec (second old-argv)) (draw! new-spec))))
      :component-will-unmount (fn [_] (some-> @view .finalize))
      :reagent-render (fn [_ f] (reset! click f) [:div.chart {:ref #(reset! el %)}])})))

(defmethod render-view :bar [{:keys [view columns rows on-select] :as vm}]
  (let [{:keys [x y]} view
        by-name (into {} (map (juxt :name identity)) columns)
        cur (currency (:unit (by-name y)))
        xs (mapv #(or (get % x) "—") rows)]   ; an empty category (hidden or missing link) is "—"
    (if (not-any? #(number? (get % y)) rows)
      ;; nothing to draw (e.g. the measured field is hidden from this viewer): the table
      ;; still shows what they may see; the tile's notes say why the values are empty
      (render-view (assoc vm :view {:type :table}))
      [vega {:$schema "https://vega.github.io/schema/vega-lite/v6.json"
           :width "container" :height 280
           :data {:values (map-indexed (fn [i [xv row]] {(field x) xv (field y) (get row y) "row" i})
                                       (map vector xs rows))}
           :mark {:type "bar" :tooltip true :cursor (if on-select "pointer" "default")}
           ;; the domain keeps every category on the axis, even one whose bar is empty
           :encoding {:x {:field (field x) :type "nominal" :sort nil :title (label x)
                          :scale {:domain (distinct xs)} :axis {:labelAngle 0}}
                      :y {:field (field y) :type "quantitative"
                          :title (str (label y) (when cur (str " (" cur ")")))}
                      :tooltip [{:field (field x) :title (label x)} {:field (field y) :title (label y)}]}}
       (when on-select #(some->> (aget % "row") (nth rows) on-select))])))

(defmethod render-view :kpi [{:keys [view columns rows] :as vm}]
  (let [{:keys [value compare]} view
        by-name (into {} (map (juxt :name identity)) columns)
        row (first rows)
        v (get row value)
        c (when compare (get row compare))]
    (if (not= 1 (count rows))
      (render-view (assoc vm :view {:type :table}))   ; a kpi is one row; anything else is shown as it is
      [:div.kpi
       [:div.kpi-value {:class (when (nil? v) "empty")} (or (format-value (by-name value) v) "—")]
       [:div.kpi-label (label value)]
       (when (and (number? v) (number? c))
         [:div.kpi-compare {:class (if (>= v c) "up" "down")}
          (str (if (>= v c) "▲ " "▼ ") (format-value (by-name value) (js/Math.abs (- v c)))
               " vs " (label compare))])])))

(defmethod render-view :line [{:keys [view columns rows] :as vm}]
  (let [{:keys [x y series]} view
        by-name (into {} (map (juxt :name identity)) columns)
        temporal? (#{:date :datetime} (:type (by-name x)))]
    (if (not-any? #(number? (get % y)) rows)
      (render-view (assoc vm :view {:type :table}))
      [vega {:$schema "https://vega.github.io/schema/vega-lite/v6.json"
             :width "container" :height 280
             :data {:values (for [row rows]
                              (cond-> {(field x) (get row x) (field y) (get row y)}
                                series (assoc (field series) (or (get row series) "—"))))}
             :mark {:type "line" :point true :tooltip true}
             :encoding (cond-> {:x {:field (field x) :type (if temporal? "temporal" "quantitative") :title (label x)}
                                :y {:field (field y) :type "quantitative" :title (label y)}}
                         series (assoc :color {:field (field series) :type "nominal" :title (label series)}))}
       nil])))
