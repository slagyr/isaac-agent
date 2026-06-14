(ns session.concurrency-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Crew and session concurrency"

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

  (it "a real turn marks its session in-flight, then clears it"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["s1"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "wait"], :rows [["text" "ok" "echo" "true"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "s1")
    (isaac.session.session-steps/session-in-flight-status "s1" "true")
    (isaac.session.session-steps/turn-ends-on-session "s1")
    (isaac.session.session-steps/session-in-flight-status "s1" "false"))

  (it "a second dispatch on the same in-flight session is refused"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["s1"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "wait"], :rows [["text" "first" "echo" "true"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "s1")
    (isaac.session.session-steps/user-sends-on-session "go again" "s1")
    (isaac.session.session-steps/dispatch-refused-with-reason "session-in-flight")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session"], :rows [["warn" ":dispatch/refused" "s1"]]})
    (isaac.session.session-steps/turn-ends-on-session "s1"))

  (it "in-flight clears when a turn errors"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["s1"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "boom" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "s1")
    (isaac.session.session-steps/session-in-flight-status "s1" "false")))
