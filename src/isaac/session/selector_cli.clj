(ns isaac.session.selector-cli
  "Shared CLI parsing and validation for session selection and override flags."
  (:require
    [clojure.string :as str]))

(def create-modes #{:never :if-missing :always})

(def prefer-modes #{:recent :oldest})

(defn parse-create
  [value]
  (when value
    (let [kw (keyword (str/lower-case (str value)))]
      (when (contains? create-modes kw) kw))))

(defn parse-prefer
  [value]
  (when value
    (let [kw (keyword (str/lower-case (str value)))]
      (when (contains? prefer-modes kw) kw))))

(defn- keyword-set [values]
  (when (seq values)
    (into #{} (map keyword) values)))

(defn- has-session-tag? [opts]
  (or (seq (:session-tag opts)) (seq (:tag opts))))

(defn- session-tags-from [opts]
  (keyword-set (or (:session-tag opts) (:tag opts))))

(defn build-select
  "Build a :select map from parsed CLI options and per-tool defaults."
  [opts & {:keys [default-session-key default-create]
           :or   {default-session-key "prompt-default"
                  default-create        :if-missing}}]
  (cond-> {:reach               :one
           :create              (or (:create opts) default-create)
           :default-session-key default-session-key}
    (:session opts) (assoc :session [(:session opts)])
    (:crew opts) (assoc :crew (:crew opts))
    (has-session-tag? opts) (assoc :session-tags (session-tags-from opts))
    (:resume opts) (assoc :resume true)
    (:prefer opts) (assoc :prefer (parse-prefer (:prefer opts)))))

(defn build-override
  "Map --with-* (and legacy aliases) to behavioral override keys."
  [opts]
  (cond-> {}
    (or (:with-model opts) (:model opts))
    (assoc :model (or (:with-model opts) (:model opts)))

    (:with-crew opts)
    (assoc :crew (:with-crew opts))

    (:with-effort opts)
    (assoc :effort (:with-effort opts))

    (:with-context-mode opts)
    (assoc :context-mode (keyword (str (:with-context-mode opts))))))

(defn validate-select-options
  "Return a seq of usage error strings for illegal flag combinations."
  [opts]
  (cond-> []
    (and (:session opts)
         (or (:crew opts) (has-session-tag? opts) (contains? opts :create)))
    (conj "--session is mutually exclusive with --crew, --session-tag, and --create")

    (and (:resume opts)
         (or (:session opts) (:crew opts) (has-session-tag? opts) (contains? opts :create)))
    (conj "--resume is mutually exclusive with session selection flags")

    (and (contains? opts :create)
         (not (contains? create-modes (:create opts))))
    (conj "--create must be one of: never, if-missing, always")

    (and (:prefer opts)
         (not (parse-prefer (:prefer opts))))
    (conj "--prefer must be recent or oldest")))

(def select-option-spec
  "Shared session selection flags for sync CLI tools."
  [["-s" "--session KEY" "Exact session id"]
   ["-R" "--resume" "Resume the most recent session"]
   ["-c" "--crew ID" "Select sessions whose crew matches"]
   [nil "--session-tag TAG" "Select sessions with this tag (repeatable, AND)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--tag TAG" "Alias for --session-tag"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--create MODE" "Create policy: never, if-missing, or always"]
   [nil "--prefer MODE" "Multi-match tiebreak: recent or oldest (default recent)"]])

(def override-option-spec
  "Shared session override flags for sync CLI tools."
  [[nil "--with-crew ID" "Override crew for this turn"]
   [nil "--with-model ALIAS" "Override model for this turn"]
   [nil "--with-effort N" "Override effort for this turn"]
   [nil "--with-context-mode MODE" "Override context mode for this turn"]
   ["-M" "--model ALIAS" "Alias for --with-model"]])

(defn parse-create-option [options]
  (when-let [mode (:create options)]
    (or (parse-create mode)
        (throw (ex-info "invalid --create" {:create mode})))))