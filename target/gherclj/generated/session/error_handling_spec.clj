(ns session.error-handling-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]))

(describe "Error Entry Handling"

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

  (it "error response is stored as type error, not role error"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["error-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "something went wrong" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "error-test")
    (isaac.session.session-steps/session-transcript-matching "error-test" {:headers ["type"], :rows [["error"]]})
    (isaac.session.session-steps/session-has-no-role "error-test" "error"))

  (it "error entries are excluded from the prompt"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["error-test"]]})
    (isaac.session.session-steps/session-has-transcript "error-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hi"]]})
    (isaac.session.session-steps/session-has-error-entry "error-test" "something went wrong")
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "I recovered" "echo"]]})
    (isaac.session.session-steps/prompt-built-for-provider "error-test" "openai")
    (isaac.session.session-steps/prompt-messages-do-not-contain-role "error")))
