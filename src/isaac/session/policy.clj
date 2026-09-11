(ns isaac.session.policy
  "Per-crew session policies over a primitive SessionStore.

   Callers (bridge, drive, comms, tools) talk to SessionPolicy. The store
   underneath is persistence primitives; a policy never sees a directory."
  (:require
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]))

(defprotocol SessionPolicy
  (open-session! [this name opts])
  (delete-session! [this name])
  (rename-session! [this old-name new-name])
  (list-sessions [this])
  (list-sessions-by-agent [this agent])
  (most-recent-session [this])
  (get-session [this name])
  (get-transcript [this name])
  (active-transcript [this name])
  (chronicle-transcript [this name])
  (update-session! [this name updates])
  (append-message! [this name message])
  (append-error! [this name error])
  (append-compaction! [this name compaction])
  (append-reckoning! [this name reckoning])
  (append-checkpoint! [this name checkpoint])
  (splice-compaction! [this name compaction])
  (truncate-after-compaction! [this name])
  (record-turn-marker! [this session-id marker])
  (clear-turn-marker! [this session-id])
  (get-turn-marker [this session-id])
  (turn-markers [this])
  (default-session [this crew opts])
  (repair-transcript! [this session-id])
  (request-cancel! [this session-id]))

(defonce ^:private factories* (atom {}))

(defn register-factory!
  "Berth factory for :isaac.agent/session-policy. `factory` is
   (fn [store] policy)."
  [name factory]
  (swap! factories* assoc (keyword name) factory)
  name)

(defn unregister! [name]
  (swap! factories* dissoc (keyword name)))

(defn clear-factories!
  "Test helper. Production factories re-register at ns load."
  []
  (reset! factories* {}))

(defn registered-names
  []
  (set (keys @factories*)))

(defn- ->id [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn policy-name
  "Crew :session-policy, defaulting to :chronicle when absent."
  [crew-cfg]
  (if-let [raw (:session-policy crew-cfg)]
    (keyword (->id raw))
    :chronicle))

(def ^:private builtin-factories
  [[:chronicle 'isaac.session.policy.chronicle/create]])

(defn- ensure-builtins!
  "Register the built-in chronicle policy when its namespace has not run."
  []
  (doseq [[kw sym] builtin-factories]
    (when-not (contains? @factories* kw)
      (let [f @(requiring-resolve sym)]
        (when-not (contains? @factories* kw)
          (register-factory! kw f))))))

(defn known-policy-names
  []
  (ensure-builtins!)
  (->> (registered-names)
       (map name)
       sort
       vec))

(defn create
  "Instantiate the named policy over `store`. Unknown names throw."
  [name store]
  (ensure-builtins!)
  (let [kw (keyword (->id name))]
    (if-let [factory (get @factories* kw)]
      (factory store)
      (throw (ex-info (str "unknown session policy: " name)
                      {:policy name :registered (known-policy-names)})))))

(defn for-crew
  "Policy for `crew-id` given config and the root store."
  ([crew-id]
   (for-crew crew-id (or (some-> (nexus/get :config) deref) {}) (store/registered-store)))
  ([crew-id cfg]
   (for-crew crew-id cfg (store/registered-store)))
  ([crew-id cfg store]
   (let [crew-id  (or (->id crew-id) "main")
         crew-cfg (get-in cfg [:crew crew-id])
         name     (policy-name crew-cfg)]
     (create name store))))

(defn register-entry!
  "Per-entry factory for the :isaac.agent/session-policy berth.
   Receives `[name entry]`; resolves the entry's symbol-valued :factory
   and registers it under `name`."
  [[name entry]]
  (let [factory (some-> (:factory entry) requiring-resolve var-get)]
    (register-factory! name factory)))

(defn- policy? [x]
  (satisfies? SessionPolicy x))

(defn- store? [x]
  (satisfies? store/SessionStore x))

(defn wrap
  "If `x` is already a SessionPolicy, return it. If it is a SessionStore,
   wrap it in the chronicle policy so existing callers keep working.
   Unknown values are returned unchanged so callers can pass a policy
   that is not yet a protocol instance."
  [x]
  (cond
    (nil? x)    nil
    (policy? x) x
    (store? x)  (create :chronicle x)
    :else       x))

(defn for-request
  "Policy for a request/charge. Honours an explicit :session-policy, else
   selects from the crew's :session-policy over the root store."
  [request]
  (let [explicit (:session-policy request)]
    (or (when (and explicit (policy? explicit))
          explicit)
        (let [cfg     (or (when (map? (:config request)) (:config request))
                          (some-> (nexus/get :config) deref)
                          {})
              crew-id (or (:crew request)
                          (get-in cfg [:defaults :crew])
                          "main")
              store   (or (:session-store request) (store/registered-store))]
          (when store
            (for-crew crew-id cfg store))))))
