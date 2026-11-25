(ns loreview.check.view
  "Does a tile's view fit its plan's output contract (SPEC 5.3, 5.4)? Pure; run once the
  query has compiled, beside `loreview.schema.tile/problems` (which checks names only).")

(def ^:private numeric #{:int :decimal})

(def ^:private requires
  "view type → [view key, allowed column types, problem] for each typed requirement"
  {:bar  [[:y numeric :view/not-numeric]]
   :line [[:x (into numeric #{:date :datetime}) :view/not-ordered]
          [:y numeric :view/not-numeric]]
   :kpi  [[:value numeric :view/not-numeric]
          [:compare numeric :view/not-numeric]]})

(defn problems
  "Problems of `tile` against `output` (the plan's columns), as data; empty when none."
  [{:tile/keys [view events query]} output]
  (let [types (into {} (map (juxt :name :type)) output)
        params (:params query)
        listens (into {} (map (juxt :event :param)) (:listens events))]
    (vec (concat
          (for [[k allowed problem] (requires (:type view))
                :let [col (get view k)]
                :when (and col (not (allowed (types col))))]
            {:problem problem :at [:tile/view k] :column col :type (types col)})
          ;; a tile that emits and listens to the same event must agree with itself
          (for [{:keys [event column]} (:emits events)
                :let [p (listens event)]
                :when (and p (not= (types column) (get-in params [p :type])))]
            {:problem :tile/event-type-mismatch :at [:tile/events] :event event
             :column-type (types column) :param-type (get-in params [p :type])})))))

(defn row-problems
  "What the view needs of the rows themselves: a kpi shows exactly one row."
  [{:tile/keys [view]} rows]
  (when (and (= :kpi (:type view)) (not= 1 (count rows)))
    [{:problem :view/kpi-not-one-row :rows (count rows)}]))
