(ns bridge.commands-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Bridge Commands"

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

  (it "/status prints session information as markdown table"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; when the user sends "/status" on session "bridge-status"
    ;; then the reply matches:
    ;; and the reply does not contain "SOUL.md"
    (pending "not yet implemented"))

  (it "/status Context shows last-turn size, not cumulative billing"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; given the following sessions exist:
    ;; when the user sends "/status" on session "size-status"
    ;; then the reply matches:
    ;; and the reply does not contain "1,000,000"
    (pending "not yet implemented"))

  (it "/status shows the session's cwd, not the process working directory"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; given the following sessions exist:
    ;; when the user sends "/status" on session "cwd-status"
    ;; then the reply matches:
    (pending "not yet implemented"))

  (it "/status is not sent to the LLM"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "compaction-count"], :rows [["bridge-status" "5000" "5000" "2"]]})
    (isaac.session.session-steps/session-has-transcript "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hello"] ["message" "assistant" "hi"] ["message" "user" "how are you"] ["message" "assistant" "fine"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/user-sends-on-session "/status" "bridge-status")
    (isaac.session.session-steps/session-transcript-matching "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hello"] ["message" "assistant" "hi"] ["message" "user" "how are you"] ["message" "assistant" "fine"]]}))

  (it "unrecognized command produces an error"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; when the user sends "/bogus" on session "bridge-status"
    ;; then the reply contains "unknown command: /bogus"
    (pending "not yet implemented"))

  (it "normal input is not intercepted by the bridge"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "compaction-count"], :rows [["bridge-status" "5000" "5000" "2"]]})
    (isaac.session.session-steps/session-has-transcript "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hello"] ["message" "assistant" "hi"] ["message" "user" "how are you"] ["message" "assistant" "fine"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "I am fine" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "how are you?" "bridge-status")
    (isaac.session.session-steps/session-transcript-matching "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "I am fine"]]}))

  (it "bare /cwd shows the session's current working directory"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; given the following sessions exist:
    ;; when the user sends "/cwd" on session "cwd-test"
    ;; then the reply contains "/work/lettuce"
    (pending "not yet implemented"))

  (it "/cwd <path> sets the session's working directory"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "compaction-count"], :rows [["bridge-status" "5000" "5000" "2"]]})
    (isaac.session.session-steps/session-has-transcript "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hello"] ["message" "assistant" "hi"] ["message" "user" "how are you"] ["message" "assistant" "fine"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.foundation.fs-steps/file-exists-with "fresh-cwd/.keep" "And the following sessions exist:\n| name     | crew | cwd     |\n| cwd-test | main | old-cwd |\nWhen the user sends \"/cwd fresh-cwd\" on session \"cwd-test\"\nThen the reply contains \"fresh-cwd\"\nAnd session \"cwd-test\" matches:\n| key | value             |\n| cwd | #\".*fresh-cwd.*\" |"))

  (it "/cwd rejects a non-existent path"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given session "bridge-status" has transcript:
    ;; given the built-in tools are registered
    ;; given the following sessions exist:
    ;; when the user sends "/cwd no-such-dir" on session "cwd-test"
    ;; then the reply contains "no such directory"
    ;; then session "cwd-test" matches:
    (pending "not yet implemented"))

  (it "/cwd is not sent to the LLM"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "compaction-count"], :rows [["bridge-status" "5000" "5000" "2"]]})
    (isaac.session.session-steps/session-has-transcript "bridge-status" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hello"] ["message" "assistant" "hi"] ["message" "user" "how are you"] ["message" "assistant" "fine"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.foundation.fs-steps/file-exists-with "fresh-cwd/.keep" "And the following sessions exist:\n| name     | crew | cwd     |\n| cwd-test | main | old-cwd |\nAnd session \"cwd-test\" has transcript:\n| type    | message.role | message.content |\n| message | user         | hello           |\n| message | assistant    | hi              |\nWhen the user sends \"/cwd fresh-cwd\" on session \"cwd-test\"\nThen session \"cwd-test\" has transcript matching:\n| type    | message.role | message.content |\n| message | user         | hello           |\n| message | assistant    | hi              |")))
