(ns config.set-unset-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Config set / unset"

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

  (it "scalar set writes a value at a known map path"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.model echo")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.model")
    (isaac.foundation.cli-steps/stdout-contains "echo")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "scalar unset removes a value at a known map path"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "test"]]})
    (isaac.foundation.cli-steps/isaac-run "config unset crew.joe.model")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe")
    (isaac.foundation.cli-steps/stdout-does-not-contain "grover")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config set is idempotent when the value is already present"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.model echo")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.model")
    (isaac.foundation.cli-steps/stdout-contains "echo")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config unset is idempotent when the value is absent"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.foundation.cli-steps/isaac-run "config unset crew.joe.effort")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.model")
    (isaac.foundation.cli-steps/stdout-contains "grover")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config set errors on a path the schema doesn't recognize"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.bogus value")
    (isaac.foundation.cli-steps/stderr-contains "bogus")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "config set errors when the value doesn't match the schema type"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.effort not-a-number")
    (isaac.foundation.cli-steps/stderr-contains "effort")
    (isaac.foundation.cli-steps/exit-code-is "1")))
