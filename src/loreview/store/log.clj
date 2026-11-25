(ns loreview.store.log
  "The append-only event log (SPEC 10.1) behind a port. The file adapter keeps one EDN
  event per line; it is enough for v1. An append is validated (schema, then the
  projection's guard), written, and only then folded into the in-memory projection: a
  failed write changes nothing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loreview.schema.log :as schema]
            [loreview.store.projections :as proj]
            [malli.core :as m]
            [malli.error :as me]))

;; Golden cases hold the JVM values Loregate's library returns; a date must survive the log.
(defmethod print-method java.time.LocalDate [d ^java.io.Writer w]
  (.write w (str "#time/date \"" d "\"")))

(def ^:private readers {'time/date #(java.time.LocalDate/parse %)})

(defprotocol Log
  (append! [log event] "→ {:event event} or {:error kw …}; nothing is written on error.")
  (events [log] "Every event, oldest first.")
  (projection [log] "The current projection."))

(defn event
  "A new event of `type` by `actor`: `payload` with the envelope stamped on."
  [type actor payload]
  (merge payload {:event/type type :event/id (random-uuid) :event/at (java.util.Date.)
                  :event/actor actor}))

(defn check
  "nil if `ev` may follow `projection`, else {:error kw …}."
  [projection ev]
  (if-let [e (m/explain schema/Event ev)]
    {:error :log/invalid-event :explain (me/humanize e)}
    (when-let [p (proj/problem projection ev)] {:error p})))

(defrecord FileLog [file state]                     ; state: atom {:events [] :projection {}}
  Log
  (append! [this ev]
    (locking this
      (or (check (:projection @state) ev)
          (do (spit file (str (pr-str ev) "\n") :append true)
              (swap! state #(-> % (update :events conj ev) (update :projection proj/apply-event ev)))
              {:event ev}))))
  (events [_] (:events @state))
  (projection [_] (:projection @state)))

(defn open-file-log
  "The log stored at `path` (created on first append). A line that does not validate, or
  that its predecessors refuse, stops the open: a log is never silently repaired."
  [path]
  (let [f (io/file path)
        evs (if (.exists f)
              (->> (str/split-lines (slurp f)) (remove str/blank?) (mapv #(edn/read-string {:readers readers} %)))
              [])
        projection (reduce (fn [p [i ev]]
                             (if-let [e (check p ev)]
                               (throw (ex-info (str "log line " (inc i) " is refused") (assoc e :path path)))
                               (proj/apply-event p ev)))
                           proj/empty-projection (map-indexed vector evs))]
    (some-> (.getParentFile f) .mkdirs)
    (->FileLog f (atom {:events evs :projection projection}))))
