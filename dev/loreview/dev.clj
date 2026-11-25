(ns loreview.dev
  "Development server on the real Loregate, embedded as a library over its own fixture
  ontology, users and Parquet data (../loregate/dev/fixtures). A fresh log is seeded by
  approving the fixture tiles as ana — compiled, tested and approved for real — plus a
  mosaic. Approved plans are rechecked every minute (SPEC 10.3). The browser reaches
  Loregate's own HTTP handler under /loregate, as it would through a proxy.

    clojure -M:dev [port]      then open http://localhost:<port> (default 8292)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [loregate.api.http :as lhttp]
            [loregate.auth.port :as auth]
            [loregate.core :as lg]
            [loregate.ontology.memory :as mem]
            [loregate.sources.parquet :as pq]
            [loregate.store.memory :as mstore]
            [loreview.api.server :as server]
            [loreview.check.approval :as approval]
            [loreview.gate.library :as library]
            [loreview.store.log :as log :refer [event]]))

(def lg-fixtures "../loregate/dev/fixtures/")

(defn loregate-system
  "Loregate over its fixture ontology and data. Binding paths are relative to Loregate's
  folder, so they are rewritten to reach it from here."
  []
  (lg/system {:ontology (mem/create (walk/postwalk #(if (and (string? %) (str/starts-with? % "dev/fixtures/"))
                                                      (str "../loregate/" %) %)
                                                   (edn/read-string (slurp (str lg-fixtures "ontology.edn")))))
              :sources {:parquet (pq/adapter [lg-fixtures])}
              :store (mstore/store)
              ;; ponytail: finance publishes in development; a deployment sets its own role
              :config {:publish/role "role:finance"}}))

(defn loregate-users
  "Loregate's fixture users by id. ponytail: the token IS the user id in development; a
  real deployment resolves the token through Loregate's identity adapter (OIDC)."
  []
  (into {} (map (juxt :user/id identity)) (edn/read-string (slurp (str lg-fixtures "users.edn")))))

(def tiles (edn/read-string (slurp "dev/fixtures/tiles.edn")))
(defn dev-identity
  "Loregate's Identity port for development: the bearer token is a fixture user's id.
  ponytail: no signature, no expiry — a real deployment uses loregate.auth.oidc."
  [users]
  (reify auth/Identity
    (authenticate [_ req]
      (let [h (get-in req [:headers "authorization"] "")
            token (when (str/starts-with? h "Bearer ") (subs h 7))]
        (cond (str/blank? token) {:error :auth/missing-token}
              (users token) (users token)
              :else {:error :auth/invalid-token})))))

(defn seed-mosaic!
  "A receivables page: the clients chart beside the invoices of the client selected in it."
  [l author]
  (let [r (log/append! l (event :mosaic/saved author
                                {:mosaic/id :mosaic/receivables :mosaic/title "Receivables"
                                 :mosaic/tiles [{:tile/id :tile/unpaid-invoices :tile/version 1 :x 0 :y 0 :w 7 :h 1}
                                                {:tile/id :tile/client-invoices :tile/version 1 :x 7 :y 0 :w 5 :h 1}]}))]
    (when (:error r) (throw (ex-info "seed refused" r)))))

(defn -main [& [port]]
  (let [port (or (some-> port parse-long) 8292)
        path "out/dev-log.edn"
        sys (loregate-system)
        users (loregate-users)
        g (library/gate sys users)]
    (io/delete-file path true)
    (let [l (log/open-file-log path)
          ctx {:log l :gate g :lib sys :token "ana" :author "user:ana" :test-user (users "ana")
               :clock {:now (java.util.Date.) :zone "UTC"}}]
      (doseq [t tiles]
        (let [r (approval/approve! ctx t)]
          (println (str "approve " (:tile/id t) ": ") (or (:error r) (str "v" (:tile/version r))))))
      (seed-mosaic! l "user:ana")
      (server/start! port {:log l :gate g :lib sys :identity users :public "public"
                           :dev-loregate (lhttp/handler sys (dev-identity users))})
      (future (loop [] (Thread/sleep 60000)
                (try (doseq [r (approval/refresh! ctx)] (println "refresh" r))
                     (catch Exception e (println "refresh failed" (ex-message e))))
                (recur)))
      (println (str "Loreview dev: http://localhost:" port "  (users: " (str/join ", " (sort (keys users))) ")")))))
