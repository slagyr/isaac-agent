(ns isaac.mcp.mcp-steps
  (:require
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as drive-turn]
    [isaac.mcp.turns :as mcp-turns]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [isaac.tool.registry :as tool-registry]))

(helper! isaac.mcp.mcp-steps)

(g/after-scenario
  (fn []
    (mcp-turns/clear-all!)))

(defn- session-store []
  (or (store/registered-store)
      (nexus/get-in [:sessions :store])))

(defn- allowed-tools []
  (let [cfg   (or (loader/snapshot "mcp turn fixture — crew allow-list") {})
        allow (or (get-in cfg [:crew :main :tools :allow])
                  (get-in cfg [:crew "main" :tools :allow])
                  [])]
    (vec allow)))

(defn- tools-for-allow [allowed]
  (let [defs    (tool-registry/tool-definitions allowed)
        by-name (into {} (map (juxt :name identity) defs))]
    (mapv (fn [token]
            (let [wire (or (names/wire-name token) (str token))]
              (or (get by-name wire)
                  {:name        wire
                   :description wire
                   :parameters  {:type "object"}})))
          allowed)))

(defn- snapshot-caps []
  (let [cfg (or (loader/snapshot "mcp turn fixture — tool output caps") {})]
    {:max-lines (get-in cfg [:tools :defaults :max-lines])
     :max-bytes (get-in cfg [:tools :defaults :max-bytes])}))

(defn- drive-tool-fn [session-key allowed]
  (fn [name arguments]
    (let [tool-ctx {:comm          null-comm/channel
                    :session-key   session-key
                    :allowed-tools allowed
                    :module-index  nil
                    :caps          (snapshot-caps)
                    :tool-count    (atom 0)
                    :ctx           {:session-store (session-store)}}]
      (#'drive-turn/record-tool-call! tool-ctx name arguments))))

(defn turn-registered [turn-id session-key]
  (let [allowed (allowed-tools)]
    (mcp-turns/register! turn-id {:session-key session-key
                                  :tool-fn     (drive-tool-fn session-key allowed)
                                  :tools       (tools-for-allow allowed)})))

(defn turn-cleared [turn-id]
  (mcp-turns/clear! turn-id))

(defn- commit-caps-config! []
  (when-let [root (or (g/get :runtime-root-dir) (g/get :root))]
    (let [fs* (or (g/get :mem-fs) (nexus/get :fs))
          cfg (:config (loader/load-config-result {:root root :fs fs*}))]
      (loader/set-snapshot! cfg "mcp turn fixture — commit tool caps"))))

(defn mcp-request-handled [turn-id json]
  (commit-caps-config!)
  (g/assoc! :mcp-response (mcp-turns/handle turn-id json)))

(defn mcp-response-matches [table]
  (let [result (match/match-object table (g/get :mcp-response))]
    (g/should= [] (:failures result))))

(defgiven "a turn {turn-id:string} is registered for session {session-key:string}"
  isaac.mcp.mcp-steps/turn-registered
  "Writes a registry entry for the named turn with the session's crew
   tools and a real record-tool-call! tool-fn. The driver will do this
   at turn start; these scenarios have no driver.")

(defgiven "the turn {turn-id:string} is cleared"
  isaac.mcp.mcp-steps/turn-cleared
  "Drops the registry entry so later MCP requests refuse with -32001.")

(defwhen "an MCP request is handled for turn {turn-id:string}:"
  isaac.mcp.mcp-steps/mcp-request-handled
  "Calls isaac.mcp.turns/handle directly (no HTTP). Docstring is one JSON-RPC message.")

(defthen "the MCP response matches:"
  isaac.mcp.mcp-steps/mcp-response-matches
  "Matches the last MCP response map against a key|value table using the match DSL.")
