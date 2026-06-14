(ns llm.api.responses.api-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "OpenAI Responses API — effort wire translation"

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

  (it "Effort 7 maps to reasoning high with summary auto"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."] ["effort" "7"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning.effort" "high"] ["body.reasoning.summary" "auto"]]}))

  (it "Effort 5 maps to reasoning medium with summary auto"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning.effort" "medium"] ["body.reasoning.summary" "auto"]]}))

  (it "Effort 2 maps to reasoning low with summary auto"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."] ["effort" "2"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning.effort" "low"] ["body.reasoning.summary" "auto"]]}))

  (it "Effort 0 omits the reasoning block"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "trash-can")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.reasoning"))

  (it "allows-effort false omits the reasoning block"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"] ["allows-effort" "false"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "trash-can")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.reasoning")))
