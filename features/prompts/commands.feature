Feature: Prompt-template command expansion
  A config-defined command's body is a prompt template. The bridge recognizes
  these (a kind alongside builtin handler commands like /crew) and expands an
  invocation into the turn's user input: substitute {{params}}, inline the
  bodies of any declared skills, and use the expansion as the user message
  (the raw /command is not what's stored or sent). Any producer — a CLI user,
  a hail, cron — goes through the same bridge triage.

  Unknown commands are handled by origin: an interactive caller gets an
  "unknown command" reply (no turn); an autonomous one (a hail) falls through
  and dispatches the raw input so the work is delivered, not dropped.

  Background:
    Given default Grover setup

  Scenario: a prompt-template command expands into the turn input with params substituted
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | grover                                   |
      | soul  | You are Hieronymus, the ship's botanist. |
    And the isaac file "config/commands/tend.md" exists with:
      """
      ---
      type: command
      description: Tend a specimen in the greenhouse
      params: [specimen]
      ---
      Tend the {{specimen}} in the greenhouse. Check the soil moisture first.
      """
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the following model responses are queued:
      | type | content                 | model  |
      | text | Tending to it, Captain. | grover |
    When the user sends "/tend dilithium-orchid" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                                                                     |
      | message | user         | #"(?s)Tend the dilithium-orchid in the greenhouse\. Check the soil moisture first\." |

  Scenario: declared skills are inlined into the expanded command
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | grover                                   |
      | soul  | You are Hieronymus, the ship's botanist. |
    And the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle before integration.
      """
    And the isaac file "config/commands/tend.md" exists with:
      """
      ---
      type: command
      description: Tend a specimen in the greenhouse
      params: [specimen]
      skills: [greenhouse-protocol]
      ---
      Tend the {{specimen}} in the greenhouse.
      """
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the following model responses are queued:
      | type | content                   | model  |
      | text | Quarantining it, Captain. | grover |
    When the user sends "/tend dilithium-orchid" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                                                                  |
      | message | user         | #"(?s).*Tend the dilithium-orchid in the greenhouse.*quarantine new specimens.*" |

  Scenario: an unknown command from an interactive caller is rejected
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    When the user sends "/prune dilithium-orchid" on session "greenhouse"
    Then the reply contains "unknown command: prune"
