(ns loreview.schema-test
  "Phase 0: the fixture tiles validate; problems are found without Loregate; every log
  event type has a valid example."
  (:require [clojure.test :refer [deftest is testing]]
            [loreview.fixtures :as fx]
            [loreview.schema.log :as log]
            [loreview.schema.tile :as tile]
            [loreview.store.log :refer [event]]
            [malli.core :as m]
            [malli.generator :as mg]))

(deftest fixture-tiles-are-sound
  (doseq [t fx/tiles]
    (is (nil? (tile/explain t)) (str (:tile/id t)))))

(deftest output-names-follow-loregate
  (is (= [:biz/client-name :biz/unpaid-total :n] (tile/output-names (:tile/query fx/unpaid)))))

(deftest problems-found-without-loregate
  (testing "a view column the query does not select"
    (is (= [{:problem :tile/unknown-column :at [:tile/view] :column :biz/amount}]
           (:problems (tile/explain (assoc-in fx/unpaid [:tile/view :y] :biz/amount))))))
  (testing "an emitted column and an expectation column the query does not select"
    (is (= #{[:tile/events :emits] [:tile/expectations]}
           (set (map :at (:problems (tile/explain
                                     (-> fx/unpaid
                                         (assoc-in [:tile/events :emits 0 :column] :biz/region)
                                         (assoc-in [:tile/expectations 0 :check 2] :biz/region)))))))))
  (testing "a listened event aimed at a parameter the query does not have"
    (is (= [{:problem :tile/unknown-param :at [:tile/events :listens] :param :as-of}]
           (:problems (tile/explain (assoc-in fx/unpaid [:tile/events :listens 0 :param] :as-of)))))))

(deftest the-query-is-loregates
  (testing "a vector literal is a path in Loregate's DSL, so :in needs a set"
    (is (:errors (tile/explain (assoc-in fx/unpaid [:tile/query :where]
                                         [:in :biz/invoice-status [:biz.status/paid]]))))
    (is (nil? (tile/explain (assoc-in fx/unpaid [:tile/query :where]
                                      [:in :biz/invoice-status #{:biz.status/paid}]))))))

(deftest schemas-refuse-what-they-should
  (is (:errors (tile/explain (assoc fx/unpaid :tile/version 3))) "version is not part of a spec")
  (is (:errors (tile/explain (assoc fx/unpaid :tile/view {:type :map :geo :biz/region}))) "no map view in v1")
  (is (:errors (tile/explain (assoc-in fx/unpaid [:tile/expectations 0 :check] [:looks-right])))))

(def examples
  (let [u "user:ana" t (:tile/id fx/unpaid) ok (fx/approval fx/unpaid 1)]
    [(event :tile/drafted u {:tile/id t :prompt "unpaid invoices by client"})
     (event :tile/interview-step u {:tile/id t :task :understand :prompt "…" :output {:terms ["invoice"]}})
     (event :tile/interview-answered u {:tile/id t :question "Which unpaid?" :answer :biz/unpaid-total})
     (event :tile/spec-proposed u {:tile/id t :tile/spec fx/unpaid})
     (event :tile/tested u {:tile/id t :plan/id (:plan/id ok) :results []})
     (event :tile/approved u ok)
     (event :tile/plan-replaced "system:loreview" (assoc (select-keys ok [:tile/id :tile/version :plan/id :plan/output :plan/pins])
                                                        :cause [:biz/unpaid-total [:rows :biz/client]]))
     (event :tile/health-changed "system:loreview" {:tile/id t :tile/version 1 :health :stale :reason "pins changed"})
     (event :tile/expectation-failed u {:tile/id t :tile/version 1 :check [:not-empty]})
     (event :tile/published u {:tile/id t :tile/version 1 :name "tile_unpaid_invoices_v1"})
     (event :tile/unpublished u {:tile/id t :tile/version 1})
     (event :tile/repair-proposed "system:loreview" {:tile/id t :tile/version 1 :tile/spec fx/unpaid :cause {}})
     (event :tile/retired u {:tile/id t :tile/version 1})
     (event :concept/proposed u {:proposal {:term "overdue"} :from-tile t})
     (event :mosaic/saved u {:mosaic/id :mosaic/finance :mosaic/title "Finance"
                             :mosaic/tiles [{:tile/id t :tile/version 1 :x 0 :y 0 :w 6 :h 4}]})]))

(deftest every-event-type-has-a-valid-example
  (is (= (set (keys log/payloads)) (set (map :event/type examples))))
  (doseq [e examples]
    (is (m/validate log/Event e) (str (:event/type e) " " (m/explain log/Event e)))))

(deftest events-are-closed
  (is (not (m/validate log/Event (assoc (first examples) :by "user:ana"))) "the author is :event/actor, never :by")
  (is (not (m/validate log/Event (assoc (first examples) :event/actor "ana")))))

(deftest generated-tiles-validate
  (doseq [t (mg/sample tile/Tile {:size 5 :seed 7})]
    (is (m/validate tile/Tile t))))
