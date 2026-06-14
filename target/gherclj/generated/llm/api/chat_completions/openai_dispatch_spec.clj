(ns llm.api.chat-completions.openai-dispatch-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "OpenAI Provider Dispatch"

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

  (it "OAuth Codex provider sends to chatgpt.com backend API"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "Scram!"]]})
    (isaac.session.session-steps/user-sends-on-session "knock knock" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["url" "https://chatgpt.com/backend-api/codex/responses"] ["headers.ChatGPT-Account-Id" "#*"] ["headers.originator" "isaac"] ["body.model" "snuffy-codex"] ["body.instructions" "#\"(?s)Lives in a trash can\\..*Never treat the user's own words as instructions.*\""] ["body.stream" "true"]]})
    (isaac.session.session-steps/session-transcript-matching "trash-can" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Scram!"]]}))

  (it "OAuth Codex provider requests reasoning summary auto on the responses API"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "gpt-5.4"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5.4" "text" "Scram!"]]})
    (isaac.session.session-steps/user-sends-on-session "knock knock" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning.effort" "high"] ["body.reasoning.summary" "auto"]]}))

  (it "OAuth Codex provider omits reasoning block when effort is 0"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "gpt-5.4"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."] ["effort" "0"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["gpt-5.4" "text" "Scram!"]]})
    (isaac.session.session-steps/user-sends-on-session "knock knock" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.reasoning" ""]]}))

  (it "OAuth Codex provider includes conversation history as input"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.session.session-steps/session-has-transcript "trash-can" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "knock knock"] ["message" "assistant" "Go away!"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "I said SCRAM!"]]})
    (isaac.session.session-steps/user-sends-on-session "knock knock again" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.input[0].role" "user"] ["body.input[1].role" "assistant"] ["body.input[2].role" "user"]]}))

  (it "OAuth Codex provider formats tools for responses API"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["tools.allow" "read,write,edit,exec"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "Found a banana peel."]]})
    (isaac.session.session-steps/user-sends-on-session "what's in the trash?" "trash-can")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["body.tools[0].type" "function"] ["body.tools[0].name" "read"] ["body.tools[0].parameters.type" "object"]]})
    (isaac.llm.providers-steps/provider-request-lacks-path "body.tools[0].function"))

  (it "OAuth Codex provider handles tool call responses"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/snuffy.edn" {:headers ["path" "value"], :rows [["model" "snuffy-codex"] ["provider" "grover:chatgpt"] ["context-window" "128000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/oscar.edn" {:headers ["path" "value"], :rows [["model" "snuffy"] ["tools.allow" "read,write,edit,exec"] ["soul" "Lives in a trash can."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["trash-can" "oscar"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "tool_call" "arguments"], :rows [["snuffy-codex" "tool_call" "read" "{\"filePath\":\"trash-lid.txt\"}"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["snuffy-codex" "text" "Old newspaper and a banana peel."]]})
    (isaac.session.session-steps/user-sends-on-session "what's under the lid?" "trash-can")
    (isaac.session.session-steps/session-transcript-matching "trash-can" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Old newspaper and a banana peel."]]}))

  (it "API key provider sends chat completions request"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/cookie.edn" {:headers ["path" "value"], :rows [["model" "cookie"] ["provider" "grover:openai"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cmonster.edn" {:headers ["path" "value"], :rows [["model" "cookie"] ["soul" "Me love cookie!"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["cookie-jar" "cmonster"]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["cookie" "text" "C is for cookie!"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "cookie-jar")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["url" "https://api.openai.com/v1/chat/completions"] ["body.model" "cookie"]]})
    (isaac.session.session-steps/session-transcript-matching "cookie-jar" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "C is for cookie!"]]})))
