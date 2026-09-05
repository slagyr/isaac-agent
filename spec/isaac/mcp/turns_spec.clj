(ns isaac.mcp.turns-spec
  (:require
    [isaac.logger :as log]
    [isaac.mcp.turns :as sut]
    [isaac.nexus :as nexus]
    [isaac.spec-helper :as helper]
    [isaac.util.jsonrpc :as jrpc]
    [speclj.core :refer :all]))

(defn- echo-tool-fn [name arguments]
  (str name ":" (get arguments "command" (get arguments :command))))

(defn- error-tool-fn [_name _arguments]
  "Error: nope")

(describe "MCP per-turn registry"

  (around [it]
    (nexus/-with-nexus {:mcp-turns (atom {})}
      (it)))

  (before (sut/clear-all!))

  (it "looks up a registered turn by id"
    (sut/register! "t-list" {:session-key "mcp-sess"
                             :tool-fn     echo-tool-fn
                             :tools       [{:name "exec__run" :description "Run" :parameters {:type "object"}}]})
    (should= "mcp-sess" (:session-key (sut/lookup "t-list"))))

  (it "clears a registered turn so it is no longer active"
    (sut/register! "t-done" {:session-key "mcp-sess" :tool-fn echo-tool-fn :tools []})
    (sut/clear! "t-done")
    (should-be-nil (sut/lookup "t-done")))

  (context "handle"

    (helper/with-captured-logs)

    (it "refuses an unknown turn with JSON-RPC -32001 and does not invoke the tool-fn"
      (let [called? (atom false)
            response (sut/handle "t-ghost"
                                 (jrpc/request 5 "tools/call" {:name "exec__run"
                                                               :arguments {:command "echo never"}}))]
        (should-not @called?)
        (should= -32001 (get-in response [:error :code]))
        (should (re-find #"(?i)turn not active" (get-in response [:error :message])))
        (should= 5 (:id response))))

    (it "refuses a cleared turn with JSON-RPC -32001"
      (sut/register! "t-done" {:session-key "mcp-sess" :tool-fn echo-tool-fn :tools []})
      (sut/clear! "t-done")
      (let [response (sut/handle "t-done" "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\"}")]
        (should= -32001 (get-in response [:error :code]))))

    (it "lists the turn's tools as MCP input schemas in registration order"
      (sut/register! "t-list" {:session-key "mcp-sess"
                               :tool-fn     echo-tool-fn
                               :tools       [{:name        "exec__run"
                                              :description "Run a command"
                                              :parameters  {:type "object" :properties {"command" {:type "string"}}}}
                                             {:name        "fs__read"
                                              :description "Read a file"
                                              :parameters  {:type "object"}}]})
      (let [response (sut/handle "t-list" (jrpc/request 1 "tools/list"))
            tools    (get-in response [:result :tools])]
        (should= "exec__run" (:name (first tools)))
        (should= "object" (get-in (first tools) [:inputSchema :type]))
        (should= "fs__read" (:name (second tools)))
        (should= "object" (get-in (second tools) [:inputSchema :type]))
        (should= 2 (:count (first (filter #(= :mcp/tools-listed (:event %)) @log/captured-logs))))
        (should= "t-list" (:turn (first (filter #(= :mcp/tools-listed (:event %)) @log/captured-logs))))))

    (it "calls the turn's tool-fn and returns MCP text content"
      (sut/register! "t-call" {:session-key "mcp-sess"
                               :tool-fn     echo-tool-fn
                               :tools       [{:name "exec__run" :parameters {:type "object"}}]})
      (let [response (sut/handle "t-call"
                                 (jrpc/request 2 "tools/call" {:name "exec__run"
                                                               :arguments {"command" "echo hi"}}))]
        (should= false (get-in response [:result :isError]))
        (should= "text" (get-in response [:result :content 0 :type]))
        (should= "exec__run:echo hi" (get-in response [:result :content 0 :text]))))

    (it "returns isError true for a failing tool instead of a JSON-RPC error"
      (sut/register! "t-fail" {:session-key "mcp-sess"
                               :tool-fn     error-tool-fn
                               :tools       [{:name "fs__read" :parameters {:type "object"}}]})
      (let [response (sut/handle "t-fail"
                                 (jrpc/request 3 "tools/call" {:name "fs__read"
                                                               :arguments {:file_path "/nope/missing.txt"}}))]
        (should= 3 (:id response))
        (should (true? (get-in response [:result :isError])))
        (should-be-nil (:error response))))

    (it "answers initialize for an active turn"
      (sut/register! "t-init" {:session-key "mcp-sess" :tool-fn echo-tool-fn :tools []})
      (let [response (sut/handle "t-init" (jrpc/request 1 "initialize" {:protocolVersion "2025-06-18"}))]
        (should= "isaac" (get-in response [:result :serverInfo :name]))))
    )
  )
