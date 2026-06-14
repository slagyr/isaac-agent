(ns bridge.tool-visibility-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Crew tools reach every comm path"

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

  (it "prompt command offers the crew's configured tools"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read,write,exec")
    (isaac.foundation.cli-steps/isaac-run "prompt hi")
    (isaac.session.session-steps/prompt-has-tools {:headers ["name"], :rows [["read"] ["write"] ["exec"]]}))

  (it "a crew with no :tools section still gets zero tools over every comm"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "read,write,exec")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Marvin. Paranoid droid."]]})
    (isaac.foundation.cli-steps/isaac-run "prompt hi")
    (isaac.session.session-steps/prompt-has-tool-count "0")))
