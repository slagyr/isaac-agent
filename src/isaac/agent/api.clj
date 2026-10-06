(ns isaac.agent.api
  (:require
    [isaac.agent.bridge.core :as bridge-impl]
    [isaac.agent.comm.protocol :as comm-impl]
    [isaac.agent.comm.registry :as comm-registry]
    [isaac.foundation.reconfigurable :as reconfigurable]
    [isaac.agent.llm.api.protocol :as api-impl]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.store.spi :as session-store]))

(def Comm
  "Protocol implemented by comm integrations (Discord, Telly, etc.).
   Implement to receive lifecycle callbacks for turns, tool calls, compaction,
   and errors. See isaac.agent.comm.protocol/Comm for the full method list."
  comm-impl/Comm)

(def Reconfigurable
  "Protocol implemented by long-running module instances.
   on-load is called when the instance is first started;
   on-config-change! is called on config slice updates;
   on-unload is called when the instance is evicted.
   See isaac.foundation.reconfigurable/Reconfigurable for method signatures."
  reconfigurable/Reconfigurable)

(defn register-comm!
  "Register a Comm factory under impl-name.
   factory is (fn [host-map] -> Comm-instance) where host-map contains
   :root and :connect-ws!.
   Returns impl-name (normalised to a string). Side-effects the global registry."
  [impl-name factory]
  (comm-registry/register-factory! impl-name factory))

(defn comm-registered?
  "Return true if a Comm factory is registered under impl-name, false otherwise."
  [impl-name]
  (comm-registry/registered? impl-name))

(defn register-provider!
  "Register an Api factory by name (e.g. \"ollama\", \"anthropic\").
   factory is (fn [name cfg] -> Api).
   Returns api-key. Side-effects the global provider registry."
  [api-key factory]
  (api-impl/register! api-key factory))

(defn- crew-policy [crew store]
  store)

(defn create-session!
  "Create (or reopen) a session record through the session store.
   identifier may be a session name string or an existing session map; a nil
   identifier is named here, by the configured naming strategy, before the
   policy sees it.
   opts may include :crew, :origin, :chatType, :cwd.
   Returns the session map."
  ([identifier]
   (create-session! identifier {}))
  ([identifier opts]
   (let [store        (or (:session-store opts) (session-store/registered-store))
         session-opts (dissoc opts :root :session-store)]
     (session-store/open-session! (crew-policy (:crew opts) store)
                           (or identifier (session-store/mint-name))
                           session-opts)))
  ([root identifier opts]
   (session-store/open-session! (crew-policy (:crew opts) (session-store/create root))
                         (or identifier (session-store/mint-name root))
                         opts)))

(defn get-session
  "Return the session map for identifier, or nil if not found.
   identifier may be a session name string, key string, or session map."
  ([identifier]
   (session-store/get-session (session-store/registered-store) identifier))
  ([root identifier]
   (session-store/get-session (session-store/create root) identifier)))

(defn dispatch!
  "Comm-facing entry point for inbound messages. Triage slash commands,
   then delegate normal turns to the bridge dispatcher.
   request must have :session-key and :input; see bridge/dispatch! for full shape."
  ([request]
   (bridge-impl/dispatch! (merge (nexus/necho) request)))
  ([root request]
   (bridge-impl/dispatch! root request)))
