(ns isaac.session.frequencies
  "Tool-agnostic session frequencies: match existing sessions by name or describe
   attributes, apply tri-state create policy, and pick one target for sync tools."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [c3kit.apron.schema :as schema]
    [isaac.session.store.spi :as store]))

(def create-modes #{:never :if-missing :always})

(def prefer-modes #{:recent :oldest})

(def reach-modes #{:one :all})

(def frequencies-keys
  #{:session :session-tags :crew :reach :prefer :create
    :with-crew :with-model :with-effort :with-context-mode})

(def frequencies-schema
  {:session
   {:type        :seq
    :description "Explicit session id(s)"
    :spec        {:type :string}}

   :session-tags
   {:type        :seq
    :description "Tags sessions must carry (AND)"
    :spec        {:type :keyword}}

   :crew
   {:type        :string
    :description "Sessions whose :crew matches this id"}

   :reach
   {:type        :keyword
    :description "How many sessions to target (:one or :all)"
    :validations [[:one-of? :one :all]]}

   :prefer
   {:type        :keyword
    :description "Multi-match tiebreak when :reach is :one (:recent or :oldest)"
    :validations [[:one-of? :recent :oldest]]}

   :create
   {:type        :keyword
    :description "Create policy: :never, :if-missing, or :always"
    :validations [[:one-of? :never :if-missing :always]]}

   :with-crew
   {:type        :string
    :description "Override crew for this turn"}

   :with-model
   {:type        :string
    :description "Override model for this turn"}

   :with-effort
   {:type        :int
    :description "Override effort for this turn"}

   :with-context-mode
   {:type        :keyword
    :description "Override context mode for this turn"}})

(defn- unknown-keys [m]
  (seq (set/difference (set (keys m)) frequencies-keys)))

(defn conform-frequencies!
  "Strictly validate a frequencies map for config and runtime use."
  [m]
  (when-let [extra (unknown-keys m)]
    (throw (ex-info "unrecognized frequencies keys"
                    {:keys (vec (sort extra))})))
  (schema/conform! frequencies-schema m))

(defn conform-frequencies
  [m]
  (if-let [extra (unknown-keys m)]
    (ex-info "unrecognized frequencies keys" {:keys (vec (sort extra))})
    (schema/conform frequencies-schema m)))

(defn- id-keyword [value]
  (when value
    (if (keyword? value) value (keyword (str value)))))

(defn- normalize-tags [tags]
  (cond
    (nil? tags) nil
    (set? tags) (set (map keyword tags))
    (sequential? tags) (into #{} (map keyword) tags)
    :else (into #{} (map keyword) tags)))

(defn- session-matches? [session-entry {:keys [session session-tags crew]}]
  (let [session-ids (when (seq session)
                      (set (map #(if (keyword? %) (name %) (str %)) session)))
        tag-set     (normalize-tags session-tags)
        crew-id     (when crew (if (keyword? crew) (name crew) (str crew)))]
    (and
      (or (nil? session-ids) (contains? session-ids (:id session-entry)))
      (or (nil? tag-set)
          (every? #(store/has-tag? session-entry %) tag-set))
      (or (nil? crew-id) (= crew-id (:crew session-entry))))))

(defn matching-sessions
  "Return sessions from `all-sessions` that match the describe/explicit frequencies map."
  [frequencies all-sessions]
  (->> all-sessions
       (filter #(session-matches? % frequencies))
       vec))

(defn- pick-by-prefer [sessions prefer]
  (let [sorted (sort-by :updated-at sessions)]
    (case (or prefer :recent)
      :oldest (first sorted)
      :recent (last sorted))))

(defn- describe-select? [{:keys [crew session-tags]}]
  (boolean (or crew (seq session-tags))))

(defn- explicit-session? [{:keys [session]}]
  (seq session))

(defn- resume-select? [{:keys [resume]}]
  resume)

(defn- no-match-message [frequencies]
  (cond
    (explicit-session? frequencies)
    (str "no session: " (first (:session frequencies)))

    (:crew frequencies)
    (str "no session for crew: " (:crew frequencies))

    (seq (:session-tags frequencies))
    (str "no session matching tags: " (str/join " " (map name (:session-tags frequencies))))

    :else
    (str "no session: " (:default-session-key frequencies "prompt-default"))))

(defn resolve-session-targets
  "Resolve a single session target from `frequencies` and `session-store`.

   Selection keys in `frequencies`:
     :session            — exact session id(s); first is used
     :session-tags       — tag set (AND)
     :crew               — crew id
     :reach              — :one (sync tools; default)
     :prefer             — :recent | :oldest; multi-match tiebreak when :reach :one (default :recent)
     :resume             — select across all sessions (--resume); mutually exclusive with describe flags
     :create             — :never | :if-missing | :always (default :if-missing)
     :default-session-key — fallback when no describe/explicit selector (default prompt-default)

   Returns:
     {:session-key \"id\" :session {...} :create? false}
     {:session-key \"id\" :session nil :create? true :create-identity {...}}
     {:session-key nil :session nil :create? true :create-identity {...}}  ; generated key
     {:error :no-match :message \"...\"}"
  [frequencies session-store]
  (let [frequencies  (merge {:reach :one :prefer :recent :create :if-missing :default-session-key "prompt-default"}
                            frequencies)
        create       (:create frequencies)
        all-sessions (store/list-sessions session-store)]
    (cond
      (explicit-session? frequencies)
      (let [session-key (first (:session frequencies))
            existing    (store/get-session session-store session-key)]
        (cond
          existing
          {:session-key session-key :session existing :create? false}

          (= create :never)
          {:error :no-match :message (no-match-message frequencies)}

          :else
          {:session-key session-key
           :session     nil
           :create?     true
           :create-identity (select-keys frequencies [:crew :session-tags])}))

      (describe-select? frequencies)
      (let [matches (matching-sessions frequencies all-sessions)]
        (cond
          (= create :always)
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity (cond-> {}
                              (:crew frequencies) (assoc :crew (:crew frequencies))
                              (:session-tags frequencies) (assoc :tags (:session-tags frequencies)))}

          (seq matches)
          (let [picked (pick-by-prefer matches (:prefer frequencies))]
            {:session-key (:id picked) :session picked :create? false})

          (= create :never)
          {:error :no-match :message (no-match-message frequencies)}

          :else
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity (cond-> {}
                              (:crew frequencies) (assoc :crew (:crew frequencies))
                              (:session-tags frequencies) (assoc :tags (:session-tags frequencies)))}))

      (resume-select? frequencies)
      (let [matches (matching-sessions {} all-sessions)]
        (cond
          (= create :always)
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity {}}

          (seq matches)
          (let [picked (pick-by-prefer matches (:prefer frequencies))]
            {:session-key (:id picked) :session picked :create? false})

          (= create :never)
          {:error :no-match :message (no-match-message frequencies)}

          :else
          {:session-key     (:default-session-key frequencies)
           :session         nil
           :create?         true
           :create-identity {}}))

      :else
      (let [session-key (:default-session-key frequencies)
            existing    (store/get-session session-store session-key)]
        (cond
          existing
          {:session-key session-key :session existing :create? false}

          (= create :never)
          {:error :no-match :message (no-match-message frequencies)}

          :else
          {:session-key session-key
           :session     nil
           :create?     true
           :create-identity {}})))))

(defn behavioral-override
  "Project :with-* override keys to behavioral-keys for create-with-resolved-behavior!."
  [frequencies]
  (cond-> {}
    (:with-model frequencies) (assoc :model (:with-model frequencies))
    (:with-crew frequencies) (assoc :crew (:with-crew frequencies))
    (:with-effort frequencies) (assoc :effort (:with-effort frequencies))
    (:with-context-mode frequencies) (assoc :context-mode (:with-context-mode frequencies))))