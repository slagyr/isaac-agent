(ns bridge.logging-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Chat and Provider Logging"

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

  (it "Provider failure is logged with chat context"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "llama3.2:latest"] ["provider" "ollama"] ["context-window" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.llm.providers-steps/provider-configured "ollama" {:headers ["key" "value"], :rows [["base-url" "http://localhost:99999"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["log-fail-test"]]})
    (isaac.session.session-steps/user-sends-on-session "Hello" "log-fail-test")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "provider" "session"], :rows [[":error" ":chat/response-failed" "ollama" "log-fail-test"]]}))

  (it "Successful chat response storage is logged at debug"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["log-success-test"]]})
    (isaac.session.session-steps/user-sends-on-session "Hi" "log-success-test")
    (isaac.session.session-steps/session-transcript-matching "log-success-test" {:headers ["type" "message.role"], :rows [["message" "assistant"]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session" "model"], :rows [[":debug" ":session/message-stored" "log-success-test" "echo"]]}))

  (it "Streaming completion is logged at debug"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["log-stream-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hi back" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Hi" "log-stream-test")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session"], :rows [[":debug" ":chat/stream-completed" "log-stream-test"]]}))

  (it "Compaction check and start are logged during chat"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["log-compact-test" "30000" "exceeds 90% of 32768 window"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "echo"] ["text" "Here is my answer" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Continue" "log-compact-test")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session"], :rows [[":debug" ":session/compaction-check" "log-compact-test"] [":info" ":session/compaction-started" "log-compact-test"]]}))

  (it "Compaction entry precedes the triggering user message in transcript"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["log-order-test" "30000" "exceeds 90% of 32768 window"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "echo"] ["text" "Here is my answer" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Continue" "log-order-test")
    (isaac.session.session-steps/session-transcript-matching "log-order-test" {:headers ["#index" "type"], :rows [["1" "compaction"] ["2" "message"]]})))
