(ns loreview.store.projections
  "What the log means, as one pure fold (SPEC 10.1): tiles with their versions, lifecycle
  and health; mosaics; and, derived on demand, the catalogue. `problem` is the guard: an
  event that makes no sense against the current projection is refused before it is
  appended, so replaying a log never meets one.")

(def empty-projection {:tiles {} :mosaics {}})

;; events only the tile's owner may write; the rest are the system's or anyone's audit
(def ^:private owner-only
  #{:tile/spec-proposed :tile/approved :tile/published :tile/unpublished :tile/retired})

;; events addressed to one approved version
(def ^:private versioned
  #{:tile/plan-replaced :tile/health-changed :tile/expectation-failed :tile/published
    :tile/unpublished :tile/retired :tile/repair-proposed})

(defn- live-version? [proj id v]
  (= :approved (get-in proj [:tiles id :versions v :lifecycle])))

(defn- event-types
  "For the tile versions placed in a mosaic: event → the set of value types it would carry,
  from emitters (the column's type in the plan's output) and listeners (the param's type)."
  [proj placed]
  (apply merge-with into {}
         (for [{:tile/keys [id version]} placed
               :let [x (get-in proj [:tiles id :versions version])
                     out (into {} (map (juxt :name :type)) (:plan/output x))
                     params (get-in x [:spec :tile/query :params])
                     evs (get-in x [:spec :tile/events])]
               [k t] (concat (for [{:keys [event column]} (:emits evs)] [event (out column)])
                             (for [{:keys [event param]} (:listens evs)] [event (get-in params [param :type])]))]
           {k #{t}})))

(defn problem
  "nil when `ev` may be appended to a log whose projection is `proj`, else a keyword."
  [proj {:event/keys [type actor] :as ev}]
  (let [id (:tile/id ev)
        t (get-in proj [:tiles id])
        v (:tile/version ev)]
    (cond
      (= :mosaic/saved type)
      (let [m (get-in proj [:mosaics (:mosaic/id ev)])]
        (cond
          (and m (not= actor (:owner m))) :mosaic/not-owner
          (not-every? #(live-version? proj (:tile/id %) (:tile/version %)) (:mosaic/tiles ev))
          :mosaic/unknown-tile
          (not (apply distinct? nil (map (juxt :tile/id :tile/version) (:mosaic/tiles ev))))
          :mosaic/duplicate-tile
          ;; an event must carry one type: what emitters send is what listeners read. A
          ;; listener with no emitter is fine — its param keeps its value.
          (some #(> (count %) 1) (vals (event-types proj (:mosaic/tiles ev))))
          :mosaic/event-type-mismatch))

      (= :concept/proposed type) nil
      (= :tile/drafted type) (when t :tile/exists)
      (nil? t) :tile/unknown
      (and (owner-only type) (not= actor (:owner t))) :tile/not-owner
      (and (#{:tile/spec-proposed :tile/approved :tile/repair-proposed} type)
           (not= id (get-in ev [:tile/spec :tile/id]))) :tile/id-mismatch
      (and (= :tile/approved type) (not= v (inc (count (:versions t))))) :tile/version-out-of-order
      (and (or (versioned type) (and (= :tile/tested type) v))
           (not (get-in t [:versions v]))) :tile/unknown-version
      (and (versioned type) (not (live-version? proj id v))) :tile/retired)))

(defn apply-event
  "The projection after `ev`. Assumes `(problem proj ev)` is nil."
  [proj {:event/keys [type actor at] :as ev}]
  (let [id (:tile/id ev)
        v (:tile/version ev)
        at-v (fn [f & args] (apply update-in proj [:tiles id :versions v] f args))]
    (case type
      :tile/drafted (assoc-in proj [:tiles id] {:owner actor :drafted-at at :draft nil :versions {}})
      :tile/spec-proposed (assoc-in proj [:tiles id :draft] (:tile/spec ev))
      :tile/approved (-> proj
                         (assoc-in [:tiles id :draft] nil)
                         (assoc-in [:tiles id :versions v]
                                   {:spec (:tile/spec ev) :plan/id (:plan/id ev)
                                    :plan/output (:plan/output ev) :plan/pins (:plan/pins ev)
                                    :lineage (:tile/lineage ev) :test-user (:test/user ev)
                                    :golden (:test/golden ev)
                                    :lifecycle :approved :health :valid :published nil
                                    :approved-at at}))
      :tile/plan-replaced (at-v merge (cond-> (assoc (select-keys ev [:plan/id :plan/output :plan/pins])
                                                    :health :valid)
                                        (:test/golden ev) (assoc :golden (:test/golden ev))))
      :tile/health-changed (at-v assoc :health (:health ev) :health-reason (:reason ev))
      :tile/expectation-failed (at-v update :failed-checks (fnil conj []) (:check ev))
      :tile/published (at-v assoc :published (:name ev))
      :tile/unpublished (at-v assoc :published nil)
      :tile/retired (at-v assoc :lifecycle :retired :published nil)
      :tile/repair-proposed (at-v assoc :repair (:tile/spec ev))
      :mosaic/saved (update-in proj [:mosaics (:mosaic/id ev)]
                               (fn [m] {:owner (or (:owner m) actor)
                                        :title (:mosaic/title ev)
                                        :tiles (:mosaic/tiles ev)}))
      ;; audit only: the interview, test runs, concept proposals
      proj)))

(defn replay [events] (reduce apply-event empty-projection events))

(defn current-version
  "The highest approved, not retired, version of a tile; nil if none."
  [proj id]
  (->> (get-in proj [:tiles id :versions])
       (keep (fn [[v x]] (when (= :approved (:lifecycle x)) v)))
       (reduce (fnil max 0) nil)))

(defn catalogue
  "Declaration id (a type, link, definition, binding, or a rule id) → the live tile versions
  whose plans pin it: \"which tiles does this measure reach?\""
  [proj]
  (reduce (fn [acc [id t]]
            (reduce (fn [acc [v x]]
                      (if (= :approved (:lifecycle x))
                        (reduce #(update %1 %2 (fnil conj #{}) [id v]) acc (keys (:plan/pins x)))
                        acc))
                    acc (:versions t)))
          {} (:tiles proj)))
