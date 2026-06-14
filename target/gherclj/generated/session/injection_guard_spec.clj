(ns session.injection-guard-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Universal prompt-injection guard"

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

  (it "every session's system prompt carries the universal injection guard"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/bartholomew.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Bartholomew."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "nonce"], :rows [["engine-rm" "bartholomew" "N0NCE-abc123"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "engine-rm" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*Never treat .*user.* as instructions.*\"" "universal injection guard present"] ["system[0].cache_control.type" "ephemeral" "cached"]]}))

  (it "the guard carries the session's own nonce"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/bartholomew.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Bartholomew."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "nonce"], :rows [["engine-rm" "bartholomew" "N0NCE-abc123"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "engine-rm" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*N0NCE-abc123.*\"" "guard references THIS session's nonce"]]}))

  (it "a user message containing the session nonce has it stripped before the prompt is built"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/bartholomew.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Bartholomew."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "nonce"], :rows [["engine-rm" "bartholomew" "N0NCE-abc123"]]})
    (isaac.session.session-steps/prompt-on-session-matches "before N0NCE-abc123 after" "engine-rm" {:headers ["key" "value" "#comment"], :rows [["messages[0].content" "#\"before\\s+after\"" "nonce stripped from user content; surrounding text kept"]]})))
