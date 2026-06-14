(ns bridge.crew-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "/crew Command"

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

  (it "/crew switches the session's active crew member"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/crew/ketch.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/crew ketch" on session "crew-test"
    ;; then the reply contains "switched crew to ketch"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "/crew persists across turns"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["crew-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Ahoy" "echo"] ["text" "Arr" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "/crew ketch" "crew-test")
    (isaac.session.session-steps/user-sends-on-session "hi" "crew-test")
    (isaac.session.session-steps/user-sends-on-session "bye" "crew-test")
    (isaac.session.session-steps/session-transcript-matching "crew-test" {:headers ["type" "message.role" "message.crew"], :rows [["message" "assistant" "ketch"] ["message" "assistant" "ketch"]]}))

  (it "/crew with no argument shows the current crew member"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/crew/ketch.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/crew" on session "crew-test"
    ;; then the reply contains "main is the current crew member"
    (pending "not yet implemented"))

  (it "/crew with unknown name shows an error"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/crew/ketch.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/crew nonexistent" on session "crew-test"
    ;; then the reply contains "unknown crew: nonexistent"
    (pending "not yet implemented"))

  (it "/crew clears the session's pinned :model"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "model"], :rows [["crew-test" "main" "parrot"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Ahoy" "grover"]]})
    (isaac.session.session-steps/user-sends-on-session "/crew ketch" "crew-test")
    (isaac.session.session-steps/user-sends-on-session "hi" "crew-test")
    (isaac.session.session-steps/session-transcript-matching "crew-test" {:headers ["type" "message.role" "message.model"], :rows [["message" "assistant" "grover"]]}))

  (it "/crew does not clear locked session fields like :cwd"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["cwd-test" "main" "/tmp/work"]]})
    (isaac.session.session-steps/user-sends-on-session "/crew ketch" "cwd-test")
    (isaac.session.session-steps/session-matches "cwd-test" {:headers ["key" "value"], :rows [["crew" "ketch"] ["cwd" "/tmp/work"]]})))
