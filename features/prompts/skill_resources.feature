Feature: Skill bundled resources via load_skill
  A skill packaged as a directory (<name>/SKILL.md) may bundle reference files.
  load_skill, shaped load_skill(name, [resource]), serves them: with a resource
  arg it returns that bundled file's contents. It is a SCOPED reader of the
  skill's own directory under config/ (which general crew fs-bounds forbid), so
  it must confine to that directory — a resource path that escapes is rejected.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path           | value            |
      | model          | claude           |
      | provider       | grover:anthropic |
      | context-window | 200000           |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | claude                                   |
      | soul  | You are Hieronymus, the ship's botanist. |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |

  Scenario: load_skill fetches a bundled resource file from the skill's directory
    Given the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Follow the checklist in checklist.md.
      """
    And the isaac file "config/skills/greenhouse-protocol/checklist.md" exists with:
      """
      1. Check soil moisture.
      2. Quarantine new specimens for one cycle.
      """
    And the following model responses are queued:
      | model  | type      | content | tool_call  | arguments                                                |
      | grover | tool_call |         | load_skill | {"name":"greenhouse-protocol","resource":"checklist.md"} |
      | grover | text      | Done.   |            |                                                          |
    When the user sends "Run the greenhouse checklist." on session "greenhouse"
    Then the tool result lines match:
      | line                                       |
      | 1. Check soil moisture.                    |
      | 2. Quarantine new specimens for one cycle. |

  Scenario: a resource path that escapes the skill directory is rejected
    Given the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Follow the checklist.
      """
    And the following model responses are queued:
      | model  | type      | content     | tool_call  | arguments                                                   |
      | grover | tool_call |             | load_skill | {"name":"greenhouse-protocol","resource":"../../auth.json"} |
      | grover | text      | Understood. |            |                                                             |
    When the user sends "Load the secrets." on session "greenhouse"
    Then the tool result lines match:
      | line                                                 |
      | #"(?s).*resource path escapes the skill directory.*" |
