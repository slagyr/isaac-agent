(ns prompts.skill-resources-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Skill bundled resources via load_skill"

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

  (it "load_skill fetches a bundled resource file from the skill's directory"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nFollow the checklist in checklist.md.")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/checklist.md" "1. Check soil moisture.\n2. Quarantine new specimens for one cycle.")
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content" "tool_call" "arguments"], :rows [["grover" "tool_call" "" "load_skill" "{\"name\":\"greenhouse-protocol\",\"resource\":\"checklist.md\"}"] ["grover" "text" "Done." "" ""]]})
    (isaac.session.session-steps/user-sends-on-session "Run the greenhouse checklist." "greenhouse")
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["line"], :rows [["1. Check soil moisture."] ["2. Quarantine new specimens for one cycle."]]}))

  (it "a resource path that escapes the skill directory is rejected"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["provider" "grover:anthropic"] ["context-window" "200000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "claude"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nFollow the checklist.")
    (isaac.session.session-steps/responses-queued {:headers ["model" "type" "content" "tool_call" "arguments"], :rows [["grover" "tool_call" "" "load_skill" "{\"name\":\"greenhouse-protocol\",\"resource\":\"../../auth.json\"}"] ["grover" "text" "Understood." "" ""]]})
    (isaac.session.session-steps/user-sends-on-session "Load the secrets." "greenhouse")
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["line"], :rows [["#\"(?s).*resource path escapes the skill directory.*\""]]})))
