(ns isaac.mcp.turns
  "Per-turn MCP tool registry. Pure functions over an in-memory registry;
   no HTTP. The server route calls `handle`."
  (:require
    [clojure.string :as str]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.util.jsonrpc :as jrpc]))

(def TURN_NOT_ACTIVE -32001)

(defn- registry-atom []
  (or (nexus/get :mcp-turns)
      (let [registry* (atom {})]
        (nexus/register! [:mcp-turns] registry*)
        registry*)))

(defn register!
  "Register a single-use turn. `entry` is
   {:session-key _ :tool-fn _ :tools [{:name _ :description _ :parameters _} ...]}."
  [turn-id entry]
  (swap! (registry-atom) assoc turn-id entry)
  entry)

(defn lookup [turn-id]
  (get @(registry-atom) turn-id))

(defn clear!
  "Drop a turn so later MCP requests refuse with -32001."
  [turn-id]
  (swap! (registry-atom) dissoc turn-id)
  nil)

(defn clear-all! []
  (reset! (registry-atom) {}))

(defn- stringify-keys [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(name k) v]) m))
    m))

(defn- param [params k]
  (or (get params k)
      (get params (name k))
      (get params (keyword k))))

(defn- mcp-tool [tool]
  {:name        (:name tool)
   :description (or (:description tool) "")
   :inputSchema (or (:parameters tool) {:type "object"})})

(defn- tools-list [turn-id entry]
  (let [tools (mapv mcp-tool (or (:tools entry) []))]
    (log/info :mcp/tools-listed :turn turn-id :count (count tools))
    {:tools tools}))

(defn- error-text? [text]
  (and (string? text) (str/starts-with? text "Error:")))

(defn- tools-call [entry params]
  (let [name      (param params :name)
        arguments (stringify-keys (or (param params :arguments) {}))
        tool-fn   (:tool-fn entry)
        result    (str (tool-fn name arguments))
        error?    (error-text? result)]
    {:content [{:type "text" :text result}]
     :isError (boolean error?)}))

(defn- turn-not-active [id]
  {:jsonrpc jrpc/VERSION
   :id      id
   :error   {:code    TURN_NOT_ACTIVE
             :message "turn not active"}})

(defn- handlers [turn-id entry]
  {"initialize" (fn [_params _message]
                  {:protocolVersion "2025-06-18"
                   :capabilities    {:tools {}}
                   :serverInfo      {:name "isaac" :version "0.1.0"}})
   "tools/list" (fn [_params _message]
                  (tools-list turn-id entry))
   "tools/call" (fn [params _message]
                  (tools-call entry params))})

(defn- parse [message]
  (cond
    (string? message) (jrpc/parse-message message)
    (map? message)    message
    :else             ::jrpc/invalid))

(defn handle
  "Handle one MCP JSON-RPC message for a turn. `message` may be a map or a JSON string.
   Unknown/ended turns return JSON-RPC error -32001 and do not execute.
   Tool failures return isError true, never a JSON-RPC error."
  [turn-id message]
  (let [parsed (parse message)]
    (cond
      (jrpc/parse-error? parsed)
      (jrpc/parse-error)

      (not (map? parsed))
      (jrpc/invalid-request nil)

      :else
      (if-let [entry (lookup turn-id)]
        (jrpc/dispatch (handlers turn-id entry) parsed)
        (when-not (jrpc/notification? parsed)
          (turn-not-active (:id parsed)))))))
