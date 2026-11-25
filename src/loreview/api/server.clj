(ns loreview.api.server
  "Loreview's HTTP side (SPEC 11.1) on the JDK's HttpServer: the tile list and descriptors
  from the log's projection, and the web app's static files. Bodies are EDN.

  `handler` is a plain function from a request map to a response map, so routes are tested
  without a socket; `start!` puts it on a port.

  Approval (`POST /api/tiles/{id}/approvals`) needs `:lib` (an embedded Loregate over the
  same ontology) and `:identity` (token → Loregate user, for the test user); without them
  the route is absent.

  Development only: given `:dev-loregate` (Loregate's own HTTP handler, a function from
  request map to response map), the server passes /loregate/* to it — the place a reverse
  proxy puts the real Loregate, on the same origin (SPEC 6.1)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loreview.check.approval :as approval]
            [loreview.store.log :as log]
            [loreview.store.projections :as p])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(defn- edn-response [status body]
  {:status status :headers {"Content-Type" "application/edn; charset=utf-8"} :body (pr-str body)})

(defn descriptor
  "What the browser needs to draw a tile version (SPEC 11.1); nil if there is no such version."
  [proj id v]
  (when-let [x (get-in proj [:tiles id :versions v])]
    (merge (select-keys (:spec x) [:tile/id :tile/title :tile/query :tile/view :tile/events])
           {:tile/version v :tile/lineage (:lineage x)
            :plan/id (:plan/id x) :plan/output (:plan/output x)
            :health (:health x) :lifecycle (:lifecycle x) :published (:published x)})))

(defn tile-list [proj]
  (->> (:tiles proj)
       (keep (fn [[id t]]
               (when-let [v (p/current-version proj id)]
                 {:tile/id id :tile/version v
                  :tile/title (get-in t [:versions v :spec :tile/title])
                  :health (get-in t [:versions v :health])})))
       (sort-by :tile/title)
       vec))

(defn mosaic-list [proj]
  (->> (:mosaics proj)
       (map (fn [[id m]] {:mosaic/id id :mosaic/title (:title m)}))
       (sort-by :mosaic/title)
       vec))

(defn mosaic [proj id]
  (when-let [m (get-in proj [:mosaics id])]
    {:mosaic/id id :mosaic/title (:title m) :mosaic/tiles (:tiles m) :owner (:owner m)}))

(defn- save-mosaic!
  "Save a mosaic as the caller. The body is untrusted: only title and tiles are taken, and
  the log checks the event (schema, owner, live tile versions, event types)."
  [l token id body]
  (let [r (log/append! l (log/event :mosaic/saved (str "user:" token)
                                    (assoc (select-keys body [:mosaic/title :mosaic/tiles]) :mosaic/id id)))]
    (if (:error r)
      (edn-response 422 (select-keys r [:error]))
      (edn-response 200 (mosaic (log/projection l) id)))))

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8" "map" "application/json" "svg" "image/svg+xml"})

(defn- static-file [root segments]
  (let [base (.getCanonicalFile (io/file root))
        f (.getCanonicalFile (io/file base (str/join "/" (if (seq segments) segments ["index.html"]))))]
    (when (and (str/starts-with? (str f) (str base "/")) (.isFile f))
      {:status 200
       :headers {"Content-Type" (get content-types (peek (str/split (.getName f) #"\.")) "application/octet-stream")}
       :body f})))

(defn- owner-action
  "Run `f` (publish!, unpublish!, retire!) for tile `id` version `v` as the caller."
  [identity opts token f id v segment expected]
  (when (= segment expected)
    (let [user (when identity (identity token))
          version (parse-long (str v))]
      (cond
        (nil? identity) nil
        (nil? user) (edn-response 401 {:error :auth/invalid-token})
        (nil? version) nil
        :else (let [r (f {:log (:log opts) :gate (:gate opts) :token token
                          :author (str "user:" (:user/id user))}
                         (keyword id) version)]
                (edn-response (cond (nil? (:error r)) 200
                                    (= :auth/forbidden (:error r)) 403
                                    :else 422)
                              r))))))

(defn handler
  "A request {:method :segments :token :body} → a response {:status :headers :body}, or a 404.
  Segments are the path's parts, already URL-decoded (a tile id like tile/x arrives encoded
  as one part)."
  [{:keys [log dev-loregate public lib identity clock] :as opts}]
  (fn [{:keys [method segments token] :as req}]
    (or
     (when (= "api" (first segments))
       ;; ponytail: any bearer token is accepted; the identity check comes with the real
       ;; Loregate's OIDC setup (Loregate SPEC 8.1), validating the same token
       (if (str/blank? token)
         (edn-response 401 {:error :auth/missing-token})
         (let [proj (log/projection log)
               [_ what id _ v] segments]
           (case [method what (count segments)]
             ["GET" "tiles" 2] (edn-response 200 (tile-list proj))
             ["GET" "tiles" 5] (when-let [d (descriptor proj (keyword id) (parse-long (str v)))]
                                 (edn-response 200 d))
             ["GET" "mosaics" 2] (edn-response 200 (mosaic-list proj))
             ["GET" "mosaics" 3] (some->> (mosaic proj (keyword id)) (edn-response 200))
             ["POST" "mosaics" 3] (save-mosaic! log token (keyword id)
                                                (try (edn/read-string (:body req)) (catch Exception _ nil)))
             ;; publishing and retiring: the caller acts as the author; the log and Loregate decide
             ["PUT" "tiles" 6] (owner-action identity opts token approval/publish! id v (nth segments 5) "publication")
             ["DELETE" "tiles" 6] (owner-action identity opts token approval/unpublish! id v (nth segments 5) "publication")
             ["DELETE" "tiles" 5] (owner-action identity opts token approval/retire! id v (nth segments 3) "versions")
             ["POST" "tiles" 4] (when (and lib identity (= "approvals" (nth segments 3)))
                                  (let [spec (:tile/spec (try (edn/read-string (:body req)) (catch Exception _ nil)))
                                        user (identity token)]
                                    (cond
                                      (nil? user) (edn-response 401 {:error :auth/invalid-token})
                                      (not= (keyword id) (:tile/id spec)) (edn-response 422 {:error :approval/id-mismatch})
                                      :else
                                      (let [r (approval/approve! {:log log :gate (:gate opts) :lib lib :token token
                                                                  :author (str "user:" (:user/id user))
                                                                  :test-user user
                                                                  :clock {:now (if clock (clock) (java.util.Date.)) :zone "UTC"}}
                                                                 spec)]
                                        (edn-response (if (:error r) 422 200) r)))))
             nil))))
     (when (and dev-loregate (= "loregate" (first segments)))
       ;; the decoded path, as Loregate's own server gives it (a plan id is URL-encoded)
       (let [r (dev-loregate {:method method :path (subs (:path req) (count "/loregate"))
                              :query-string (:query-string req) :headers (:headers req) :body (:body req)})]
         (update r :headers #(into {} (map (fn [[k v]] [(str/capitalize k) v])) %))))
     (when (and public (= "GET" method) (not= "api" (first segments)))
       (static-file public segments))
     (edn-response 404 {:error :not-found}))))

(defn- exchange->request [^HttpExchange ex]
  (let [raw (.getRawPath (.getRequestURI ex))
        auth (.getFirst (.getRequestHeaders ex) "Authorization")]
    {:method (.getRequestMethod ex)
     :path (.getPath (.getRequestURI ex))
     :query-string (.getRawQuery (.getRequestURI ex))
     :headers (into {} (for [[k vs] (.getRequestHeaders ex)] [(str/lower-case k) (first vs)]))
     :segments (->> (str/split raw #"/") (remove str/blank?)
                    (mapv #(URLDecoder/decode ^String % StandardCharsets/UTF_8)))
     :token (when (and auth (str/starts-with? auth "Bearer ")) (subs auth 7))
     :body (slurp (.getRequestBody ex) :encoding "UTF-8")}))

(defn- respond! [^HttpExchange ex {:keys [status headers body]}]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (let [bytes (if (instance? java.io.File body)
                (java.nio.file.Files/readAllBytes (.toPath ^java.io.File body))
                (.getBytes (str body) StandardCharsets/UTF_8))]
    (.sendResponseHeaders ex status (alength ^bytes bytes))
    (with-open [o (.getResponseBody ex)] (.write o ^bytes bytes))))

(defn start!
  "Serve `opts` (see `handler`) on `port`; returns the server (.stop it to end)."
  [port opts]
  (let [h (handler opts)
        server (HttpServer/create (InetSocketAddress. port) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (try (respond! ex (h (exchange->request ex)))
                             (catch Throwable t
                               (respond! ex (edn-response 500 {:error :server/error}))
                               (.printStackTrace t))))))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    server))
