(ns session.identity-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]))

(describe "Session Identity"

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

  (it "creating a session with a chosen name"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["friday-debug"]]})
    (isaac.session.session-steps/session-exists-quoted "friday-debug")
    (isaac.session.session-steps/session-file-is-quoted "sessions/friday-debug.jsonl"))

  (it "session name is converted to a valid filename"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["Friday Debug!"]]})
    (isaac.session.session-steps/session-exists-quoted "Friday Debug!")
    (isaac.session.session-steps/session-file-is-quoted "sessions/friday-debug.jsonl"))

  (it "session with no name gets an auto-generated name"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/session-created-randomly)
    (isaac.session.session-steps/session-count-is "1"))

  (it "session has a name and an id"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["Friday Debug!"]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "name" "file"], :rows [["friday-debug" "Friday Debug!" "sessions/friday-debug.jsonl"]]}))

  (it "session id must be unique"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["friday-debug"]]})
    (isaac.session.session-steps/session-created-with-name-quoted "Friday Debug")
    (isaac.session.session-steps/error-contains-quoted "session already exists: friday-debug"))

  (it "most recent session is found by updated time"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "updated-at"], :rows [["old-one" "2026-04-10T10:00:00"] ["new-one" "2026-04-12T15:00:00"]]})
    (isaac.session.session-steps/most-recent-session-is "\"new-one\""))

  (it "session uses default crew member when none specified"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["friday-debug"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "friday-debug")
    (isaac.session.session-steps/session-transcript-matching "friday-debug" {:headers ["type" "message.role" "message.crew"], :rows [["message" "assistant" "main"]]}))

  (it "transcript records crew and model per message"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["friday-debug"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "friday-debug")
    (isaac.session.session-steps/session-transcript-matching "friday-debug" {:headers ["type" "message.role" "message.crew" "message.model"], :rows [["message" "user" "" ""] ["message" "assistant" "main" "echo"]]})))
