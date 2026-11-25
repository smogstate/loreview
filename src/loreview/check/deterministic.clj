(ns loreview.check.deterministic
  "Deterministic tests of a plan (SPEC 9.1), run through the embedded Loregate library over
  rows Loreview supplies — no live data, no user's data rights, a fixed clock. The plan
  tested is the plan that runs; only its inputs and clock are supplied. Loregate's rules
  apply for the test user, as they would live.

  Rows are generated per input (`:plan/inputs`: link path, ontology type, attributes read)
  from the attributes' declared types, seeded, so a case is the same every time:
  - a type's key gets unique values k0, k1, …; an attribute a link joins on is drawn from
    those values only, so every link connects;
  - other text values come, weighted, from the query's own text parameter values
    (defaults, options), the test user's text values, and the key values — so a filter on
    a parameter matches some rows and a rule that compares a row with the user lets some
    rows through (a heuristic with a ceiling: a rule
    the pool cannot satisfy leaves only the shape checks — SPEC 14.8);
  - enums from their declared values, sets from the same pool plus the user's set values,
    numbers small, dates around the clock's day."
  (:require [loregate.core :as lg]
            [loregate.ontology.port :as onto]
            [loreview.check.view :as view])
  (:import (java.time LocalDate ZoneId)
           (java.util Random)))

(defn- user-values [user]
  (let [vs (vals (:user/attrs user))]
    {:strings (filter string? vs)
     :set-members (mapcat #(when (set? %) (filter string? %)) vs)}))

(defn- today [{:keys [now zone]}]
  (LocalDate/ofInstant (.toInstant ^java.util.Date now) (ZoneId/of zone)))

(defn- pick [^Random r xs] (when (seq xs) (nth xs (.nextInt r (count xs)))))

(defn- text [^Random r {:keys [params user pool]}]
  (let [u (.nextDouble r)]
    (cond (and (seq params) (< u 0.4)) (pick r params)
          (and (seq user) (< u 0.7)) (pick r user)
          :else (pick r pool))))

(defn- value [^Random r {:keys [type values]} {:keys [set-pool day now] :as ctx}]
  (case type
    :string (text r ctx)
    :enum (pick r (vec (sort values)))
    :set (into #{} (repeatedly (.nextInt r 3) #(pick r set-pool)))
    :int (- (.nextInt r 200) 20)
    :decimal (BigDecimal/valueOf (long (.nextInt r 1000000)) 2)
    :boolean (.nextBoolean r)
    :date (.minusDays ^LocalDate day (- (.nextInt r 400) 30))
    :datetime (java.util.Date. (- (.getTime ^java.util.Date now) (* 1000 (.nextInt r 30000000))))
    nil))

(defn param-strings
  "Text values a query's parameters may take: their defaults and options."
  [params]
  (filter string? (mapcat (fn [d] (cons (:default d) (:options d))) (vals params))))

(defn relations
  "Supplied rows for every input of a plan: {path [row …]}. `n` rows per input; with
  `nulls?`, every non-key value is empty. `strings`: more text values for the pool."
  [sys inputs user clock {:keys [n seed nulls? strings]}]
  (let [r (Random. (long (or seed 0)))
        keys-pool (mapv #(str "k" %) (range (max n 1)))
        {user-strings :strings :keys [set-members]} (user-values user)
        decls (into {} (for [t (distinct (map :type inputs))] [t (onto/type-of (:ontology sys) t)]))
        ;; attributes a link joins on, either side: drawn from the key values only
        join-attrs (set (for [d (vals decls) l (:type/links d) pair (:link/on l) a pair] a))
        ctx {:params (vec (distinct strings)) :user (vec user-strings) :pool keys-pool
             :set-pool (vec (concat keys-pool user-strings set-members))
             :day (today clock) :now (:now clock)}]
    (into {}
          (for [{:keys [path type attributes]} inputs
                :let [decl (decls type)
                      types (:type/attributes decl)
                      key-attrs (set (:type/key decl))]]
            [path (vec (for [i (range n)]
                         (into {} (for [a (sort attributes)]
                                    [a (cond (key-attrs a) (str "k" i)
                                             nulls? nil
                                             (join-attrs a) (pick r keys-pool)
                                             :else (value r (types a) ctx))]))))]))))

(defn- defaults [params]
  (into {} (for [[k d] params :when (contains? d :default)] [k (:default d)])))

(defn cases
  "The cases run for a plan: a base case (kept as the golden case), empty input, empty
  values, each parameter at its declared bounds or options, and a larger input."
  [{:plan/keys [params]}]
  (let [base (defaults params)]
    (concat
     [{:case :base :n 20 :seed 1 :params base}
      {:case :empty :n 0 :params base}
      {:case :nulls :n 5 :seed 2 :nulls? true :params base}
      {:case :large :n 2000 :seed 3 :params base}]
     (for [[k d] (sort-by key params)
           [bound v] (concat (keep (fn [b] (when (contains? d b) [b (get d b)])) [:min :max])
                             (when-let [os (seq (:options d))] [[:first-option (first os)] [:last-option (last os)]]))]
       {:case [:param k bound] :n 20 :seed 1 :params (assoc base k v)}))))

(def ^:private type-ok?
  {:string string? :int integer? :decimal #(or (decimal? %) (integer? %)) :boolean boolean?
   :date #(instance? LocalDate %) :datetime inst? :enum keyword? :set set?})

(defn- shape-problems [output rows]
  (let [cols (into {} (map (juxt :name identity)) output)]
    (for [row rows
          [k v] row
          :let [{:keys [type]} (cols k)]
          :when (or (nil? type) (and (some? v) (not ((type-ok? type any?) v))))]
      {:problem (if type :output/wrong-type :output/unknown-column) :column k :value v})))

(defn- run-case [sys plan-id user clock rels params]
  (lg/run sys {:plan-id plan-id :user user :clock clock :relations rels :params params}))

(defn run-all
  "Run every case for `plan` (a compile summary: :plan/id :plan/output :plan/params
  :plan/inputs) as `user` at `clock`. → {:problems […] :golden {…}}; empty problems = pass."
  [sys tile {:plan/keys [id output inputs] :as plan} user clock]
  (let [results (for [{:keys [case params] :as c} (cases plan)
                      :let [rels (relations sys inputs user clock (assoc c :strings (param-strings (:plan/params plan))))
                            res (run-case sys id user clock rels params)]]
                  {:case case :rels rels :params params :res res})
        problems (vec (for [{:keys [case res]} results
                            p (cond
                                (:error res) [{:problem :run/error :error (:error res)
                                               :reason (:reason res)}]
                                :else (concat (shape-problems output (:rows res))
                                              (when (#{:base :large} case) (view/row-problems tile (:rows res)))))]
                        (assoc p :case case)))
        base (first results)]
    {:problems problems
     :golden (when-not (:error (:res base))
               {:relations (:rels base) :clock clock :user user :params (:params base)
                :rows (vec (:rows (:res base)))})}))

(defn reproduce
  "Rerun a golden case against `plan-id`. → {:same? bool :rows […]} or the run's error."
  [sys plan-id {:keys [relations clock user params rows]}]
  (let [res (run-case sys plan-id user clock relations params)]
    (if (:error res)
      res
      {:same? (= rows (vec (:rows res))) :rows (vec (:rows res))})))
