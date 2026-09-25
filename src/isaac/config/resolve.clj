(ns isaac.config.resolve
  "Server-side provider/crew resolution against a loaded config value.
   Requires the foundation loader for normalize-config and workspace reads."
  (:require
    [clojure.string :as str]
    [isaac.config.defaults :as defaults]
    [isaac.config.loader :as loader]
    [isaac.config.schema.root :as schema]
    [isaac.llm.provider :as llm-provider]
    [isaac.llm.providers :as llm-providers]))

(def ^:private ->id schema/->id)

(defn default-crew [cfg]
  (defaults/crew-id (loader/normalize-config (or cfg {}))))

(defn- provider-unresolved-refs
  "The loader's unresolved references (isaac-rxun) for one provider's fields,
   or nil. `provider-id` may be a simulated id (\"grover:quantum-anvil\"); its
   fields live under the base provider's entry."
  [cfg provider-id]
  (let [prefix (str "providers." (first (str/split provider-id #":" 2)) ".")]
    (not-empty (into {} (filter (fn [[path _]] (str/starts-with? path prefix)))
                     (:unresolved-refs cfg)))))

(defn resolve-provider
  "The provider entity under the :defaults :provider template. Nil when no such
   provider is configured — the template alone is not a provider.

   When any of its fields was emptied by an unresolvable ${VAR}, the slice
   carries those reasons under :unresolved-refs, so the point of use can name
   the variable from the value it is handed (isaac-rxun)."
  [cfg provider-id]
  (let [cfg         (loader/normalize-config cfg)
        provider-id (->id provider-id)]
    (when provider-id
      (when-let [provider-cfg (or (llm-providers/lookup cfg (:module-index cfg) provider-id)
                                  (when-let [idx (str/index-of provider-id ":")]
                                    (get-in cfg [:providers (subs provider-id 0 idx)])))]
        (let [refs (provider-unresolved-refs cfg provider-id)]
          (cond-> (merge (defaults/provider-template cfg) provider-cfg)
            refs (assoc :unresolved-refs refs)))))))

(defn parse-model-ref [model-ref]
  (let [idx (str/index-of model-ref "/")]
    (when idx
      {:provider (subs model-ref 0 idx)
       :model    (subs model-ref (inc idx))})))

(defn- model-by-provider-string [cfg model-id]
  (when model-id
    (some (fn [[_ model-cfg]]
            (when (= model-id (->id (:model model-cfg)))
              model-cfg))
          (:models cfg))))

(defn- lookup-model-cfg
  "The model entity under the :defaults :model template."
  [cfg model-id]
  (when-let [model-cfg (or (get-in cfg [:models model-id])
                           (get-in cfg [:models (keyword model-id)])
                           (model-by-provider-string cfg model-id))]
    (merge (defaults/model-template cfg) model-cfg)))

(defn- model-override-cfg [cfg model-override]
  (or (lookup-model-cfg cfg model-override)
      (parse-model-ref model-override)))

(defn- model-override-provider-opts [cfg provider-cfg model-cfg]
  (merge (select-keys (defaults/provider-template cfg) [:stream-idle-timeout-ms])
         provider-cfg
         {:module-index (:module-index cfg)}
         (select-keys model-cfg [:enforce-context-window :thinking-budget-max :think-mode])))

(defn- model-override-provider [cfg provider-id provider-cfg model-cfg]
  (when provider-id
    (llm-provider/make-provider provider-id (model-override-provider-opts cfg provider-cfg model-cfg))))

(defn- model-override-context-window [ctx model-cfg provider-cfg]
  (or (:context-window model-cfg)
      (:context-window provider-cfg)
      (:context-window ctx)
      32768))

(defn resolve-crew
  "The crew entity under the :defaults :crew template: every crew behaves as if
   it had set the template's fields, and its own values outrank them."
  [cfg crew-id]
  (let [cfg     (loader/normalize-config cfg)
        crew-id (->id crew-id)]
    (merge (defaults/crew-template cfg)
           (get-in cfg [:crew crew-id] {}))))

(def default-history-retention :retain)

(defn resolve-history-retention
  "Resolve history retention for a new session from the chain:
   explicit override > crew > model > provider > defaults > :retain."
  [cfg crew-id explicit-retention]
  (let [cfg          (loader/normalize-config (or cfg {}))
        crew-cfg     (resolve-crew cfg crew-id)
        model-id     (or (:model crew-cfg) (defaults/model-id cfg))
        model-cfg    (or (lookup-model-cfg cfg model-id)
                         (when-let [provider-id (:provider crew-cfg)]
                           {:provider provider-id}))
        provider-id  (:provider model-cfg)
        provider-cfg (or (resolve-provider cfg provider-id) {})]
    (or explicit-retention
        (:history-retention crew-cfg)
        (:history-retention model-cfg)
        (:history-retention provider-cfg)
        (defaults/history-retention cfg)
        default-history-retention)))

(defn- apply-model-override [cfg ctx model-override]
  (let [cfg       (loader/normalize-config cfg)
        model-cfg (model-override-cfg cfg model-override)]
    (if-not model-cfg
      ctx
      (let [provider-id  (:provider model-cfg)
            provider-cfg (or (resolve-provider cfg provider-id) {})]
        (assoc ctx
          :model (:model model-cfg)
          :model-cfg model-cfg
          :provider-cfg provider-cfg
          :provider (model-override-provider cfg provider-id provider-cfg model-cfg)
          :context-window (model-override-context-window ctx model-cfg provider-cfg))))))

(defn resolve-crew-context [cfg crew-id & [opts]]
  (let [cfg          (cond-> cfg
                             (:crew-members opts) (assoc :crew (:crew-members opts))
                             (:models opts) (assoc :models (:models opts))
                             (:providers opts) (assoc :providers (:providers opts)))
        cfg          (loader/normalize-config cfg)
        crew-id      (->id crew-id)
        crew-cfg     (resolve-crew cfg crew-id)
        model-id     (or (:model crew-cfg) (defaults/model-id cfg))
        model-cfg    (or (lookup-model-cfg cfg model-id)
                         (when-let [provider-id (:provider crew-cfg)]
                           {:model model-id :provider provider-id})
                         (when model-id (parse-model-ref model-id)))
        provider-id  (:provider model-cfg)
        provider-cfg (merge (or (resolve-provider cfg provider-id) {})
                            (select-keys model-cfg [:enforce-context-window :thinking-budget-max :think-mode :stateful])
                            {:module-index (:module-index cfg)})
        ctx          {:soul           (or (:soul crew-cfg)
                                          (loader/read-workspace-file crew-id "SOUL.md" opts)
                                          "You are Isaac, a helpful AI assistant.")
                      :model          (:model model-cfg)
                      :model-cfg      model-cfg
                      :crew-cfg       crew-cfg
                      :provider-cfg   (or (resolve-provider cfg provider-id) {})
                      :provider       (when provider-id
                                        (llm-provider/make-provider provider-id provider-cfg))
                      :context-window (or (:context-window model-cfg)
                                          (:context-window provider-cfg)
                                          32768)}]
    (if-let [model-override (:model-override opts)]
      (apply-model-override cfg ctx (->id model-override))
      ctx)))