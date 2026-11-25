(ns loreview.client-test
  "The HTTP gate against Loregate's own HTTP server (a real socket, on a free port): it
  answers as the embedded library does, and approval over HTTP tests the plan that runs."
  (:require [clojure.test :refer [deftest is testing]]
            [loregate.api.http :as lhttp]
            [loreview.check.approval :as approval]
            [loreview.dev :as dev]
            [loreview.gate.client :as client]
            [loreview.gate.library :as library]
            [loreview.gate.port :as gate]
            [loreview.store.log :as log]))

(defn- with-loregate [f]
  (let [sys (dev/loregate-system)
        users (dev/loregate-users)
        server (lhttp/start (lhttp/handler sys (dev/dev-identity users)) {:port 0 :host "127.0.0.1"})]
    (try (f sys users (client/gate (str "http://127.0.0.1:" (.getPort (.getAddress server)))))
         (finally (.stop server 0)))))

(deftest over-http-as-in-process
  (with-loregate
    (fn [sys users http]
      (let [lib (library/gate sys users)
            q (:tile/query (first (filter #(= :tile/unpaid-invoices (:tile/id %)) dev/tiles)))
            c (gate/compile-query http "ana" q)]
        (is (= (:plan/id c) (:plan/id (gate/compile-query lib "ana" q))) "the same plan id either way")
        (testing "rows decode to the library's values"
          (is (= (:rows (gate/run-plan lib "ana" (:plan/id c) q {}))
                 (:rows (gate/run-plan http "ana" (:plan/id c) q {}))))
          (is (decimal? (-> (gate/run-plan http "ana" (:plan/id c) q {}) :rows first :biz/unpaid-total))))
        (is (= #{:biz/amount} (set (-> (gate/run-plan http "bob" (:plan/id c) q {}) :notes :hidden))))
        (is (false? (:stale? (gate/plan-status http "ana" (:plan/id c)))))
        (testing "errors"
          (is (= :auth/missing-token (:error (gate/run-plan http nil (:plan/id c) q {}))))
          (is (= :compile/unknown-name (:error (gate/compile-query http "ana" (assoc q :where [:> :biz/nope 1]))))))
        (is (= :source/unavailable (:error (gate/compile-query (client/gate "http://127.0.0.1:1") "ana" q))))))))

(deftest approval-over-http
  (with-loregate
    (fn [sys users http]
      (let [l (log/open-file-log (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn"))
            r (approval/approve! {:log l :gate http :lib sys :token "ana" :author "user:ana" :test-user (users "ana")
                                  :clock {:now #inst "2026-11-24T12:00:00Z" :zone "UTC"}}
                                 (first (filter #(= :tile/unpaid-invoices (:tile/id %)) dev/tiles)))]
        (is (= 1 (:tile/version r)) (pr-str r))))))
