(ns tool.execution-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Tool execution logging"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it "Successful tool execution is logged at debug"
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.tool.tools-steps/tool-executed "read" {:headers ["file_path" "/etc/hosts"], :rows []})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "tool" "arguments.file_path"], :rows [[":debug" ":tool/start" "read" "/etc/hosts"]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "tool" "result-chars"], :rows [[":debug" ":tool/result" "read" "#\"[1-9]\\d*\""]]}))

  (it "Tool failure is logged at error with tool context"
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.tool.tools-steps/tool-executed "read" {:headers ["file_path" "/no/such/path/that/exists"], :rows []})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "tool" "arguments.file_path"], :rows [[":error" ":tool/execute-failed" "read" "/no/such/path/that/exists"]]}))

  (it "Nil tool result is treated as an error"
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.tool.tools-steps/nil-tool-registered "nil-tool")
    (isaac.tool.tools-steps/tool-executed "nil-tool" {:headers ["arg" "value"], :rows []})
    (isaac.tool.tools-steps/tool-result-indicates-error)
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "tool"], :rows [[":error" ":tool/execute-failed" "nil-tool"]]})))
