(ns loreview.expected-test
  "The fixture tiles' answers, per user, against the real Loregate: rows (in order, except
  where every sort key is hidden) and the notes that explain them."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [loregate.core :as lg]
            [loreview.dev :as dev]))

(def expected (edn/read-string (slurp "dev/fixtures/expected.edn")))
(def clock {:now #inst "2026-11-24T12:00:00Z" :zone "UTC"})

(defn- plain [row] (update-vals row #(if (instance? java.time.LocalDate %) (str %) %)))

(deftest loregate-answers-as-expected
  (let [sys (dev/loregate-system)
        users (dev/loregate-users)
        tiles (into {} (map (juxt :tile/id identity)) dev/tiles)]
    (doseq [{:keys [tile output filter] :as e} expected
            :let [plan (lg/compile sys (get-in tiles [tile :tile/query]))]
            [uid {:keys [rows notes]}] (:users e)
            v (if filter (distinct (keep (:column filter) rows)) [nil])
            :let [res (lg/run sys {:plan-id (:plan/id plan) :user (users uid) :clock clock
                                   :params (if filter {(:param filter) v} {})})
                  want (vec (cond->> rows filter (clojure.core/filter #(= v (get % (:column filter))))))
                  got (mapv plain (:rows res))]]
      (testing (str tile " " uid " " v)
        (is (= output (:plan/output plan)))
        (if (:hidden notes)
          (is (= (set want) (set got)) "every sort key hidden: same rows, any order")
          (is (= want got)))
        (is (= (merge {:hidden #{} :partial #{} :rows-filtered #{}} (select-keys notes [:hidden :partial :rows-filtered]))
               (select-keys (:notes res) [:hidden :partial :rows-filtered])))))))
