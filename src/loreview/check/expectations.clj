(ns loreview.check.expectations
  "Evaluate expectation checks (SPEC 9.2) against a result's rows. Pure. Empty values are
  skipped by sums and fail :no-nulls; a check about a column the viewer cannot see (all
  empty) therefore fails rather than passing vacuously.")

(defn- values [rows col] (keep #(get % col) rows))

(defn passed?
  [[op & args] rows]
  (case op
    :top-n-contains (let [[n col v] args] (boolean (some #(= v (get % col)) (take n rows))))
    :sum-between (let [[col lo hi] args
                       vs (values rows col)]
                   (and (seq vs) (<= lo (reduce + vs) hi)))
    :row-count-between (let [[lo hi] args] (<= lo (count rows) hi))
    :not-empty (boolean (seq rows))
    :unique-key (let [[cols] args] (or (empty? rows) (apply distinct? (map #(mapv % cols) rows))))
    :no-nulls (let [[col] args] (every? #(some? (get % col)) rows))))

(defn evaluate
  "Each expectation with :passed? added."
  [expectations rows]
  (mapv #(assoc % :passed? (boolean (passed? (:check %) rows))) expectations))
