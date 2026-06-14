(ns session.llm-interaction-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.comm.comm-steps :as comm-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "LLM Interaction"

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

  (it "Send a message and receive a response"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Four, I think" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "What is 2+2?" "llm-chat")
    (isaac.session.session-steps/session-transcript-matching "llm-chat" {:headers ["type" "message.role" "message.model" "message.provider"], :rows [["message" "assistant" "echo" "grover"]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "input-tokens" "output-tokens"], :rows [["llm-chat" "#\"\\d+\"" "#\"\\d+\""]]}))

  (it "Streaming response"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Once upon a time..." "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Tell me a story" "llm-chat")
    (isaac.session.session-steps/session-transcript-matching "llm-chat" {:headers ["type" "message.role"], :rows [["message" "assistant"]]}))

  (it "Model requests a tool call and receives the result"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-has-tools {:headers ["name" "description" "parameters"], :rows [["exec" "Run a command" "{\"command\": \"string\"}"]]})
    (isaac.session.session-steps/responses-queued {:headers ["tool_call" "arguments"], :rows [["exec" "{\"command\": \"echo hi\"}"]]})
    (isaac.session.session-steps/user-sends-on-session "Run echo hi" "llm-chat")
    (isaac.session.session-steps/session-transcript-matching "llm-chat" {:headers ["type" "message.role" "message.content[0].type" "message.content[0].name"], :rows [["message" "assistant" "toolCall" "exec"] ["message" "toolResult" "" ""] ["message" "assistant" "" ""]]})
    (isaac.session.session-steps/session-transcript-matching "llm-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "toolResult" "#\"hi\""]]}))

  (it "Tool calls dispatch when provider lacks streaming tool support"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.llm.providers-steps/provider-configured "grover" {:headers ["key" "value" "#comment"], :rows [["stream-supports-tool-calls" "false" "models real ollama/qwen — its stream endpoint doesn't return structured tool_calls"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-has-tools {:headers ["name" "description" "parameters"], :rows [["exec" "Run a command" "{\"command\": \"string\"}"]]})
    (isaac.session.session-steps/responses-queued {:headers ["tool_call" "arguments"], :rows [["exec" "{\"command\": \"echo hi\"}"]]})
    (isaac.session.session-steps/user-sends-on-session "Run echo hi" "llm-chat")
    (isaac.session.session-steps/session-transcript-matching "llm-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "toolResult" "#\"hi\""]]}))

  (it "LLM errors are recorded in the session transcript"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "llama3.2:latest"] ["provider" "ollama"] ["context-window" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.llm.providers-steps/provider-configured "ollama" {:headers ["key" "value"], :rows [["base-url" "http://localhost:99999"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-error"]]})
    (isaac.session.session-steps/user-sends-on-session "Hello" "llm-error")
    (isaac.session.session-steps/session-transcript-matching "llm-error" {:headers ["type" "error"], :rows [["error" ":connection-refused"]]}))

  (it "tools-using turns stream text deltas as they arrive"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-tool-allow "main" "grep")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["stream-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text-stream" "[\"chunkA\",\"chunkB\",\"chunkC\"]" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "stream-test")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "text"], :rows [["text-chunk" "chunkA"] ["text-chunk" "chunkB"] ["text-chunk" "chunkC"]]}))

  (it "tool loop produces a real final message when the LLM keeps requesting tools"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["llm-chat"]]})
    (isaac.session.session-steps/tool-loop-max-is 1)
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-tool-allow "main" "grep")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["loopy"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "model" "content"], :rows [["tool_call" "grep" "{}" "echo" ""] ["tool_call" "grep" "{}" "echo" ""] ["text" "" "" "echo" "I checked grep once, hit the limit, and need to continue manually."]]})
    (isaac.session.session-steps/user-sends-on-session "poke around" "loopy")
    (isaac.session.session-steps/session-transcript-matching "loopy" {:headers ["#index" "type" "message.role" "message.content"], :rows [["-1" "message" "assistant" "I checked grep once, hit the limit, and need to continue manually."]]})))
