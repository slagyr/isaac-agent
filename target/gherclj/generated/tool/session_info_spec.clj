(ns tool.session-info-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "session_info tool"

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

  (it "session_info reports current crew, model, provider, origin, and timing"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.tool.tools-steps/current-time-is "2026-04-28T10:00:00Z")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["status-test" "main" "/work/project"]]})
    (isaac.tool.tools-steps/current-session-is "status-test")
    (isaac.tool.tools-steps/tool-called-no-args "session_info")
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-json-has {:headers ["path" "value"], :rows [["crew" "main"] ["model.alias" "grover"] ["model.upstream" "echo"] ["provider" "grover"] ["session" "status-test"] ["cwd" "/work/project"] ["origin.kind" "cli"] ["created_at" "2026-04-28T10:00:00Z"] ["updated_at" "2026-04-28T10:00:00Z"] ["context.used" "0"] ["context.window" "32768"] ["compactions" "0"]]}))

  (it "session_info reports origin name when the session was started by a webhook"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "origin.kind" "origin.name"], :rows [["hook:lettuce" "main" "webhook" "lettuce"]]})
    (isaac.tool.tools-steps/current-session-is "hook:lettuce")
    (isaac.tool.tools-steps/tool-called-no-args "session_info")
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-json-has {:headers ["path" "value"], :rows [["origin.kind" "webhook"] ["origin.name" "lettuce"]]}))

  (it "session_info resolves model when session :model holds the upstream name"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/providers/hieronymus.edn" "{:api \"grover\" :auth \"none\"}")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/models/lettuce.edn" "{:model \"lettuce-grande\" :provider :hieronymus :context-window 128000}")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "model"], :rows [["salad-bowl" "main" "lettuce-grande"]]})
    (isaac.tool.tools-steps/current-session-is "salad-bowl")
    (isaac.tool.tools-steps/tool-called-no-args "session_info")
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-json-has {:headers ["path" "value"], :rows [["model.alias" "lettuce"] ["model.upstream" "lettuce-grande"] ["provider" "hieronymus"] ["context.window" "128000"]]})))
