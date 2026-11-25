(ns loreview.check-test
  "Phase 2 (the parts that need no Loregate run): views against the output contract, and
  expectations against rows. The rows are what the real Loregate returned for the fixture
  tile and user ana (verified 2026-11-24 against its phase 1)."
  (:require [clojure.test :refer [deftest is testing]]
            [loreview.check.expectations :as ex]
            [loreview.check.view :as view]
            [loreview.fixtures :as fx]))

(def ana-rows
  [{:biz/client-name "Umbrella" :biz/unpaid-total 7200.00M :n 1}
   {:biz/client-name "ACME"     :biz/unpaid-total 4200.00M :n 3}
   {:biz/client-name nil        :biz/unpaid-total 999.00M  :n 1}
   {:biz/client-name "Initech"  :biz/unpaid-total 600.00M  :n 2}
   {:biz/client-name "Globex"   :biz/unpaid-total 450.00M  :n 2}])

(def bob-rows
  [{:biz/client-name nil :biz/unpaid-total nil :n 5}
   {:biz/client-name "Globex" :biz/unpaid-total nil :n 2}
   {:biz/client-name "Initech" :biz/unpaid-total nil :n 2}])

(deftest views-fit-the-output
  (is (= [] (view/problems fx/unpaid fx/unpaid-output)))
  (testing "a bar's y must be a number"
    (is (= [{:problem :view/not-numeric :at [:tile/view :y] :column :biz/client-name :type :string}]
           (view/problems (assoc-in fx/unpaid [:tile/view :y] :biz/client-name) fx/unpaid-output))))
  (testing "a line's x must be ordered"
    (is (= [:view/not-ordered]
           (map :problem (view/problems (assoc fx/unpaid :tile/view {:type :line :x :biz/client-name :y :n}) fx/unpaid-output)))))
  (testing "a tile emitting and listening to one event must agree with itself"
    (is (= [:tile/event-type-mismatch]
           (map :problem (view/problems (assoc-in fx/unpaid [:tile/events :listens 0 :event] :client-selected)
                                        fx/unpaid-output)))))
  (testing "a kpi shows exactly one row"
    (is (nil? (view/row-problems fx/contract [{:biz/contract-total 1M}])))
    (is (= [{:problem :view/kpi-not-one-row :rows 0}] (view/row-problems fx/contract [])))))

(deftest expectations
  (is (every? :passed? (ex/evaluate (:tile/expectations fx/unpaid) ana-rows)) "the example's own expectations hold for ana")
  (let [ok? (fn [check rows] (ex/passed? check rows))]
    (is (ok? [:top-n-contains 2 :biz/client-name "ACME"] ana-rows))
    (is (not (ok? [:top-n-contains 1 :biz/client-name "ACME"] ana-rows)))
    (is (ok? [:sum-between :biz/unpaid-total 13000 14000] ana-rows) "13 449.00")
    (is (not (ok? [:sum-between :biz/unpaid-total 0 1e9] bob-rows)) "a hidden column has no sum to pass with")
    (is (ok? [:row-count-between 1 5] ana-rows))
    (is (not (ok? [:not-empty] [])))
    (is (ok? [:unique-key [:biz/client-name]] ana-rows))
    (is (not (ok? [:unique-key [:n]] ana-rows)))
    (is (not (ok? [:no-nulls :biz/client-name] ana-rows)) "the client nobody has")
    (is (ok? [:no-nulls :n] bob-rows))))
