(ns prompts.skill-activation-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Model-driven skill activation"

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

  (it "discovered skills are advertised (name + description) in the cached system prompt"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nAlways quarantine new specimens for one cycle before integration.")
    (isaac.session.session-steps/prompt-on-session-matches "Tend the orchid." "greenhouse" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*greenhouse-protocol.*Use when tending specimens.*\"" "skill menu: name + description"] ["system[0].cache_control.type" "ephemeral" "menu sits in the cached prefix"]]}))

  (it "the model loads a skill body on demand via load_skill"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nAlways quarantine new specimens for one cycle before integration.")
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content" "tool_call" "arguments"], :rows [["grover" "tool_call" "" "load_skill" "{\"name\":\"greenhouse-protocol\"}"] ["grover" "text" "On it." "" ""]]})
    (isaac.session.session-steps/user-sends-on-session "Tend the orchid." "greenhouse")
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["line"], :rows [["Always quarantine new specimens for one cycle before integration."]]}))

  (it "the skill menu is rendered in a stable (sorted) order"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/aeroponics/SKILL.md" "---\ntype: skill\ndescription: Use for soil-free growing\n---\nMist the roots on a schedule.")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nAlways quarantine new specimens for one cycle.")
    (isaac.session.session-steps/prompt-on-session-matches "Status?" "greenhouse" {:headers ["key" "value" "#comment"], :rows [["system[0].text" "#\"(?s).*aeroponics.*greenhouse-protocol.*\"" "sorted by name -> stable order -> cache-safe"]]})))
