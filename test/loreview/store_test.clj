(ns loreview.store-test
  "Phase 0: events replay into the projections; the guard refuses what makes no sense;
  the file log survives a reopen and writes nothing it refused."
  (:require [clojure.test :refer [deftest is testing]]
            [loreview.fixtures :as fx]
            [loreview.store.log :as log :refer [event]]
            [loreview.store.projections :as p]))

(def author "user:ana")
(def sys "system:loreview")
(def u-id (:tile/id fx/unpaid))
(def c-id (:tile/id fx/contract))

(def history
  [(event :tile/drafted author {:tile/id u-id :prompt "unpaid invoices by client"})
   (event :tile/spec-proposed author {:tile/id u-id :tile/spec fx/unpaid})
   (event :tile/approved author (fx/approval fx/unpaid 1))
   (event :tile/published author {:tile/id u-id :tile/version 1 :name "tile_unpaid_invoices_v1"})
   (event :tile/health-changed sys {:tile/id u-id :tile/version 1 :health :stale :reason "pins changed"})
   (event :tile/plan-replaced sys (assoc (select-keys (fx/approval fx/unpaid 1) [:tile/id :tile/version :plan/output :plan/pins])
                                         :plan/id (:plan/id (fx/approval fx/unpaid 9)) :cause [:biz/unpaid-total]))
   (event :tile/approved author (fx/approval fx/unpaid 2))
   (event :tile/retired author {:tile/id u-id :tile/version 1})
   (event :tile/drafted "user:bob" {:tile/id c-id :prompt "contract total"})
   (event :tile/approved "user:bob" (fx/approval fx/contract 1))
   (event :mosaic/saved author {:mosaic/id :mosaic/finance :mosaic/title "Finance"
                               :mosaic/tiles [{:tile/id u-id :tile/version 2 :x 0 :y 0 :w 6 :h 4}
                                              {:tile/id c-id :tile/version 1 :x 6 :y 0 :w 3 :h 2}]})])

(deftest history-is-accepted-event-by-event
  (reduce (fn [proj ev]
            (is (nil? (log/check proj ev)) (str (:event/type ev)))
            (p/apply-event proj ev))
          p/empty-projection history))

(deftest events-replay-into-the-projections
  (let [proj (p/replay history)
        t (get-in proj [:tiles u-id])]
    (is (= author (:owner t)))
    (is (nil? (:draft t)) "approval consumes the draft")
    (is (= 2 (p/current-version proj u-id)) "v1 is retired")
    (testing "v1: replaced plan, valid again, then retired and unpublished"
      (is (= {:lifecycle :retired :health :valid :published nil
              :plan/id (:plan/id (fx/approval fx/unpaid 9))}
             (select-keys (get-in t [:versions 1]) [:lifecycle :health :published :plan/id]))))
    (is (= {:lifecycle :approved :health :valid} (select-keys (get-in t [:versions 2]) [:lifecycle :health])))
    (is (= "user:bob" (get-in proj [:tiles c-id :owner])))
    (is (= 2 (count (get-in proj [:mosaics :mosaic/finance :tiles]))))
    (testing "the catalogue lists live versions only"
      (is (= #{[u-id 2]} (get (p/catalogue proj) :biz/unpaid-total)))
      (is (= #{[c-id 1]} (get (p/catalogue proj) :biz/contract-total))))))

(deftest the-guard-refuses
  (let [proj (p/replay history)
        refused (fn [type actor payload] (:error (log/check proj (event type actor payload))))]
    (is (= :tile/exists (refused :tile/drafted author {:tile/id u-id :prompt "again"})))
    (is (= :tile/unknown (refused :tile/retired author {:tile/id :tile/nope :tile/version 1})))
    (is (= :tile/not-owner (refused :tile/approved "user:bob" (fx/approval fx/unpaid 3))) "the author alone approves")
    (is (= :tile/version-out-of-order (refused :tile/approved author (fx/approval fx/unpaid 5))))
    (is (= :tile/id-mismatch (refused :tile/spec-proposed author {:tile/id u-id :tile/spec fx/contract})))
    (is (= :tile/unknown-version (refused :tile/published author {:tile/id u-id :tile/version 7 :name "x"})))
    (is (= :tile/retired (refused :tile/published author {:tile/id u-id :tile/version 1 :name "x"})))
    (is (= :mosaic/not-owner (refused :mosaic/saved "user:bob" {:mosaic/id :mosaic/finance :mosaic/title "Mine" :mosaic/tiles []})))
    (is (= :mosaic/unknown-tile (refused :mosaic/saved author {:mosaic/id :mosaic/old :mosaic/title "Old"
                                                              :mosaic/tiles [{:tile/id u-id :tile/version 1 :x 0 :y 0 :w 1 :h 1}]})))
    (is (= :log/invalid-event (refused :tile/drafted author {:tile/id u-id})) "schema before guard")))

(deftest mosaic-events-must-agree-on-type
  (let [ana "user:ana"
        int-listener (-> fx/invoices (assoc :tile/id :tile/by-number)
                         (assoc-in [:tile/query :params :client] {:type :int :default 1}))
        proj (p/replay (concat history
                               [(event :tile/drafted ana {:tile/id :tile/client-invoices :prompt "x"})
                                (event :tile/approved ana (fx/approval fx/invoices 1))
                                (event :tile/drafted ana {:tile/id :tile/by-number :prompt "x"})
                                (event :tile/approved ana (assoc (fx/approval int-listener 1) :tile/spec int-listener))]))
        save (fn [& tiles] (:error (log/check proj (event :mosaic/saved ana
                                                         {:mosaic/id :mosaic/m :mosaic/title "M"
                                                          :mosaic/tiles (vec (for [[id v] tiles] {:tile/id id :tile/version v :x 0 :y 0 :w 1 :h 1}))}))))]
    (is (nil? (save [u-id 2] [:tile/client-invoices 1])) "a string column feeds a string param")
    (is (nil? (save [:tile/client-invoices 1])) "a listener with no emitter is allowed")
    (is (= :mosaic/event-type-mismatch (save [u-id 2] [:tile/by-number 1])) "a string column cannot feed an int param")
    (is (= :mosaic/duplicate-tile (save [u-id 2] [u-id 2])))))

(deftest the-file-log-persists-and-refuses
  (let [path (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn")
        l (log/open-file-log path)]
    (doseq [ev history] (is (:event (log/append! l ev))))
    (testing "refused appends write nothing"
      (is (= :tile/exists (:error (log/append! l (first history)))))
      (is (= :log/invalid-event (:error (log/append! l {:event/type :tile/drafted}))))
      (is (= (count history) (count (log/events l)))))
    (testing "a reopened log has the same events and projection"
      (let [again (log/open-file-log path)]
        (is (= (log/events l) (log/events again)))
        (is (= (log/projection l) (log/projection again)))))
    (testing "a log with a refused line does not open"
      (spit path (pr-str (first history)) :append true)
      (spit path "\n" :append true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused" (log/open-file-log path))))))
