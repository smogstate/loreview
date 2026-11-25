(ns loreview.test-runner
  "Runs every *-test namespace under test/. Exit code 1 on any failure or error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test"))
       (map str)
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> % (subs 5 (- (count %) 4)) (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

(defn -main [& _]
  (let [nss (test-namespaces)]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
