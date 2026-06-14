(ns llm.api.ollama.api-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Ollama API surface"

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

  (it "Bool mode — effort 0 sends think:false"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "false"]]}))

  (it "Bool mode — effort 1 sends think:true"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "1"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "true"]]}))

  (it "Bool mode — effort 7 sends think:true"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "7"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "true"]]}))

  (it "Levels mode — effort 0 omits think field"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"] ["think-mode" "levels"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "0"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/provider-request-lacks-path "body.think"))

  (it "Levels mode — effort 2 sends think:low"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"] ["think-mode" "levels"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "2"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "low"]]}))

  (it "Levels mode — effort 5 sends think:medium"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"] ["think-mode" "levels"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "medium"]]}))

  (it "Levels mode — effort 9 sends think:high"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["qwen-house" "qwerty"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["qwen3" "text" "ok"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["provider" "grover:ollama"] ["context-window" "32768"] ["think-mode" "levels"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/qwerty.edn" {:headers ["path" "value"], :rows [["model" "qwen3"] ["soul" "Thinks in QWEN."] ["effort" "9"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "qwen-house")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.think" "high"]]})))
