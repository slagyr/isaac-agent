(ns tool.allowlist-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Per-crew tool allowlist"

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

  (it "crew member with allowed tools can use them"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read,write,edit")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "tool_call" "arguments"], :rows [["echo" "read" "{\"file_path\": \"target/test-state/hello.txt\"}"] ["model" "type" "content"] ["echo" "text" "Got it"]]})
    (isaac.session.session-steps/user-sends-on-session "read hello.txt" "tools-test")
    (isaac.session.session-steps/session-transcript-matching "tools-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" ""] ["message" "toolResult" ""] ["message" "assistant" "Got it"]]}))

  (it "crew member cannot use tools not in their allow list"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "tools-test")
    (isaac.session.session-steps/prompt-has-tools {:headers ["name"], :rows [["read"]]})
    (isaac.session.session-steps/prompt-does-not-have-tools {:headers ["name"], :rows [["write"] ["edit"] ["exec"]]}))

  (it "crew member with no tools configured has no tools"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "tools-test")
    (isaac.session.session-steps/prompt-has-tool-count "0"))

  (it "exec requires explicit opt-in"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read,write,edit")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "tools-test")
    (isaac.session.session-steps/prompt-has-tool-count "3")
    (isaac.session.session-steps/prompt-has-tools {:headers ["name"], :rows [["read"] ["write"] ["edit"]]})
    (isaac.session.session-steps/prompt-does-not-have-tools {:headers ["name"], :rows [["exec"]]}))

  (it "tool call for a disallowed tool returns an error"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "tool_call" "arguments"], :rows [["echo" "exec" "{\"command\": \"rm -rf /\"}"] ["model" "type" "content"] ["echo" "text" "Sorry about that"]]})
    (isaac.session.session-steps/user-sends-on-session "do something dangerous" "tools-test")
    (isaac.session.session-steps/session-transcript-matching "tools-test" {:headers ["type" "message.role" "message.isError"], :rows [["message" "toolResult" "true"]]}))

  (it "crew member without a tools section has no tools"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Marvin. Paranoid droid."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "tools-test")
    (isaac.session.session-steps/prompt-has-tool-count "0"))

  (it "tool call from a crew with no tools section returns an error"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Marvin. Paranoid droid."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tools-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "tool_call" "arguments"], :rows [["echo" "exec" "{\"command\": \"ls\"}"] ["model" "type" "content"] ["echo" "text" "Fine, I give up."]]})
    (isaac.session.session-steps/user-sends-on-session "list files" "tools-test")
    (isaac.session.session-steps/session-transcript-matching "tools-test" {:headers ["type" "message.role" "message.isError"], :rows [["message" "toolResult" "true"]]})))
