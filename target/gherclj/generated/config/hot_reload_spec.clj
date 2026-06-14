(ns config.hot-reload-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Config hot-reload"

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

  (it "a change under config/ fires a reload and updates the cfg"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["api" "grover"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Keep the Marigold on course."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Storm glass says rough weather ahead."]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path"], :rows [[":info" ":config/reloaded" "crew/cordelia.edn"]]})
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.soul" "Storm glass says rough weather ahead."]]}))

  (it "parse failure on reload is rejected and logged with the error"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["api" "grover"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Keep the Marigold on course."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/crew/cordelia.edn" "{:model :grover\n :soul \"only half a ma")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "reason" "error"], :rows [[":error" ":config/reload-failed" "crew/cordelia.edn" ":parse" "#\"EOF while reading.*\""]]})
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.soul" "Keep the Marigold on course."]]}))

  (it "validation failure on reload is rejected and logged with the errors"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["api" "grover"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Keep the Marigold on course."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "\"\""] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "reason" "error"], :rows [[":error" ":config/reload-failed" "models/grover.edn" ":validation" "models.grover.model is required"]]})
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["models.grover.model" "echo"]]}))

  (it "writes outside config/ do not fire a reload"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/providers/grover.edn" {:headers ["path" "value"], :rows [["api" "grover"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Keep the Marigold on course."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "random.txt" "just some content")
    (isaac.foundation.log-steps/log-entries-dont-match {:headers ["event"], :rows [[":config/reloaded"]]})))
