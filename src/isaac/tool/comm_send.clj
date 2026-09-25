(ns isaac.tool.comm-send
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.factory :as comm-factory]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.tool.fs-bounds :as bounds]))

(def ^:private attachments-description
  "Local file paths to attach. Only comms that accept attachments take them.")

(defn- snapshot-config []
  (or (loader/snapshot "comm_send tool")
      {}))

(defn- impl-keyword
  "The impl a slot sends through: its :type, else its slot id - the same
   rule the comm factory instantiates by (isaac-baf1)."
  [slot-id slot-cfg]
  (comm-factory/impl-id [slot-id] slot-cfg))

(defn- manifest-comm-entry [module-index impl-kw]
  (some (fn [[_ entry]]
          (get-in entry [:manifest :isaac.agent/comm impl-kw]))
        module-index))

(defn- send-schema-for-slot [module-index slot-id slot-cfg]
  (or (:send-schema (manifest-comm-entry module-index (impl-keyword slot-id slot-cfg)))
      {}))

(defn- accepts-attachments? [module-index slot-id slot-cfg]
  (true? (:send-attachments? (manifest-comm-entry module-index (impl-keyword slot-id slot-cfg)))))

(defn- union-send-schema [module-index comms]
  (reduce-kv
    (fn [acc slot-id slot-cfg]
      (merge acc (send-schema-for-slot module-index slot-id slot-cfg)))
    {}
    (or comms {})))

(defn- schema-type->json [field-spec]
  (case (:type field-spec)
    :int     "integer"
    :boolean "boolean"
    :string  "string"
    "string"))

(defn- comm-slot-ids [comms]
  (vec (sort (map (fn [k] (if (keyword? k) (name k) (str k)))
                  (keys (or comms {}))))))

(defn- comm-slot-id-str [k]
  (if (keyword? k) (name k) (str k)))

(defn- resolve-comm-slot [comms comm-str]
  (when-not (str/blank? comm-str)
    (let [comm-kw (keyword comm-str)]
      (cond
        (contains? comms comm-kw)
        {:record-key comm-kw :cfg (get comms comm-kw)}

        (contains? comms comm-str)
        {:record-key comm-kw :cfg (get comms comm-str)}

        :else
        (some (fn [[k cfg]]
                (when (= comm-str (comm-slot-id-str k))
                  {:record-key comm-kw :cfg cfg}))
              comms)))))

(defn- field-json-key [field]
  (if-let [ns* (namespace field)]
    (str ns* "." (name field))
    (name field)))

(defn- field-present? [validations]
  (some #(= :present? (if (vector? %) (first %) %)) (or validations [])))

(defn- required-send-fields [send-schema]
  (->> send-schema
       (filter (fn [[_ spec]] (field-present? (:validations spec))))
       (map first)
       vec))

(defn build-parameters
  "JSON-schema parameters for comm_send: comm + content + attachments plus
   the union of configured comms' namespaced :send-schema fields (all
   optional in the union; required-ness is enforced per chosen comm at
   execution)."
  [{:keys [module-index comms]}]
  (let [union-fields (union-send-schema module-index comms)
        properties   (merge {"comm"    {:type        "string"
                                        :description "Configured comm slot id"
                                        :enum        (comm-slot-ids comms)}
                              "content" {:type        "string"
                                         :description "Message body"}
                              "attachments" {:type        "array"
                                             :items       {:type "string"}
                                             :description attachments-description}}
                            (into {}
                                  (map (fn [[field spec]]
                                         [(field-json-key field)
                                          (cond-> {:type (schema-type->json spec)}
                                            (:description spec) (assoc :description (:description spec)))])
                                       union-fields)))]
    {:type       "object"
     :properties properties
     :required   ["comm" "content"]}))

(defn- arg-value [args k]
  (let [json-key (field-json-key k)]
    (or (get args json-key)
        (get args (keyword json-key))
        (get args (name k))
        (get args k))))

(defn- build-record [comm-kw content args send-schema]
  (into {:comm comm-kw :content content}
        (keep (fn [[field _spec]]
                (when-let [v (arg-value args field)]
                  (when-not (str/blank? (str v))
                    [field v])))
              send-schema)))

(defn- error [message]
  {:isError true :error message})

(defn- attachment-args [args]
  (let [v (or (get args "attachments") (get args :attachments))]
    (cond
      (nil? v)                                v
      (and (sequential? v) (every? string? v)) (vec v)
      :else                                   ::invalid)))

(defn- attachment-error [args path]
  (let [fs* (bounds/filesystem args)]
    (or (bounds/ensure-path-allowed args path)
        (cond
          (not (fs/exists? fs* path)) (error (str "attachment not found: " path))
          (not (fs/file? fs* path))   (error (str "attachment is not a regular file: " path))))))

(defn- resolve-attachments
  "Returns {:paths [...]} with each path resolved against the session cwd,
   or {:error ...} naming the first refused path."
  [args paths]
  (let [cwd      (bounds/session-workdir args)
        resolved (mapv #(bounds/resolve-path % cwd) paths)]
    (if-let [refusal (some #(attachment-error args %) resolved)]
      {:error refusal}
      {:paths resolved})))

(defn- missing-send-fields [args send-schema]
  (remove #(some (fn [v] (not (str/blank? (str v))))
                 [(arg-value args %)])
          (required-send-fields send-schema)))

(defn- enqueue-for-slot! [args comm-str content {comm-kw :record-key slot-cfg :cfg} module-index]
  (let [send-schema (send-schema-for-slot module-index comm-kw slot-cfg)
        missing     (missing-send-fields args send-schema)
        attachments (attachment-args args)
        attaching?  (and (vector? attachments) (seq attachments))
        resolved    (when attaching? (resolve-attachments args attachments))]
    (cond
      (seq missing)
      (error (str "missing required field(s) for "
                  (name (impl-keyword comm-kw slot-cfg))
                  ": "
                  (str/join ", " (map name missing))))

      (= ::invalid attachments)
      (error "attachments must be an array of file paths")

      (and attaching? (not (accepts-attachments? module-index comm-kw slot-cfg)))
      (error (str "comm " comm-str " does not accept attachments"))

      (:error resolved)
      (:error resolved)

      :else
      {:result (:id (queue/enqueue! (cond-> (build-record comm-kw content args send-schema)
                                      (seq (:paths resolved)) (assoc :attachments (:paths resolved)))))})))

(defn comm-send-tool
  "Queue an outbound comm delivery. Args use string keys (LLM JSON)."
  [arguments]
  (let [args     (if (map? arguments) arguments {})
        comm-str (some-> (or (get args "comm") (get args :comm)) str str/trim)
        content  (some-> (or (get args "content") (get args :content)) str)
        cfg      (snapshot-config)
        slot     (resolve-comm-slot (:comms cfg {}) comm-str)]
    (cond
      (str/blank? comm-str) (error "comm is required")
      (str/blank? content)  (error "content is required")
      (nil? slot)           (error (str "unknown comm slot: " comm-str))
      :else                 (enqueue-for-slot! args comm-str content slot (:module-index cfg)))))

(defn comm-send-tool-factory [_]
  (let [cfg (snapshot-config)]
    {:description "Send a message over a configured comm channel (queue-first)."
     :parameters  (build-parameters {:module-index (:module-index cfg)
                                     :comms        (:comms cfg)})
     :handler     #'comm-send-tool}))