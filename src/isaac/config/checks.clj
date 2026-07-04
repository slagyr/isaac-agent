(ns isaac.config.checks
  (:require
    [clojure.java.io :as io]
    [c3kit.apron.schema :as cs]
    [isaac.config.berths :as berths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.root :as root]
    [isaac.config.validation :as validation]
    [isaac.tool.fs-bounds :as fs-bounds]))

(defn- ->id [value]
  (schema-base/->id value))

(def ^:private manifest-schema-kinds
  [:isaac.server/comm :isaac.agent/comm :isaac.agent/provider-template :isaac.agent/slash-commands :isaac.agent/tools])

(defn- verify-manifest-schema-fragment [module-id field-schema]
  (try
    (cs/verify-schema-lexes field-schema)
    []
    (catch Throwable t
      [{:key   (str "modules." (->id module-id))
        :value (if-let [ref (or (:ref (ex-data t))
                                (:lex (ex-data t)))]
                 (str "unregistered ref " ref)
                 (.getMessage t))}])))

(defn- manifest-ref-errors [module-index]
  (mapcat (fn [[module-id entry]]
            (mapcat (fn [kind]
                      (mapcat (fn [[_ extension]]
                                (when-let [field-schema (or (:extra-schema extension)
                                                             (:send-schema extension)
                                                             (:schema extension))]
                                  (verify-manifest-schema-fragment module-id field-schema)))
                              (get-in entry [:manifest kind])))
                    manifest-schema-kinds))
          module-index))

(defn- comm-reserved-schema-errors [module-index]
  (mapcat (fn [[module-id entry]]
            (keep (fn [[extension-id extension]]
                    (when (or (contains? (:extra-schema extension) :type)
                              (contains? (:send-schema extension) :type))
                      {:key   (str "modules." (->id module-id))
                       :value (str ":type is the slot discriminator, not a field"
                                   " (comm " (name extension-id) ")")}))
                  (or (get-in entry [:manifest :isaac.server/comm])
                      (get-in entry [:manifest :isaac.agent/comm]))))
          module-index))

(defn check-resolved-providers
  [{:keys [config raw-providers effective-schema]}]
  (let [resolve-provider (requiring-resolve 'isaac.config.resolve/resolve-provider)
        provider-schema  (schema-compose/provider-entity-schema effective-schema)]
    {:errors (vec
               (mapcat (fn [[provider-id provider-cfg]]
                         (when (or (:type provider-cfg) (:from provider-cfg))
                           (when-let [resolved (resolve-provider config provider-id)]
                             (validation/annotation-errors* nil ["providers" (->id provider-id)] provider-schema resolved resolved nil))))
                       raw-providers))
     :warnings []}))

(defn check-manifest-refs
  [{:keys [module-index]}]
  {:errors (vec (manifest-ref-errors module-index))
   :warnings []})

(defn check-comm-reserved-schema
  [{:keys [module-index]}]
  {:errors (vec (comm-reserved-schema-errors module-index))
   :warnings []})

(defn- broad-directory-warning [crew-id directory {:keys [root]}]
  (when (string? directory)
    (let [user-home (root/user-home)]
      (cond
        (and user-home (= directory user-home))
        {:key   (str "crew." crew-id ".tools.directories")
         :value (str "grants the entire user home (" user-home ") — use :role for the session workspace")}

        (and root (fs-bounds/path-inside? root directory))
        {:key   (str "crew." crew-id ".tools.directories")
         :value (str "includes the Isaac state directory (" root ") — use :role for the session workspace")}

        (and user-home (fs-bounds/path-inside? user-home directory)
             (not= (fs-bounds/canonical-path user-home)
                   (fs-bounds/canonical-path directory)))
        {:key   (str "crew." crew-id ".tools.directories")
         :value (str "grants a parent of the user home (" directory ") — use :role for the session workspace")}))))

(defn check-crew-broad-directories
  [{:keys [config root]}]
  (let [isaac-root (or (:root config)
                       (some-> root io/file .getParent .getPath))]
    {:errors   []
     :warnings (vec
                 (mapcat (fn [[crew-id {:keys [tools]}]]
                           (mapcat (fn [directory]
                                     (when-let [warning (broad-directory-warning (->id crew-id) directory {:root isaac-root})]
                                       [warning]))
                                   (or (:directories tools) [])))
                         (or (:crew config) {})))}))