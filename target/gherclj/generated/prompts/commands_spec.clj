(ns prompts.commands-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Prompt-template command expansion"

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

  (it "a prompt-template command expands into the turn input with params substituted"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/commands/tend.md" "---\ntype: command\ndescription: Tend a specimen in the greenhouse\nparams: [specimen]\n---\nTend the {{specimen}} in the greenhouse. Check the soil moisture first.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Tending to it, Captain." "grover"]]})
    (isaac.session.session-steps/user-sends-on-session "/tend dilithium-orchid" "greenhouse")
    (isaac.session.session-steps/session-transcript-matching "greenhouse" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "#\"(?s)Tend the dilithium-orchid in the greenhouse\\. Check the soil moisture first\\.\""]]}))

  (it "declared skills are inlined into the expanded command"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/hieronymus.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Hieronymus, the ship's botanist."]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/greenhouse-protocol/SKILL.md" "---\ntype: skill\ndescription: Use when tending specimens\n---\nAlways quarantine new specimens for one cycle before integration.")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/commands/tend.md" "---\ntype: command\ndescription: Tend a specimen in the greenhouse\nparams: [specimen]\nskills: [greenhouse-protocol]\n---\nTend the {{specimen}} in the greenhouse.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["greenhouse" "hieronymus"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Quarantining it, Captain." "grover"]]})
    (isaac.session.session-steps/user-sends-on-session "/tend dilithium-orchid" "greenhouse")
    (isaac.session.session-steps/session-transcript-matching "greenhouse" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "#\"(?s).*Tend the dilithium-orchid in the greenhouse.*quarantine new specimens.*\""]]}))

  (it "an unknown command from an interactive caller is rejected"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/crew/hieronymus.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/prune dilithium-orchid" on session "greenhouse"
    ;; then the reply contains "unknown command: prune"
    (pending "not yet implemented"))

  (it "a hail carrying an unknown command is delivered, not rejected"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/crew/hieronymus.edn" exists with:
    ;; given the following sessions exist:
    ;; given the following model responses are queued:
    ;; given the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
    ;; when the hail delivery worker ticks
    ;; when the turn ends on session "greenhouse"
    ;; then session "greenhouse" has transcript matching:
    ;; then the isaac file "hail/deliveries/delivery-1.edn" does not exist
    ;; given the EDN isaac file "hail/delivered/delivery-1.edn" contains:
    (pending "not yet implemented")))
