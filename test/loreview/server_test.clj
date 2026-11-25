(ns loreview.server-test
  "The routes, called as plain functions: tiles and descriptors, mosaics, approval, and the
  pass-through to Loregate's own HTTP handler — all over the real Loregate (its fixtures)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [loregate.api.http :as lhttp]
            [loreview.api.server :as server]
            [loreview.check.approval :as approval]
            [loreview.dev :as dev]
            [loreview.gate.library :as library]
            [loreview.store.log :as log]))

(def clock #inst "2026-11-24T12:00:00Z")

(defn- setup
  "A handler over a fresh log; with `approved?`, the fixture tiles approved as ana."
  [approved?]
  (let [path (str (java.nio.file.Files/createTempDirectory "loreview" (make-array java.nio.file.attribute.FileAttribute 0)) "/log.edn")
        l (log/open-file-log path)
        sys (dev/loregate-system)
        users (dev/loregate-users)
        g (library/gate sys users)]
    (when approved?
      (doseq [t dev/tiles]
        (approval/approve! {:log l :gate g :lib sys :token "ana" :author "user:ana" :test-user (users "ana")
                            :clock {:now clock :zone "UTC"}}
                           t)))
    {:log l
     :h (server/handler {:log l :gate g :lib sys :identity users :clock (constantly clock)
                         :dev-loregate (lhttp/handler sys (dev/dev-identity users))})}))

(defn- call [h method segments token & [body]]
  (let [r (h {:method method :segments segments :path (apply str (map #(str "/" %) segments)) :token token
              :headers (cond-> {"accept" "application/edn" "content-type" "application/edn"}
                         token (assoc "authorization" (str "Bearer " token)))
              :body (some-> body pr-str)})]
    [(:status r) (edn/read-string (:body r))]))

(deftest tiles-and-descriptors
  (let [{:keys [h]} (setup true)]
    (is (= [401 {:error :auth/missing-token}] (call h "GET" ["api" "tiles"] nil)))
    (let [[s l] (call h "GET" ["api" "tiles"] "bob")]
      (is (= 200 s))
      (is (= #{[:tile/unpaid-invoices 1 :valid] [:tile/client-invoices 1 :valid] [:tile/contract-total 1 :valid]}
             (set (map (juxt :tile/id :tile/version :health) l)))))
    (testing "the descriptor has what the browser draws with, and no expectations"
      (let [[s d] (call h "GET" ["api" "tiles" "tile/unpaid-invoices" "versions" "1"] "bob")]
        (is (= 200 s))
        (is (= #{:tile/id :tile/title :tile/query :tile/view :tile/events :tile/version :tile/lineage
                 :plan/id :plan/output :health :lifecycle :published}
               (set (keys d))))))
    (is (= 404 (first (call h "GET" ["api" "tiles" "tile/unpaid-invoices" "versions" "2"] "bob"))))))

(deftest mosaics
  (let [{:keys [h]} (setup true)]
    (is (= [] (second (call h "GET" ["api" "mosaics"] "ana"))))
    (testing "saving as the caller; the log decides what is allowed"
      (let [tiles [{:tile/id :tile/unpaid-invoices :tile/version 1 :x 0 :y 0 :w 7 :h 1}
                   {:tile/id :tile/client-invoices :tile/version 1 :x 7 :y 0 :w 5 :h 1}]
            [s m] (call h "POST" ["api" "mosaics" "mosaic/r"] "ana" {:mosaic/title "R" :mosaic/tiles tiles :owner "user:mallory"})]
        (is (= 200 s))
        (is (= "user:ana" (:owner m)) "the owner is the caller, whatever the body says")
        (is (= tiles (:mosaic/tiles (second (call h "GET" ["api" "mosaics" "mosaic/r"] "bob")))))
        (is (= [422 {:error :mosaic/not-owner}] (call h "POST" ["api" "mosaics" "mosaic/r"] "bob" {:mosaic/title "Mine" :mosaic/tiles []})))
        (is (= 422 (first (call h "POST" ["api" "mosaics" "mosaic/x"] "ana" {:mosaic/title "X" :mosaic/tiles [{:tile/id :tile/nope :tile/version 1 :x 0 :y 0 :w 1 :h 1}]}))))
        (is (= [{:mosaic/id :mosaic/r :mosaic/title "R"}] (second (call h "GET" ["api" "mosaics"] "ana"))))))
    (is (= 404 (first (call h "GET" ["api" "mosaics" "mosaic/none"] "ana"))))))

(deftest approval-route
  (let [{:keys [h log]} (setup false)
        t (first (filter #(= :tile/unpaid-invoices (:tile/id %)) dev/tiles))
        approve (fn [token spec] (call h "POST" ["api" "tiles" (subs (str (:tile/id spec)) 1) "approvals"] token {:tile/spec spec}))]
    (is (= [200 1] (let [[s r] (approve "ana" t)] [s (:tile/version r)])) "ana approves; her attributes are the test user")
    (is (= "user:ana" (get-in (log/projection log) [:tiles :tile/unpaid-invoices :owner])))
    (is (= [422 {:error :tile/not-owner}] (approve "bob" t)) "only the owner approves a new version, refused before any test runs")
    (is (= 401 (first (approve "mallory" t))))
    (is (= 422 (first (approve "ana" (assoc-in t [:tile/view :y] :biz/client-name)))))))

(deftest loregate-behind-the-same-origin
  (let [{:keys [h]} (setup true)
        [_ d] (call h "GET" ["api" "tiles" "tile/unpaid-invoices" "versions" "1"] "ana")
        run (fn [token] (call h "POST" ["loregate" "api" "v1" "plans" (:plan/id d) "run"] token
                              {:query (:tile/query d) :params {}}))
        [s-ana ana] (run "ana")
        [s-bob bob] (run "bob")]
    (is (= [200 200] [s-ana s-bob]))
    (is (= "Umbrella" (-> ana :rows first :biz/client-name)))
    (testing "Loregate's wire encoding: decimals and datetimes as strings"
      (is (= "7200.00" (-> ana :rows first :biz/unpaid-total)))
      (is (string? (-> ana :notes :clock :now))))
    (is (= #{} (set (-> ana :notes :hidden))))
    (is (every? nil? (map :biz/unpaid-total (:rows bob))))
    (is (= #{:biz/amount} (set (-> bob :notes :hidden))))
    (is (= 401 (first (run nil))))))

(deftest publishing-routes
  (let [{:keys [h log]} (setup true)
        pub ["api" "tiles" "tile/unpaid-invoices" "versions" "1" "publication"]]
    (is (= [422 {:error :tile/not-owner}] (call h "PUT" pub "bob")))
    (is (= [200 {:name "tile_unpaid_invoices_v1"}] (call h "PUT" pub "ana")))
    (is (= 200 (first (call h "DELETE" pub "ana"))))
    (is (= [422 {:error :tile/not-published}] (call h "DELETE" pub "ana")))
    (is (= 200 (first (call h "DELETE" ["api" "tiles" "tile/unpaid-invoices" "versions" "1"] "ana"))) "retire")
    (is (= :retired (get-in (log/projection log) [:tiles :tile/unpaid-invoices :versions 1 :lifecycle])))))
