(ns prompts.rules-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Rules (always-on prepared prompts)"

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

  (it "a rule's body is always present in the cached system prompt"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/rules/greenhouse-standards.md" "---\ntype: rule\ndescription: Greenhouse operating standards\n---\nNever vent atmosphere while specimens are unsealed.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "greenhouse" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*Never vent atmosphere while specimens are unsealed.*\"" "rule BODY always in system prompt"] ["system[0].cache_control.type" "ephemeral" "cached, project-stable"]]}))

  (it "global and project rules both apply"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/rules/ship-wide.md" "---\ntype: rule\ndescription: Ship-wide standing orders\n---\nAddress the Captain formally.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["greenhouse" "hieronymus" "target/garden"]]})
    (isaac.foundation.fs-steps/file-with-content "target/garden/.isaac/rules/greenhouse.md" "---\ntype: rule\ndescription: Greenhouse standing orders\n---\nNever vent atmosphere while specimens are unsealed.")
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "greenhouse" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*Address the Captain formally.*Never vent atmosphere.*\"" "both rules applied (union)"]]}))

  (it "rules are rendered in a stable (sorted) order"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/rules/airlock.md" "---\ntype: rule\ndescription: Airlock discipline\n---\nSeal both doors before cycling.")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/rules/quarantine.md" "---\ntype: rule\ndescription: Quarantine discipline\n---\nIsolate new specimens for one cycle.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "greenhouse" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*Seal both doors.*Isolate new specimens.*\"" "sorted by name (airlock < quarantine) -> cache-safe"]]})))
