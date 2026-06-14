(ns llm.api.chat-completions.api-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "OpenAI Completions API — effort wire translation"

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

  (it "Effort 7 maps to reasoning_effort high"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."] ["effort" "7"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning_effort" "high"]]}))

  (it "Effort 10 maps to reasoning_effort high"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."] ["effort" "10"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning_effort" "high"]]}))

  (it "Effort 5 maps to reasoning_effort medium"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning_effort" "medium"]]}))

  (it "Effort 2 maps to reasoning_effort low"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."] ["effort" "2"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning_effort" "low"]]}))

  (it "Effort 0 omits reasoning_effort"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.reasoning_effort"))

  (it "allows-effort false omits reasoning_effort"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "g5"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["desk" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/g5.edn" {:headers ["path" "value"], :rows [["model" "gpt-5"] ["provider" "grover:openai"] ["context-window" "128000"] ["allows-effort" "false"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "desk")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.reasoning_effort")))
