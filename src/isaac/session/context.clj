(ns isaac.session.context
  (:require
    [clojure.edn :as edn]
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]
    [isaac.config.schema.root :as config-schema]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.effort :as effort]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.prompt.catalog :as prompt-catalog]
    [isaac.session.compaction-schema :as compaction-schema]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as store]
    [isaac.session.store.impl-common :as store-common]
    [isaac.nexus :as nexus]))

(def default-context-mode :full)

(defn read-boot-files
  ([cwd]
   (read-boot-files cwd (fs/instance)))
  ([cwd fs*]
   (when-let [project-root (prompt-catalog/discover-project-root cwd fs*)]
     (let [path (str project-root "/AGENTS.md")]
       (when (fs/exists? fs* path)
         (fs/slurp fs* path))))))

(defn read-rules-text
  ([cfg root cwd]
   (read-rules-text cfg root cwd (fs/instance)))
  ([cfg root cwd fs*]
   (let [cfg        (or cfg {})
         root' (or root (:root cfg))]
     (when (and root' (not (str/blank? (str root'))))
       (prompt-catalog/resolve-rules-text {:config    cfg
                                           :cwd       cwd
                                           :fs        fs*
                                           :root root'})))))

(defn read-skill-disclosure
  ([cfg root cwd]
   (read-skill-disclosure cfg root cwd (fs/instance)))
  ([cfg root cwd fs*]
   (let [cfg        (or cfg {})
         root' (or root (:root cfg))]
     (when (and root' (not (str/blank? (str root'))))
       (prompt-catalog/resolve-skill-disclosure {:config    cfg
                                                 :cwd       cwd
                                                 :fs        fs*
                                                 :root root'})))))

(defn default-threshold [_window] 0.8)

(defn default-head [_window] 0.3)

(def ^:private compaction-policy-keys
  #{:strategy :threshold :head :async?})

(defn- compaction-policy [m]
  (select-keys m compaction-policy-keys))

(defn default-compaction-policy
  "Static compaction defaults for config :defaults and code fallbacks."
  []
  {:async?    false
   :strategy  :rubberband
   :head      0.3
   :threshold 0.8})

(defn- session-store [explicit-store]
  (or explicit-store
      (nexus/get-in [:sessions :store])))

(defn- require-session-store [explicit-store]
  (or (session-store explicit-store)
      (throw (ex-info "session context requires :session-store" {}))))


(defn- effective-config [passed-config]
  (or passed-config
      (loader/snapshot "session behavior resolution — ambient fallback when caller passes no :config")
      {}))

(defn- default-cwd [root crew-id]
  (let [root (if (.endsWith root "/.isaac") root (str root "/.isaac"))]
    (str root "/crew/" crew-id)))

(declare resolve-compaction-config)

(def ^:private behavioral-keys
  [:compaction :context-mode :context-window :crew :cwd :effort :history-retention :model :nonce :provider])

(defn normalize-model-ref
  "Coerce a session-level model override to a canonical config id string."
  [model-ref]
  (when model-ref
    (config-schema/->id (if (and (string? model-ref) (str/starts-with? model-ref ":"))
                          (edn/read-string model-ref)
                          model-ref))))

(defn- ensure-session-nonce
  [session-entry session-store*]
  (cond
    (nil? session-entry) nil
    (seq (:nonce session-entry)) session-entry
    :else (store/update-session! session-store* (:id session-entry) {:nonce (store-common/new-nonce)})))

(defn- resolve-behavior* [cfg root session-entry]
  (let [crew-id        (or (:crew session-entry)
                           (get-in cfg [:defaults :crew])
                           "main")
        model-override (normalize-model-ref (:model session-entry))
        ctx            (resolve/resolve-crew-context cfg crew-id
                                                    (cond-> {:root root}
                                                      model-override (assoc :model-override model-override)))
        crew-cfg       (:crew-cfg ctx)
        model-ref      (or model-override
                           (:model crew-cfg)
                           (get-in cfg [:defaults :model]))
        context-window (or (:context-window session-entry)
                           (:context-window ctx)
                           32768)]
    {:compaction        (resolve-compaction-config cfg session-entry ctx context-window)
     :context-mode      (or (:context-mode session-entry)
                            (:context-mode crew-cfg)
                            (get-in cfg [:defaults :context-mode])
                            default-context-mode)
     :context-window    context-window
     :crew              crew-id
     :crew-cfg          crew-cfg
     :cwd               (or (:cwd session-entry)
                            (when root (default-cwd root crew-id)))
     :effort            (effort/resolve-effort session-entry
                                               (or crew-cfg {})
                                               (or (:model-cfg ctx) {})
                                               (or (resolve/resolve-provider cfg (get-in ctx [:model-cfg :provider])) {})
                                               (or (:defaults cfg) {}))
     :history-retention (or (:history-retention session-entry)
                            (resolve/resolve-history-retention cfg crew-id nil))
     :model             (or model-ref
                            (:model ctx))
     :model-cfg         (:model-cfg ctx)
     :nonce             (:nonce session-entry)
     :provider          (:provider ctx)
     :provider-cfg      (or (resolve/resolve-provider cfg (get-in ctx [:model-cfg :provider])) {})
     :soul              (:soul ctx)}))

(defn- compaction-layer-present? [m]
  (seq (compaction-policy m)))

(defn- higher-compaction-layers? [session-entry ctx provider-cfg]
  (or (compaction-layer-present? (:compaction session-entry))
      (compaction-layer-present? (get-in ctx [:crew-cfg :compaction]))
      (compaction-layer-present? (get-in ctx [:model-cfg :compaction]))
      (compaction-layer-present? (:compaction provider-cfg))))

(defn resolve-compaction-config
  [cfg session-entry ctx context-window]
  (let [provider-id  (or (get-in ctx [:model-cfg :provider])
                         (get-in session-entry [:provider]))
        provider-cfg (if provider-id
                       (or (resolve/resolve-provider cfg provider-id) {})
                       {})
        code-defaults {:async?    false
                       :strategy  :rubberband
                       :head      (default-head context-window)
                       :threshold (default-threshold context-window)}
        layered      (merge {}
                            (when-not (higher-compaction-layers? session-entry ctx provider-cfg)
                              (compaction-policy (get-in cfg [:defaults :compaction])))
                            (compaction-policy (:compaction provider-cfg))
                            (compaction-policy (get-in ctx [:model-cfg :compaction]))
                            (compaction-policy (get-in ctx [:crew-cfg :compaction]))
                            (compaction-policy (:compaction session-entry)))
        raw          (merge code-defaults layered)]
    (schema/coerce! compaction-schema/config-schema raw)))

(defn resolve-behavior
  ([session-key]
   (resolve-behavior session-key {}))
  ([session-key overrides]
   (let [passed-config  (:config overrides)
         root      (or (:root overrides) (:root passed-config) (loader/root))
         session-store* (session-store (:session-store overrides))
         cfg            (loader/normalize-config (effective-config passed-config))
         session-entry  (merge (or (some-> session-store* (store/get-session session-key) (ensure-session-nonce session-store*)) {})
                               (select-keys overrides behavioral-keys))
         behavior       (resolve-behavior* cfg root session-entry)]
       (log/debug :session/behavior-resolved
                  :session session-key
                :crew (:crew behavior)
                :model (:model behavior)
                :context-mode (:context-mode behavior)
                :history-retention (:history-retention behavior)
                :cwd (:cwd behavior))
     behavior)))

(defn create-with-resolved-behavior!
  [session-key opts]
  (let [passed-config (:config opts)
        root (or (:root opts) (:root passed-config) (loader/root))
        cfg       (loader/normalize-config (effective-config passed-config))
        behavior  (resolve-behavior* cfg root (select-keys opts behavioral-keys))
        store     (require-session-store (:session-store opts))]
   ;; bind the known crew set for the schema's crew validation on the writes below
   (binding [session-schema/*config* cfg]
    (let [entry     (store/open-session! store session-key {:channel           (:channel opts)
                                                            :chat-type         (or (:chat-type opts) (:chatType opts))
                                                            :crew              (:crew behavior)
                                                            :nonce             (or (:nonce opts) (store-common/new-nonce))
                                                            :tags              (:tags opts)
                                                            :cwd               (:cwd behavior)
                                                            :history-retention (:history-retention behavior)
                                                            :config            cfg
                                                            :origin            (:origin opts)})
          updates   (cond-> {}
                      (contains? opts :compaction)   (assoc :compaction (:compaction opts))
                      (contains? opts :context-mode) (assoc :context-mode (:context-mode opts))
                      (contains? opts :effort)       (assoc :effort (:effort opts))
                      (contains? opts :model)        (assoc :model (normalize-model-ref (:model opts)))
                      (contains? opts :provider)     (assoc :provider (:provider opts)))]
     (if (seq updates)
       (store/update-session! store (:id entry) updates)
       entry)))))
