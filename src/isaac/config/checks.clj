(ns isaac.config.checks
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [c3kit.apron.schema :as cs]
    [isaac.config.berths :as berths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.root :as root]
    [isaac.config.validation :as validation]
    [isaac.tool.fs-bounds :as fs-bounds]
    [isaac.tool.names :as names]))

(defn- ->id [value]
  (schema-base/->id value))

(def ^:private manifest-schema-kinds
  [:isaac.server/comm :isaac.agent/comm :isaac.agent/provider-template :isaac.agent/slash-commands :isaac.agent/tools :isaac.agent/turnstiles])

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

(defn known-model-ids+aliases
  "Model ids plus each registered model's provider :model string."
  [config]
  (let [models (:models config)]
    (->> (concat (keys models)
                 (map :model (vals models)))
         (keep ->id)
         distinct
         sort
         vec)))

(defn- model-ref-error [path-prefix entity-id model-id]
  {:key       (str path-prefix "." (->id entity-id) ".model")
   :value     "references undefined model"
   :bad-value (->id model-id)})

(defn- undefined-model-errors [path-prefix entities known-ids]
  (keep (fn [[entity-id entity]]
          (when-let [model-id (:model entity)]
            (when-not (contains? known-ids (->id model-id))
              (model-ref-error path-prefix entity-id model-id))))
        entities))

(defn check-crew-model-aliases
  "Accept a crew (or defaults) model that matches either a registered model id
   or another model's :model provider string. Reject true ghosts."
  [{:keys [config]}]
  (let [known (set (known-model-ids+aliases config))]
    {:errors   (vec (concat
                      (undefined-model-errors "crew" (:crew config) known)
                      (when-let [defaults-model (get-in config [:defaults :model])]
                        (when-not (contains? known (->id defaults-model))
                          [{:key       "defaults.model"
                            :value     "references undefined model"
                            :bad-value (->id defaults-model)}]))))
     :warnings []}))

(defonce ^:private model-exists-override
  (when-let [vlex (try (requiring-resolve 'isaac.config.validation-lexicon/known-model-ids)
                       (catch Throwable _ nil))]
    (alter-var-root vlex (constantly known-model-ids+aliases))
    true))

(defn check-crew-broad-directories
  [{:keys [config root]}]
  (let [isaac-root (or (:root config)
                       (some-> root io/file .getParent .getPath))]
    {:errors   []
     :warnings (vec
                 (mapcat (fn [[crew-id {:keys [tools]}]]
                           (let [directories (:directories tools)
                                 tokens      (if (map? directories)
                                               (concat (:allow directories) (:deny directories))
                                               directories)]
                             (mapcat (fn [directory]
                                       (when-let [warning (broad-directory-warning (->id crew-id) directory {:root isaac-root})]
                                         [warning]))
                                     (or tokens []))))
                         (or (:crew config) {})))}))


(defn- known-embedding-provider-ids [config]
  (let [providers (requiring-resolve 'isaac.llm.providers/known-providers)
        template  (requiring-resolve 'isaac.llm.providers/template)
        ->id      schema-base/->id
        user-ids  (->> (keys (:providers config)) (map ->id))
        templates (map ->id (providers))
        aliases   (into #{"grover"} (map #(str "grover:" %) templates))]
    {:ids (set (concat user-ids templates aliases))
     :template template
     :->id ->id}))

(defn- policy-path [prefix field]
  (if prefix (str prefix "." (name field)) (str "tools." (name field))))

(defn- policy-token-error [path idx token]
  {:key   (str path "[" idx "]")
   :value (str "must be a namespaced keyword (ns/name or ns/*); got " (pr-str token))})

(defn- policy-all-as-item-error [path]
  {:key   path
   :value ":all is the list, not a list item — use :allow :all, never [:all]"})

(defn- check-policy-field [path value]
  (cond
    (nil? value) []
    (= names/POLICY_ALL value) []
    (sequential? value)
    (if (some #(= names/POLICY_ALL %) value)
      [(policy-all-as-item-error path)]
      (vec (keep-indexed
             (fn [idx token]
               (when-not (names/config-token? token)
                 (policy-token-error path idx token)))
             value)))
    :else
    [{:key   path
      :value (str "must be :all or a vector of namespaced keywords; got " (pr-str value))}]))

(defn- check-tools-policies [prefix tools]
  (when (map? tools)
    (concat (check-policy-field (policy-path prefix :allow) (:allow tools))
            (check-policy-field (policy-path prefix :deny) (:deny tools)))))

(defn check-tool-allow-tokens
  "Reject unqualified allow/deny tokens. :all is the list, not a list item.
   Namespaced tokens and ns/* globs need not name a live tool (MCP may be down)."
  [{:keys [config]}]
  {:errors (vec
             (concat
               (check-tools-policies nil (:tools config))
               (mapcat
                 (fn [[crew-id crew]]
                   (check-tools-policies (str "crew." (->id crew-id) ".tools")
                                         (:tools crew)))
                 (or (:crew config) {}))))
   :warnings []})

(defn check-embedding-provider
  "Present-but-broken :embedding.provider references must fail validation
   with the house path-anchored undefined-provider message."
  [{:keys [config]}]
  (let [embedding (:embedding config)]
    (if-not (and (map? embedding)
                 (= "provider" (schema-base/->id (:source embedding)))
                 (some? (:provider embedding)))
      {:errors []}
      (let [provider (schema-base/->id (:provider embedding))
            {:keys [ids template ->id]} (known-embedding-provider-ids config)
            ok? (or (contains? ids provider)
                    (and (string? provider)
                         (str/starts-with? provider "grover:")
                         (boolean (template (subs provider (count "grover:"))))))]
        {:errors (if ok?
                   []
                   [{:key   "embedding.provider"
                     :value "references undefined provider"
                     :bad-value provider}])}))))
