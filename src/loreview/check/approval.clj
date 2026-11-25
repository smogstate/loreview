(ns loreview.check.approval
  "Approving a tile (SPEC 8) and keeping approved tiles healthy (SPEC 10.3).

  `ctx`: :log, :gate (the Loregate the app runs against), :lib (an embedded Loregate system
  over the same ontology, for deterministic tests), :token (the acting user's), and for
  approval :author, :test-user ({:user/id :user/attrs}) and :clock. The tested plan must be
  the plan that runs: if the embedded library compiles a different plan id than the gate,
  nothing is approved."
  (:require [clojure.string :as str]
            [loregate.core :as lg]
            [loreview.check.deterministic :as det]
            [loreview.check.expectations :as ex]
            [loreview.check.view :as view]
            [loreview.gate.port :as gate]
            [loreview.schema.tile :as tile]
            [loreview.store.log :as log]))

(def system-actor "system:loreview")

(defn- append! [l ev]
  (let [r (log/append! l ev)]
    (when (:error r) (throw (ex-info "log refused an event" (assoc r :event (:event/type ev)))))
    r))

(defn- compile-both
  "The gate's plan and the library's plan of one query; an error if they differ."
  [{:keys [gate lib token]} query]
  (let [c (gate/compile-query gate token query)
        lc (lg/compile lib query)]
    (cond
      (:error c) {:error :approval/does-not-compile :details c}
      (:errors lc) {:error :approval/does-not-compile :details lc}
      (not= (:plan/id c) (:plan/id lc)) {:error :approval/plan-mismatch
                                        :gate (:plan/id c) :library (:plan/id lc)}
      :else lc)))

(defn approve!
  "Validate, compile, test and approve `t` as the next version. → {:tile/version :plan/id}
  or {:error … details}; on error nothing but a :tile/tested record (when tests ran) is
  written."
  [{:keys [log gate lib token author test-user clock] :as ctx} t]
  (let [id (:tile/id t)
        query (:tile/query t)]
    (cond
      (when-let [o (get-in (log/projection log) [:tiles id :owner])] (not= o author))
      {:error :tile/not-owner}

      (tile/explain t)
      {:error :approval/invalid-tile :details (tile/explain t)}

      :else
      (let [plan (compile-both ctx query)]
        (if (:error plan)
          plan
          (let [vps (view/problems t (:plan/output plan))
                {:keys [problems golden]} (when (empty? vps) (det/run-all lib t plan test-user clock))
                live (when (and (empty? vps) (empty? problems))
                       (gate/run-plan gate token (:plan/id plan) query {}))
                results (when (:rows live) (ex/evaluate (:tile/expectations t []) (:rows live)))
                proj (log/projection log)]
            (when-not (get-in proj [:tiles id])
              (append! log (log/event :tile/drafted author {:tile/id id :prompt (:tile/title t)})))
            (when (or (seq problems) results)
              (append! log (log/event :tile/tested author {:tile/id id :plan/id (:plan/id plan)
                                                          :results {:deterministic problems :expectations results}})))
            (cond
              (seq vps) {:error :approval/view-mismatch :problems vps}
              (seq problems) {:error :approval/tests-failed :problems problems}
              (:error live) {:error :approval/run-failed :details live}
              (not-every? :passed? results) {:error :approval/expectations-failed :results results}
              :else
              (let [v (inc (count (get-in (log/projection log) [:tiles id :versions])))
                    status (gate/plan-status gate token (:plan/id plan))]
                (append! log (log/event :tile/approved author
                                        {:tile/id id :tile/version v :tile/spec t
                                         :plan/id (:plan/id plan) :plan/output (:plan/output plan)
                                         :plan/pins (:plan/pins status)
                                         :tile/lineage ""      ; from describe, phase 4
                                         :test/user (:user/attrs test-user)
                                         :test/golden golden}))
                {:tile/version v :plan/id (:plan/id plan)}))))))))

(defn published-name
  "The name a tile version is published under (SPEC 12): tile_unpaid_invoices_v3."
  [id v]
  (str "tile_" (str/replace (name id) #"[^A-Za-z0-9]" "_") "_v" v))

(defn- owned-live
  "The version if `author` owns the tile and the version is live, else an error."
  [l author id v]
  (let [t (get-in (log/projection l) [:tiles id])
        x (get-in t [:versions v])]
    (cond (nil? x) {:error :tile/unknown-version}
          (not= author (:owner t)) {:error :tile/not-owner}
          (not= :approved (:lifecycle x)) {:error :tile/retired}
          :else x)))

(defn publish!
  "Publish an approved version through Loregate (SPEC 12): its plan gets a stable name any
  caller can run, under that caller's own rules. → {:name} or {:error …}."
  [{:keys [log gate token author]} id v]
  (let [x (owned-live log author id v)]
    (if (:error x)
      x
      (let [n (published-name id v)
            r (gate/publish! gate token (:plan/id x) n
                             (or (not-empty (:lineage x)) (get-in x [:spec :tile/title])))]
        (if (:error r)
          r
          (do (append! log (log/event :tile/published author {:tile/id id :tile/version v :name n}))
              {:name n}))))))

(defn unpublish!
  [{:keys [log gate token author]} id v]
  (let [x (owned-live log author id v)]
    (cond
      (:error x) x
      (nil? (:published x)) {:error :tile/not-published}
      :else (let [r (gate/unpublish! gate token (:plan/id x))]
              (if (:error r)
                r
                (do (append! log (log/event :tile/unpublished author {:tile/id id :tile/version v}))
                    {:tile/version v}))))))

(defn retire!
  "Retire a version; a published one loses its name in Loregate first."
  [{:keys [log author] :as ctx} id v]
  (let [x (owned-live log author id v)
        un (when (and (not (:error x)) (:published x)) (unpublish! ctx id v))]
    (cond
      (:error x) x
      (:error un) un
      :else (do (append! log (log/event :tile/retired author {:tile/id id :tile/version v}))
                {:tile/version v}))))

(defn- live-versions [proj]
  (for [[id t] (:tiles proj) [v x] (:versions t) :when (= :approved (:lifecycle x))]
    [id v x]))

(defn- recheck
  "What becomes of one approved version whose plan is stale (or no longer held)."
  [ctx {:keys [spec golden] :as x} changed]
  (let [plan (compile-both ctx (:tile/query spec))
        rules-only? (and (seq changed) (every? vector? changed))]
    (cond
      (:error plan) {:health :invalid :reason (str "does not compile: " (name (:error plan)))}
      (not= (:plan/output plan) (:plan/output x)) {:health :invalid :reason "its output changed"}
      :else
      (let [{:keys [problems] :as fresh} (det/run-all (:lib ctx) spec plan (:user golden) (:clock golden))
            again (when (and golden (not rules-only?)) (det/reproduce (:lib ctx) (:plan/id plan) golden))]
        (cond
          (nil? golden) {:health :invalid :reason "no golden case to compare with"}
          (seq problems) {:health :invalid :reason "its deterministic tests fail"}
          (:error again) {:health :invalid :reason (str "its golden case fails: " (name (:error again)))}
          (and again (not (:same? again))) {:health :invalid :reason "its golden case gives other rows"}
          ;; a rule changed what the test user sees: not a defect of the tile, so the golden
          ;; case is taken again (SPEC 10.3)
          :else {:replace plan :golden (if rules-only? (:golden fresh) golden)})))))

(defn refresh!
  "Check every approved version's plan with Loregate; replace stale plans that still pass,
  mark the others invalid. → [{:tile/id :tile/version :outcome …}]."
  [{:keys [log gate token] :as ctx}]
  (vec
   (for [[id v x] (live-versions (log/projection log))
         :let [st (gate/plan-status gate token (:plan/id x))
               changed (cond (= :plan/not-found (:error st)) [:plan/not-found]
                             (:error st) nil
                             :else (:changed st))]
         :when (seq changed)
         :let [{:keys [health reason replace golden]} (recheck ctx x changed)]]
     (if replace
       (let [status (gate/plan-status gate token (:plan/id replace))]
         (when (not= (:plan/id replace) (:plan/id x))
           (append! log (log/event :tile/plan-replaced system-actor
                                   {:tile/id id :tile/version v :plan/id (:plan/id replace)
                                    :plan/output (:plan/output replace) :plan/pins (:plan/pins status)
                                    :cause (vec changed) :test/golden golden})))
         (when (not= :valid (:health x))
           (append! log (log/event :tile/health-changed system-actor
                                   {:tile/id id :tile/version v :health :valid :reason "rechecked"})))
         {:tile/id id :tile/version v :outcome :replaced :changed changed})
       (do (when (not= health (:health x))
             (append! log (log/event :tile/health-changed system-actor
                                     {:tile/id id :tile/version v :health health :reason reason})))
           {:tile/id id :tile/version v :outcome health :reason reason :changed changed})))))
