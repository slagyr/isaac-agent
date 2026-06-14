(ns crew.cli-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Crew Command"

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

  (it "crew is registered and has help"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "help crew")
    (isaac.foundation.cli-steps/stdout-contains "Usage: isaac crew")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "crew lists configured crew members with underlined headers"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.foundation.cli-steps/isaac-run "crew")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Name .* Model .* Provider .* Soul"] ["─+.*─+.*─+.*─+"] ["main .* echo"] ["ketch .* echo"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "crew with no configured crew members shows the default"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "crew")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Name .* Model .* Provider .* Soul"] ["─+"] ["main"]]})
    (isaac.foundation.cli-steps/exit-code-is "0")))
