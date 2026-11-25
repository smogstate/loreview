(ns loreview.gate.client
  "The gate over Loregate's HTTP API (Loregate SPEC 10), for a backend that reaches Loregate
  as a service. EDN both ways. Answers come back as the embedded library gives them: wire
  strings are decoded by each column's type (decimals → BigDecimal, dates → LocalDate,
  datetimes → Date), so approval and expectations behave the same either way."
  (:require [clojure.edn :as edn]
            [loreview.gate.port :as port])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration Instant LocalDate)
           (java.util Date)))

(defn- decoder [type]
  (case type
    :decimal #(if (string? %) (bigdec %) %)
    :date #(if (string? %) (LocalDate/parse %) %)
    :datetime #(if (string? %) (Date/from (Instant/parse %)) %)
    identity))

(defn- inst-of [s] (if (string? s) (Date/from (Instant/parse s)) s))

(defn decode-result
  "A run result from the wire back into JVM values, by its :columns."
  [{:keys [columns] :as res}]
  (if-not (:rows res)
    res
    (let [decoders (into {} (map (juxt :name (comp decoder :type))) columns)]
      (-> res
          (update :rows (fn [rows] (mapv (fn [row] (into {} (map (fn [[k v]] [k ((decoders k identity) v)])) row)) rows)))
          (update-in [:notes :as-of] #(update-vals (or % {}) inst-of))
          (update-in [:notes :clock :now] inst-of)))))

(defn- compiled [res]
  (if-let [es (:errors res)] {:error (:error (first es)) :errors es} res))

(defn- send! [^HttpClient http base timeout token method path body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str base path)))
                (.timeout (Duration/ofMillis timeout))
                (.header "Accept" "application/edn")
                (.header "Content-Type" "application/edn")
                (cond-> token (.header "Authorization" (str "Bearer " token)))
                (.method method (if body
                                  (HttpRequest$BodyPublishers/ofString (pr-str body))
                                  (HttpRequest$BodyPublishers/noBody)))
                .build)]
    (try
      (compiled (edn/read-string (.body (.send http req (HttpResponse$BodyHandlers/ofString)))))
      (catch java.io.IOException _ {:error :source/unavailable :reason :loregate-unreachable}))))

(defn- plan-path [plan-id & more] (apply str "/api/v1/plans/" (URLEncoder/encode ^String plan-id "UTF-8") more))

(defrecord HttpGate [http base timeout]
  port/Gate
  (compile-query [_ token query]
    (send! http base timeout token "POST" "/api/v1/plans" query))
  (run-plan [_ token plan-id query params]
    (decode-result (send! http base timeout token "POST" (plan-path plan-id "/run") {:query query :params params})))
  (run-query [_ token query params]
    (decode-result (send! http base timeout token "POST" "/api/v1/query" {:query query :params params})))
  (publish! [_ token plan-id name description]
    (send! http base timeout token "PUT" (plan-path plan-id "/name") {:name name :description description}))
  (unpublish! [_ token plan-id]
    (send! http base timeout token "DELETE" (plan-path plan-id "/name") nil))
  (plan-status [_ token plan-id]
    (send! http base timeout token "GET" (plan-path plan-id) nil)))

(defn gate
  "A gate to the Loregate at `base` (e.g. \"http://localhost:8080\")."
  ([base] (gate base {}))
  ([base {:keys [timeout] :or {timeout 30000}}]
   (->HttpGate (HttpClient/newHttpClient) base timeout)))
