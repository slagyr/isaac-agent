(ns bridge.cli-prompt-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Prompt single-turn command"

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

  (it "prompt command runs one turn and exits"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Four, I think" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'What is 2+2?'")
    (isaac.foundation.cli-steps/stdout-contains "Four, I think")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "Default session is prompt-default"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.session.session-steps/sessions-match {:headers ["id"], :rows [["prompt-default"]]})
    (isaac.session.session-steps/session-transcript-matching "prompt-default" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hi"] ["message" "assistant" "Hello"]]}))

  (it "--session resumes an existing session"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prompt-resume"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-resume" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier"] ["message" "assistant" "Earlier reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "New one" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Next' --session prompt-resume")
    (isaac.session.session-steps/session-transcript-matching "prompt-resume" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier"] ["message" "assistant" "Earlier reply"] ["message" "user" "Next"] ["message" "assistant" "New one"]]}))

  (it "Missing --message exits non-zero"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.cli-steps/isaac-run "prompt")
    (isaac.foundation.cli-steps/stdout-contains "required")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "--json outputs structured result"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi' --json")
    (isaac.foundation.cli-steps/stdout-contains "response")
    (isaac.foundation.cli-steps/stdout-contains "Hello")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "Provider error prints a readable message to stderr"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["echo" "error" "context length exceeded"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.foundation.cli-steps/stderr-contains "context length exceeded")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "--crew resolves the crew member's model"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover2.edn" {:headers ["path" "value"], :rows [["model" "echo-alt"] ["provider" "grover"] ["context-window" "16384"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover2"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["echo-alt" "text" "Ahoy"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt --crew ketch -m 'hello'")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.session.session-steps/session-transcript-matching "prompt-default" {:headers ["type" "message.model" "message.crew"], :rows [["message" "echo-alt" "ketch"]]}))

  (it "prompt sets cwd on the created session"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hi" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.session.session-steps/sessions-match {:headers ["id" "cwd"], :rows [["prompt-default" "#*"]]}))

  (it "--crew uses the crew member's soul"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content"], :rows [["echo" "text" "Arr"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt --crew ketch -m 'hello'")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.session.session-steps/sessions-match {:headers ["id" "crew"], :rows [["prompt-default" "ketch"]]}))

  (it "prompt-created sessions load AGENTS.md from cwd"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.session.session-steps/system-prompt-contains "Micah's AI assistant management tools."))

  (it "--resume uses the most recent session"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "updated-at"], :rows [["older" "2026-04-10T10:00:00"] ["recent" "2026-04-12T15:00:00"]]})
    (isaac.session.session-steps/session-has-transcript "recent" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier"] ["message" "assistant" "Earlier reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Continued" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt --resume -m 'Next'")
    (isaac.session.session-steps/session-transcript-matching "recent" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier"] ["message" "assistant" "Earlier reply"] ["message" "user" "Next"] ["message" "assistant" "Continued"]]}))

  (it "--resume with no existing sessions creates one"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt --resume -m 'Hi'")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/stdout-contains "Hello"))

  (it "prompt shows compaction lifecycle on stderr"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["prompt-default" "95"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-default" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "older prompt"] ["message" "assistant" "older reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary so far" "echo"] ["text" "here is the answer" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'next'")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["🥬 compacting"], :rows [["95"] ["✨ compacted"]]})
    (isaac.foundation.cli-steps/stdout-contains "here is the answer")
    (isaac.foundation.cli-steps/stdout-does-not-contain "🥬 compacting"))

  (it "prompt shows tool calls and results on stderr with kind icons"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "grep")
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content" "model"], :rows [["toolCall" "grep" "{\"pattern\":\"lettuce\",\"path\":\"src\"}" "" "echo"] ["text" "" "" "found it" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'find the lettuce'")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["🔍 grep"], :rows [["lettuce"] ["← grep"]]})
    (isaac.foundation.cli-steps/stdout-contains "found it"))

  (it "prompt shows compaction failure inline with the underlying error"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["prompt-default" "95"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-default" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "older prompt"] ["message" "assistant" "older reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "context length exceeded" "echo"] ["text" "here is the answer" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'next'")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["🥬 compacting"], :rows [["🥀 compaction failed"] ["context length exceeded"]]})
    (isaac.foundation.cli-steps/stdout-contains "here is the answer"))

  (it "prompt shows a banner when compaction surrenders after repeated failures"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.consecutive-failures"], :rows [["prompt-default" "95" "4"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-default" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "older prompt"] ["message" "assistant" "older reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "context length exceeded" "echo"] ["text" "here is the answer" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'next'")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["🪦 compaction disabled"], :rows [["too-many-failures"]]})
    (isaac.foundation.cli-steps/stdout-contains "here is the answer")))
