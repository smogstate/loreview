(ns loreview.publishing-test
  "Phase 5: publishing through Loregate. A published tile can be run by name by another
  caller, under that caller's own rules; only the owner publishes, only with Loregate's
  publishing role; retiring a published version takes its name away."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [loregate.api.http :as lhttp]
            [loregate.core :as lg]
            [loreview.check.approval :as approval]
            [loreview.dev :as dev]
            [loreview.gate.library :as library]
            [loreview.store.log :as log]))

(def unpaid (first (filter #(= :tile/unpaid-invoices (:tile/id %)) dev/tiles)))

(defn- setup [sys]
  (let [l (log/open-file-log (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn"))
        users (dev/loregate-users)
        ctx {:log l :gate (library/gate sys users) :lib sys :token "ana" :author "user:ana"
             :test-user (users "ana") :clock {:now #inst "2026-11-24T12:00:00Z" :zone "UTC"}}]
    (approval/approve! ctx unpaid)
    ctx))

(defn- run-named [sys token n]
  (let [h (lhttp/handler sys (dev/dev-identity (dev/loregate-users)))
        r (h {:method "POST" :path (str "/api/v1/named/" n "/run")
              :headers {"authorization" (str "Bearer " token) "content-type" "application/edn" "accept" "application/edn"}
              :body (pr-str {:params {}})})]
    [(:status r) (edn/read-string (:body r))]))

(deftest published-tiles-run-by-name-under-the-callers-rules
  (let [sys (dev/loregate-system)
        {:keys [log] :as ctx} (setup sys)
        n "tile_unpaid_invoices_v1"]
    (is (= {:name n} (approval/publish! ctx :tile/unpaid-invoices 1)))
    (is (= n (get-in (log/projection log) [:tiles :tile/unpaid-invoices :versions 1 :published])))
    (testing "listed by Loregate's describe, with the tile's title as its description"
      (is (= [[n "Unpaid invoices over 30 days by client"]]
             (map (juxt :name :description) (:named (lg/describe sys {:user ((dev/loregate-users) "bob")})))))
      (testing "another caller runs it by name, under their own rules"
        (let [[s ana] (run-named sys "ana" n)
              [_ bob] (run-named sys "bob" n)]
          (is (= 200 s))
          (is (= "7200.00" (-> ana :rows first :biz/unpaid-total)))
          (is (every? nil? (map :biz/unpaid-total (:rows bob))))
          (is (= #{:biz/amount} (set (-> bob :notes :hidden)))))))
    (testing "only the owner publishes or unpublishes"
      (is (= {:error :tile/not-owner} (approval/unpublish! (assoc ctx :token "bob" :author "user:bob") :tile/unpaid-invoices 1))))
    (testing "retiring a published version takes its name away"
      (is (= {:tile/version 1} (approval/retire! ctx :tile/unpaid-invoices 1)))
      (is (nil? (get-in (log/projection log) [:tiles :tile/unpaid-invoices :versions 1 :published])))
      (is (= 404 (first (run-named sys "bob" n))))
      (is (= {:error :tile/retired} (approval/publish! ctx :tile/unpaid-invoices 1))))))

(deftest publishing-needs-loregates-role
  (testing "bob owns a tile but lacks the publishing role"
    (let [sys (dev/loregate-system)
          users (dev/loregate-users)
          l (log/open-file-log (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn"))
          ctx {:log l :gate (library/gate sys users) :lib sys :token "bob" :author "user:bob"
               :test-user (users "bob") :clock {:now #inst "2026-11-24T12:00:00Z" :zone "UTC"}}
          t (assoc unpaid :tile/expectations [])]            ; bob does not see ACME
      (is (= 1 (:tile/version (approval/approve! ctx t))))
      (is (= {:error :auth/forbidden} (approval/publish! ctx :tile/unpaid-invoices 1)))
      (is (nil? (get-in (log/projection l) [:tiles :tile/unpaid-invoices :versions 1 :published])) "nothing recorded"))))
