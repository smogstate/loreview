(ns loreview.schema.checks
  "Expectation checks (SPEC 9.2): a closed vocabulary of statements about a tile's result.
  Syntax only; evaluation is phase 2.")

(def Column
  "A column of a plan's output contract, as Loregate names it."
  [:or qualified-keyword? simple-keyword?])

(def Check
  [:multi {:dispatch first}
   [:top-n-contains    [:tuple [:= :top-n-contains] pos-int? Column any?]]
   [:sum-between       [:tuple [:= :sum-between] Column number? number?]]
   [:row-count-between [:tuple [:= :row-count-between] nat-int? nat-int?]]
   [:not-empty         [:tuple [:= :not-empty]]]
   [:unique-key        [:tuple [:= :unique-key] [:vector {:min 1} Column]]]
   [:no-nulls          [:tuple [:= :no-nulls] Column]]])

(def Expectation
  [:map {:closed true}
   [:desc string?]
   [:check Check]])

(defn columns
  "The output columns a check refers to."
  [[op & args]]
  (case op
    (:top-n-contains) [(second args)]
    (:sum-between :no-nulls) [(first args)]
    :unique-key (first args)
    []))
