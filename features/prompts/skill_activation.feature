Feature: Model-driven skill activation
  Skills are advertised to the model by name + description in the cached system
  prompt (progressive disclosure, like Anthropic Agent Skills): cheap, stable
  per project, so it caches within a session. When a skill's description fits
  the task, the model calls load_skill to pull the full body into the turn on
  demand — only activated skills load. The menu is rendered in a stable
  (sorted) order so an unchanged skill set never busts the cache per turn.

  (Large skill sets: descriptions can instead be served via a list_skills tool
  to avoid bloating the cached prompt — a threshold knob, acceptance not
  scenario. load_skill is shaped load_skill(name, [resource]) so isaac-etpt can
  add bundled-resource loading without a redesign.)

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

  Scenario: discovered skills are advertised (name + description) in the cached system prompt
    Given the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle before integration.
      """
    Then the prompt "Tend the orchid." on session "greenhouse" matches:
      | key                          | value                                                      | #comment                       |
      | system[0].text               | #"(?s).*greenhouse-protocol.*Use when tending specimens.*" | skill menu: name + description  |
      | system[0].cache_control.type | ephemeral                                                  | menu sits in the cached prefix |

  Scenario: the model loads a skill body on demand via load_skill
    Given the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle before integration.
      """
    And the following model responses are queued:
      | model  | type      | content | tool_call  | arguments                      |
      | grover | tool_call |         | load_skill | {"name":"greenhouse-protocol"} |
      | grover | text      | On it.  |            |                                |
    When the user sends "Tend the orchid." on session "greenhouse"
    Then the tool result lines match:
      | line                                                              |
      | Always quarantine new specimens for one cycle before integration. |

  Scenario: the skill menu is rendered in a stable (sorted) order
    Given the isaac file "config/skills/aeroponics/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use for soil-free growing
      ---
      Mist the roots on a schedule.
      """
    And the isaac file "config/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle.
      """
    Then the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                      | #comment                                   |
      | system[0].text | #"(?s).*aeroponics.*greenhouse-protocol.*" | sorted by name -> stable order -> cache-safe |
