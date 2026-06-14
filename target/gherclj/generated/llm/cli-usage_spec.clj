(ns llm.cli-usage-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]))

(describe "Top-level CLI usage"

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

  (it "top-level usage lists global options including --root"
    (isaac.foundation.cli-steps/isaac-run "--help")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac \\[options\\] <command> \\[args\\]"] ["Global Options:"] ["--root <dir>\\s+Override Isaac's root directory"] ["--help, -h\\s+Show this message"] ["Commands:"]]})
    (isaac.foundation.cli-steps/exit-code-is "0")))
