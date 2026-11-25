(ns loreview.schema.tile
  "A tile's spec (SPEC 5): intent only — query, view, events, expectations. Version, owner,
  lifecycle and health are not in it; they come from the log.

  `problems` checks what can be checked without Loregate: names used by the view, the
  events and the expectations against the query's own output names. Types are checked
  against the compiled plan's output contract later (phase 2)."
  (:require [loregate.schema.query :as query]
            [loreview.schema.checks :as checks]
            [malli.core :as m]))

(def TileId qualified-keyword?)
(def Column checks/Column)

(def View
  [:multi {:dispatch :type}
   [:table [:map {:closed true} [:type [:= :table]]
            [:columns {:optional true} [:vector {:min 1} Column]]
            [:format {:optional true} [:map-of Column keyword?]]]]
   [:bar   [:map {:closed true} [:type [:= :bar]] [:x Column] [:y Column]
            [:format {:optional true} [:map-of Column keyword?]]]]
   [:line  [:map {:closed true} [:type [:= :line]] [:x Column] [:y Column]
            [:series {:optional true} Column]
            [:format {:optional true} [:map-of Column keyword?]]]]
   [:kpi   [:map {:closed true} [:type [:= :kpi]] [:value Column]
            [:compare {:optional true} Column]
            [:format {:optional true} [:map-of Column keyword?]]]]])

(def TileEvents
  [:map {:closed true}
   [:emits {:optional true}
    [:vector [:map {:closed true} [:event simple-keyword?] [:column Column]]]]
   [:listens {:optional true}
    [:vector [:map {:closed true} [:event simple-keyword?] [:param simple-keyword?]]]]])

(def Tile
  [:map {:closed true}
   [:tile/id TileId]
   [:tile/title [:string {:min 1}]]
   [:tile/query query/Query]
   [:tile/view View]
   [:tile/events {:optional true} TileEvents]
   [:tile/expectations {:optional true} [:vector checks/Expectation]]])

(defn output-names
  "The query's output column names, in order, by Loregate's naming rule (its SPEC 6.2): a
  keyword names itself, a path is named by its last element, a local by its name."
  [query]
  (mapv #(if (vector? %) (peek %) %) (:select query)))

(defn- view-columns [{:keys [type] :as view}]
  (concat (case type
            :table (:columns view)
            (:bar :line) (keep view [:x :y :series])
            :kpi (keep view [:value :compare]))
          (keys (:format view))))

(defn problems
  "Problems with a tile beyond its schema, as data; empty when none. Assumes a valid tile."
  [{:tile/keys [query view events expectations]}]
  (let [out (set (output-names query))
        params (set (keys (:params query)))
        unknown (fn [at cols] (for [c cols :when (not (out c))]
                                {:problem :tile/unknown-column :at at :column c}))]
    (vec (concat
          (unknown [:tile/view] (view-columns view))
          (unknown [:tile/events :emits] (map :column (:emits events)))
          (unknown [:tile/expectations] (mapcat (comp checks/columns :check) expectations))
          (for [{:keys [param]} (:listens events) :when (not (params param))]
            {:problem :tile/unknown-param :at [:tile/events :listens] :param param})))))

(defn explain
  "nil for a sound tile; else {:errors <malli explanation>} or {:problems […]}."
  [tile]
  (if-let [e (m/explain Tile tile)]
    {:errors e}
    (when-let [ps (seq (problems tile))] {:problems (vec ps)})))
