(ns session.crew-cwd-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Crew-level default cwd for new sessions"

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

  (it "Crew :cwd seeds the new session's cwd"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "1000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Brain."] ["cwd" "/lab/world-domination"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Try to take over the world. Narf!" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Are you pondering what I'm pondering?" "scheme-prep")
    (isaac.session.session-steps/session-matches "scheme-prep" {:headers ["key" "value"], :rows [["cwd" "/lab/world-domination"]]}))

  (it "Explicit session :cwd overrides crew :cwd"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Brain."] ["cwd" "/lab/world-domination"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "cwd"], :rows [["rubber-pants" "/acme/haberdashery"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Narf!" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Where are we going?" "rubber-pants")
    (isaac.session.session-steps/session-matches "rubber-pants" {:headers ["key" "value"], :rows [["cwd" "/acme/haberdashery"]]}))

  (it "Crew :cwd must be an absolute path"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:crew {:pinky {:cwd \"cheese-helmets\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.pinky.cwd" "must be an absolute path.*"]]})))
