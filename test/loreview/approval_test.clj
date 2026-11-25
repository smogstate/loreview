(ns loreview.approval-test
  "Phase 2: deterministic tests through the embedded Loregate, approval, and staleness —
  against Loregate's own fixture ontology, users and Parquet data (../loregate)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [loregate.core :as lg]
            [loregate.ontology.memory :as mem]
            [loregate.sources.parquet :as pq]
            [loregate.store.memory :as mstore]
            [loreview.check.approval :as approval]
            [loreview.check.deterministic :as det]
            [loreview.fixtures :as fx]
            [loreview.gate.library :as library]
            [loreview.store.log :as log]))

(def lg-dir "../loregate/dev/fixtures/")

(def decls
  ;; binding paths are relative to Loregate's folder; from here they live one level up
  (walk/postwalk #(if (and (string? %) (str/starts-with? % "dev/fixtures/")) (str "../loregate/" %) %)
                 (edn/read-string (slurp (str lg-dir "ontology.edn")))))

(def users (into {} (map (juxt :user/id identity)) (edn/read-string (slurp (str lg-dir "users.edn")))))
(def clock {:now #inst "2026-11-24T12:00:00Z" :zone "UTC"})

(defn- system [decls store]
  (lg/system {:ontology (mem/create decls) :sources {:parquet (pq/adapter [lg-dir])} :store store}))

(defn- ctx [decls store l]
  (let [sys (system decls store)]
    {:log l :gate (library/gate sys users) :lib sys :token "ana" :author "user:ana"
     :test-user (users "ana") :clock clock}))

(defn- temp-log []
  (log/open-file-log (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn")))

(defn- change-def [decls id f] (mapv #(if (= id (:def/id %)) (f %) %) decls))

(deftest deterministic-cases-pass-for-the-fixture-tiles
  (let [sys (system decls (mstore/store))]
    (doseq [t [fx/unpaid fx/invoices]]
      (let [plan (lg/compile sys (:tile/query t))
            {:keys [problems golden]} (det/run-all sys t plan (users "ana") clock)]
        (is (= [] problems) (str (:tile/id t)))
        (is (seq (:rows golden)) "the base case is not empty, so the golden case means something")
        (is (:same? (det/reproduce sys (:plan/id plan) golden)) "a golden case reproduces")))))

(deftest deterministic-runs-apply-the-test-users-rules
  (let [sys (system decls (mstore/store))
        plan (lg/compile sys (:tile/query fx/unpaid))
        {:keys [problems golden]} (det/run-all sys fx/unpaid plan (users "bob") clock)]
    (is (= [] problems))
    (is (seq (:rows golden)) "bob's region is in the pool, so he still sees some rows")
    (is (every? nil? (map :biz/unpaid-total (:rows golden))) "amounts stay hidden from bob")
    (is (:same? (det/reproduce sys (:plan/id plan) golden)))))

(deftest generated-rows-connect-and-pass-rules
  (let [sys (system decls (mstore/store))
        plan (lg/compile sys (:tile/query fx/unpaid))
        rels (det/relations sys (:plan/inputs plan) (users "bob") clock {:n 20 :seed 1})]
    (is (= #{[] [:biz.invoice/client]} (set (keys rels))))
    (testing "every invoice finds its client: link attributes draw from the key values"
      (is (every? (set (map :biz/client-id (rels [:biz.invoice/client])))
                  (map :biz/client-id (rels [])))))
    (testing "the user's own region is in the pool, so a region rule lets some rows through"
      (let [r (det/relations sys [{:path [] :type :biz/client :attributes #{:biz/client-id :biz/region}}]
                             (users "bob") clock {:n 20 :seed 1})]
        (is (some #{"US"} (map :biz/region (r []))))))
    (testing "the query's own parameter values are in the pool, so its filter matches"
      (let [r (det/relations sys [{:path [] :type :biz/client :attributes #{:biz/client-id :biz/client-name}}]
                             (users "bob") clock {:n 20 :seed 1 :strings ["ACME"]})]
        (is (some #{"ACME"} (map :biz/client-name (r []))))))))

(deftest approval
  (let [l (temp-log) c (ctx decls (mstore/store) l)]
    (testing "the example tile is approved, with its golden case"
      (let [r (approval/approve! c fx/unpaid)
            x (get-in (log/projection l) [:tiles :tile/unpaid-invoices :versions 1])]
        (is (= 1 (:tile/version r)))
        (is (= (:plan/id r) (:plan/id x)))
        (is (= {:roles #{"role:finance" "role:crm-admin"} :region "EU" :principals #{"user:ana" "group:finance"}}
               (:test-user x)))
        (is (seq (get-in x [:golden :rows])))))
    (testing "the log, golden case and dates included, survives a reopen"
      (is (= (log/projection l) (log/projection (log/open-file-log (.getPath ^java.io.File (:file l)))))))
    (testing "a second approval is version 2"
      (is (= 2 (:tile/version (approval/approve! c fx/unpaid)))))
    (testing "refusals"
      (is (= :approval/view-mismatch (:error (approval/approve! c (assoc-in fx/unpaid [:tile/view :y] :biz/client-name)))))
      (is (= :approval/expectations-failed
             (:error (approval/approve! c (assoc fx/unpaid :tile/expectations
                                                 [{:desc "ACME first" :check [:top-n-contains 1 :biz/client-name "ACME"]}])))))
      (is (= :approval/invalid-tile (:error (approval/approve! c (assoc-in fx/unpaid [:tile/view :y] :nope)))))
      (is (= :approval/does-not-compile (:error (approval/approve! c (assoc-in fx/unpaid [:tile/query :where] [:> :biz/nope 1]))))))))

(deftest staleness
  (let [store (mstore/store)
        l (temp-log)
        _ (approval/approve! (ctx decls store l) fx/unpaid)
        plan-of #(get-in (log/projection l) [:tiles :tile/unpaid-invoices :versions 1 :plan/id])
        health #(get-in (log/projection l) [:tiles :tile/unpaid-invoices :versions 1 :health])
        first-plan (plan-of)]
    (is (= [] (approval/refresh! (ctx decls store l))) "nothing changed, nothing to do")

    (testing "a change that does not change the rows: the plan is replaced quietly"
      (let [d2 (change-def decls :biz/unpaid-total #(assoc % :def/owner "team:treasury"))
            [r] (approval/refresh! (ctx d2 store l))]
        (is (= :replaced (:outcome r)) (pr-str r))
        (is (= [:biz/unpaid-total] (:changed r)))
        (is (not= first-plan (plan-of)))
        (is (= :valid (health)))

        (testing "a change of meaning: the golden case gives other rows, the tile needs its author"
          (let [d4 (change-def d2 :biz/unpaid-total #(assoc % :def/expr [:max :biz/amount]))
                [r] (approval/refresh! (ctx d4 store l))]
            (is (= :invalid (:outcome r)))
            (is (= "its golden case gives other rows" (:reason r)))
            (is (= :invalid (health)))))))))

(deftest a-rule-change-retakes-the-golden-case
  (let [store (mstore/store)
        l (temp-log)
        {:keys [plan/id]} (approval/approve! (ctx decls store l) fx/unpaid)
        pins (:plan/pins (lg/plan-status (system decls store) {:plan-id id}))]
    (if-not (some vector? (keys pins))
      ;; Loregate before its phase 2 pins no rules, so a rule change is invisible to it
      (println "SKIP a-rule-change-retakes-the-golden-case: Loregate pins no rules yet")
      (let [d3 (mapv #(if (= [:rows :biz/client] [(:rule/kind %) (:rule/on %)])
                        (assoc % :rule/allow [:= :biz/region [:user :region]]) %) decls)
            [r] (approval/refresh! (ctx d3 store l))]
        (is (= :replaced (:outcome r)) (pr-str r))
        (is (every? vector? (:changed r)))))))
