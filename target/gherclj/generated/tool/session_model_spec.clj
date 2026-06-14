(ns tool.session-model-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "session_model tool"

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

  (it "session_model switches the session's model when model arg is provided"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/parrot.edn" {:headers ["path" "value"], :rows [["model" "squawk"] ["provider" ":grover"] ["context_window" "16384"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["status-test" "main"]]})
    (isaac.tool.tools-steps/current-session-is "status-test")
    (isaac.tool.tools-steps/tool-called "session_model" {:headers ["model" "parrot"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-json-has {:headers ["path" "value"], :rows [["model.alias" "parrot"] ["model.upstream" "squawk"]]})
    (isaac.session.session-steps/session-matches "status-test" {:headers ["key" "value"], :rows [["model" "parrot"]]}))

  (it "session_model reverts to the crew's default model when reset is true"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/parrot.edn" {:headers ["path" "value"], :rows [["model" "squawk"] ["provider" ":grover"] ["context_window" "16384"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "model"], :rows [["status-test" "main" "parrot"]]})
    (isaac.tool.tools-steps/current-session-is "status-test")
    (isaac.tool.tools-steps/tool-called "session_model" {:headers ["reset" "true"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-json-has {:headers ["path" "value"], :rows [["model.alias" "grover"] ["model.upstream" "echo"]]})
    (isaac.session.session-steps/session-matches "status-test" {:headers ["key" "value"], :rows [["model" "grover"]]}))

  (it "session_model errors when both model and reset are provided"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["status-test" "main"]]})
    (isaac.tool.tools-steps/current-session-is "status-test")
    (isaac.tool.tools-steps/tool-called "session_model" {:headers ["model" "grover"], :rows [["reset" "true"]]})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "model and reset are mutually exclusive"))

  (it "session_model errors when given an unknown model alias"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["status-test" "main"]]})
    (isaac.tool.tools-steps/current-session-is "status-test")
    (isaac.tool.tools-steps/tool-called "session_model" {:headers ["model" "nonexistent"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "unknown model: nonexistent")))
