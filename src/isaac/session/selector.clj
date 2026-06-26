(ns isaac.session.selector
  "Tool-agnostic session selection: match existing sessions by name or describe
   attributes, apply tri-state create policy, and pick one target for sync tools."
  (:require
    [clojure.string :as str]
    [isaac.session.store.spi :as store]))

(defn- id-keyword [value]
  (when value
    (if (keyword? value) value (keyword (str value)))))

(defn- normalize-tags [tags]
  (cond
    (nil? tags) nil
    (set? tags) (set (map keyword tags))
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
  "Return sessions from `all-sessions` that match the describe/explicit :select map."
  [select all-sessions]
  (->> all-sessions
       (filter #(session-matches? % select))
       vec))

(def prefer-modes #{:recent :oldest})

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

(defn- no-match-message [select]
  (cond
    (explicit-session? select)
    (str "no session: " (first (:session select)))

    (:crew select)
    (str "no session for crew: " (:crew select))

    (seq (:session-tags select))
    (str "no session matching tags: " (str/join " " (map name (:session-tags select))))

    :else
    (str "no session: " (:default-session-key select "prompt-default"))))

(defn resolve-session-targets
  "Resolve a single session target from `select` and `session-store`.

   `select` keys:
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
  [select session-store]
  (let [select         (merge {:reach :one :prefer :recent :create :if-missing :default-session-key "prompt-default"}
                              select)
        create         (:create select)
        all-sessions   (store/list-sessions session-store)]
    (cond
      (explicit-session? select)
      (let [session-key (first (:session select))
            existing    (store/get-session session-store session-key)]
        (cond
          existing
          {:session-key session-key :session existing :create? false}

          (= create :never)
          {:error :no-match :message (no-match-message select)}

          :else
          {:session-key session-key
           :session     nil
           :create?     true
           :create-identity (select-keys select [:crew :session-tags])}))

      (describe-select? select)
      (let [matches (matching-sessions select all-sessions)]
        (cond
          (= create :always)
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity (cond-> {}
                              (:crew select) (assoc :crew (:crew select))
                              (:session-tags select) (assoc :tags (:session-tags select)))}

          (seq matches)
          (let [picked (pick-by-prefer matches (:prefer select))]
            {:session-key (:id picked) :session picked :create? false})

          (= create :never)
          {:error :no-match :message (no-match-message select)}

          :else
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity (cond-> {}
                              (:crew select) (assoc :crew (:crew select))
                              (:session-tags select) (assoc :tags (:session-tags select)))}))

      (resume-select? select)
      (let [matches (matching-sessions {} all-sessions)]
        (cond
          (= create :always)
          {:session-key     nil
           :session         nil
           :create?         true
           :create-identity {}}

          (seq matches)
          (let [picked (pick-by-prefer matches (:prefer select))]
            {:session-key (:id picked) :session picked :create? false})

          (= create :never)
          {:error :no-match :message (no-match-message select)}

          :else
          {:session-key     (:default-session-key select)
           :session         nil
           :create?         true
           :create-identity {}}))

      :else
      (let [session-key (:default-session-key select)
            existing    (store/get-session session-store session-key)]
        (cond
          existing
          {:session-key session-key :session existing :create? false}

          (= create :never)
          {:error :no-match :message (no-match-message select)}

          :else
          {:session-key session-key
           :session     nil
           :create?     true
           :create-identity {}})))))