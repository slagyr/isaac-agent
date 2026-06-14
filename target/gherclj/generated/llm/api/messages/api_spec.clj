(ns llm.api.messages.api-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Anthropic Messages API surface"

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

  (it "Effort 0 omits the thinking block entirely"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.thinking"))

  (it "Effort 1 maps to 10% of budget-max (3200 tokens)"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "1"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.thinking.type" "enabled"] ["body.thinking.budget_tokens" "3200"]]}))

  (it "Effort 5 maps to 50% of budget-max (16000 tokens)"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.thinking.type" "enabled"] ["body.thinking.budget_tokens" "16000"]]}))

  (it "Effort 10 maps to 100% of budget-max (32000 tokens)"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "10"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.thinking.type" "enabled"] ["body.thinking.budget_tokens" "32000"]]}))

  (it "thinking-budget-max 64000 scales 50% effort to 32000 tokens"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "64000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.thinking.budget_tokens" "32000"]]}))

  (it "thinking-budget-max 64000 scales effort 10 to 64000 tokens"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "32000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["thinking" "thinker"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["claude-sonnet-4-5" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude-sonnet-4-5"] ["provider" "grover:anthropic"] ["context-window" "200000"] ["thinking-budget-max" "64000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/thinker.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "Think hard."] ["effort" "10"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "thinking")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.thinking.budget_tokens" "64000"]]})))
