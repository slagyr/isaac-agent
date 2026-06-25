(ns isaac.tool.comm-send
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.config.loader :as loader]))

(defn- snapshot-config []
  (or (loader/snapshot "comm_send tool")
      {}))

(defn- impl-keyword [slot-cfg]
  (keyword (name (:type slot-cfg))))

(defn- manifest-comm-entry [module-index impl-kw]
  (some (fn [[_ entry]]
          (get-in entry [:manifest :isaac.server/comm impl-kw]))
        module-index))

(defn- send-schema-for-slot [module-index slot-cfg]
  (or (:send-schema (manifest-comm-entry module-index (impl-keyword slot-cfg)))
      {}))

(defn- union-send-schema [module-index comms]
  (reduce-kv
    (fn [acc _slot-id slot-cfg]
      (merge acc (send-schema-for-slot module-index slot-cfg)))
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
  "JSON-schema parameters for comm_send: comm + content plus the union of
   configured comms' namespaced :send-schema fields (all optional in the
   union; required-ness is enforced per chosen comm at execution)."
  [{:keys [module-index comms]}]
  (let [union-fields (union-send-schema module-index comms)
        properties   (merge {"comm"    {:type        "string"
                                        :description "Configured comm slot id"
                                        :enum        (comm-slot-ids comms)}
                              "content" {:type        "string"
                                         :description "Message body"}}
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

(defn comm-send-tool
  "Queue an outbound comm delivery. Args use string keys (LLM JSON)."
  [arguments]
  (let [args      (if (map? arguments) arguments {})
        comm-str  (some-> (or (get args "comm") (get args :comm)) str str/trim)
        content   (some-> (or (get args "content") (get args :content)) str)
        cfg       (snapshot-config)
        comms     (:comms cfg {})
        slot         (resolve-comm-slot comms comm-str)]
    (cond
      (str/blank? comm-str)
      {:isError true :error "comm is required"}

      (str/blank? content)
      {:isError true :error "content is required"}

      (nil? slot)
      {:isError true :error (str "unknown comm slot: " comm-str)}

      :else
      (let [comm-kw        (:record-key slot)
            slot-cfg       (:cfg slot)
            send-schema    (send-schema-for-slot (:module-index cfg) slot-cfg)
            missing        (remove #(some (fn [v] (not (str/blank? (str v))))
                                        [(arg-value args %)])
                                 (required-send-fields send-schema))]
        (if (seq missing)
          {:isError true
           :error   (str "missing required field(s) for "
                         (name (impl-keyword slot-cfg))
                         ": "
                         (str/join ", " (map name missing)))}
          {:result (:id (queue/enqueue! (build-record comm-kw content args send-schema)))})))))

(defn comm-send-tool-factory [_]
  (let [cfg (snapshot-config)]
    {:description "Send a message over a configured comm channel (queue-first)."
     :parameters  (build-parameters {:module-index (:module-index cfg)
                                     :comms        (:comms cfg)})
     :handler     #'comm-send-tool}))