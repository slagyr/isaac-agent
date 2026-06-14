(ns session.tool-history-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]))

(describe "Tool Call History in Prompts"

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

  (it "prompt includes tool call history for OpenAI-compatible providers"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tool-history"]]})
    (isaac.session.session-steps/session-has-transcript "tool-history" {:headers ["type" "message.role" "message.content" "id" "name" "arguments"], :rows [["message" "user" "What's in the fridge?" "" "" ""] ["toolCall" "" "" "call_123" "read" "{\"filePath\":\"fridge.txt\"}"] ["message" "toolResult" "1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH)" "" "" ""] ["message" "assistant" "The fridge contains a lemon of questionable morale, some cheese, and forbidden tortoise rations." "" "" ""]]})
    (isaac.session.session-steps/prompt-built-for-provider "tool-history" "openai")
    (isaac.session.session-steps/prompt-messages-contain-tool-call {:headers ["key" "value"], :rows [["role" "assistant"] ["tool_calls[0].type" "function"] ["tool_calls[0].function.name" "read"] ["tool_calls[0].id" "call_123"]]})
    (isaac.session.session-steps/prompt-messages-contain-tool-result {:headers ["key" "value"], :rows [["role" "tool"] ["tool_call_id" "call_123"] ["content" "1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH)"]]}))

  (it "prompt strips tool calls for Ollama provider"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tool-history"]]})
    (isaac.session.session-steps/session-has-transcript "tool-history" {:headers ["type" "message.role" "message.content" "id" "name" "arguments"], :rows [["message" "user" "What's in the fridge?" "" "" ""] ["toolCall" "" "" "call_123" "read" "{\"filePath\":\"fridge.txt\"}"] ["message" "toolResult" "1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH)" "" "" ""] ["message" "assistant" "The fridge contains a lemon of questionable morale, some cheese, and forbidden tortoise rations." "" "" ""]]})
    (isaac.session.session-steps/prompt-built-for-provider "tool-history" "ollama")
    (isaac.session.session-steps/prompt-messages-do-not-contain-role "tool")
    (isaac.session.session-steps/prompt-messages-do-not-contain-key "tool_calls")))
