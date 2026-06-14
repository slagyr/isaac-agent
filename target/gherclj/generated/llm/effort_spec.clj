(ns llm.effort-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Universal effort knob"

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

  (it "Default effort is 7 when nothing is configured"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "7"]]}))

  (it "Provider-level effort overrides the default"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["effort" "3"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "3"]]}))

  (it "Model-level effort overrides provider-level"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["effort" "3"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "5"]]}))

  (it "Crew-level effort overrides model-level and provider-level"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["effort" "3"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"] ["effort" "5"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac"] ["effort" "9"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "9"]]}))

  (it "Session effort overrides all config tiers"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["effort" "3"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac"] ["effort" "9"]]})
    (isaac.session.session-steps/session-has-effort "effort-s" "2")
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "2"]]}))

  (it "defaults.effort overrides the built-in default of 7"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/isaac.edn" {:headers ["path" "value"], :rows [["defaults.effort" "4"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "4"]]}))

  (it "allows-effort false omits effort from the request"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"] ["allows-effort" "false"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-has-no-effort))

  (it "Effort 0 is passed to the request"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["effort-s"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content"], :rows [["text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac"] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "effort-s")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["effort" "0"]]})))
