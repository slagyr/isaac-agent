(ns session.tool-loop-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Tool Loop Message Format"

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

  (it "tool loop formats messages for OpenAI-compatible providers"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["loop-test"]]})
    (isaac.llm.providers-steps/provider-configured "openai" {:headers ["key" "value"], :rows [["base-url" "https://api.openai.com/v1"] ["api" "chat-completions"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "tool_call" "arguments"], :rows [["tool_call" "" "" "exec" "{\"command\": \"echo Hieronymus\"}"] ["text" "The tortoise says Hieronymus." "echo" "" ""]]})
    (isaac.session.session-steps/user-sends-on-session "ask the tortoise his name" "loop-test")
    (isaac.session.session-steps/tool-loop-request-contains {:headers ["role" "tool_calls[0].type" "tool_call_id"], :rows [["assistant" "function" ""] ["tool" "" "#*"]]}))

  (it "tool loop works across multiple rounds without type errors"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["loop-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "tool_call" "arguments"], :rows [["tool_call" "" "" "read" "{\"filePath\": \"fridge.txt\"}"] ["tool_call" "" "" "exec" "{\"command\": \"echo still sad\"}"] ["text" "The lemon is still sad after two checks." "echo" "" ""]]})
    (isaac.session.session-steps/user-sends-on-session "double check the fridge" "loop-test")
    (isaac.session.session-steps/session-transcript-matching "loop-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "The lemon is still sad after two checks."]]})))
