(ns session.origin-framing-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Per-turn origin framing"

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

  (it "a turn with origin + guidance frames the current user turn, not the system prompt"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/bartholomew.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Bartholomew."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "nonce"], :rows [["engine-rm" "bartholomew" "N0NCE-abc123"]]})
    (isaac.session.session-steps/prompt-on-session-with-framing-matches "Seal the leak." "engine-rm" "{:kind :hail :hail-id \"hail-1\"}" "Autonomous hail; the user may not see your reply." {:headers ["key" "value" "#comment"], :rows [["messages[0].content[0].text" "#\"(?s).*N0NCE-abc123.*Autonomous hail.*hail-1.*\"" "nonce-tagged block: guidance + rendered origin metadata"] ["messages[0].content[1].text" "Seal the leak." "user's text, separate block"] ["messages[0].cache_control" "" "origin-bearing turn not cached"]]}))

  (it "the framing block is current-turn-only; history stays clean and cacheable"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/bartholomew.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Bartholomew."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "nonce"], :rows [["engine-rm" "bartholomew" "N0NCE-abc123"]]})
    (isaac.session.session-steps/session-has-transcript "engine-rm" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "First request."] ["message" "assistant" "First reply."]]})
    (isaac.session.session-steps/prompt-on-session-with-framing-matches "Seal the leak." "engine-rm" "{:kind :hail :hail-id \"hail-2\"}" "Autonomous hail; the user may not see your reply." {:headers ["key" "value" "#comment"], :rows [["messages[0].content[0].text" "First request." "historical user turn — clean, no framing block"] ["messages[0].content[0].cache_control.type" "ephemeral" "breakpoint on the origin-free historical message"] ["messages[2].content[0].text" "#\"(?s).*N0NCE-abc123.*Autonomous hail.*hail-2.*\"" "current turn's framing block — the only one"] ["messages[2].content[1].text" "Seal the leak." "current user text"] ["messages[2].content[1].cache_control" "" "current turn not cached"]]})))
