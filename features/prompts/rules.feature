Feature: Rules (always-on prepared prompts)
  A rule is a prepared prompt with always-on activation: unlike a skill (whose
  description sits in the system prompt and whose body loads on demand), a
  rule's full body is injected into the cached system prompt every turn, no
  invocation. Rules are project-scoped and additive — global and project rules
  all apply. They are rendered in a stable (sorted) order so an unchanged rule
  set never busts the prompt cache per turn.

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

  Scenario: a rule's body is always present in the cached system prompt
    Given the isaac file "prompts/rules/greenhouse-standards.md" exists with:
      """
      ---
      type: rule
      description: Greenhouse operating standards
      ---
      Never vent atmosphere while specimens are unsealed.
      """
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    Then the prompt "Status?" on session "greenhouse" matches:
      | key                          | value                                                        | #comment                          |
      | system[0].text               | #"(?s).*Never vent atmosphere while specimens are unsealed.*" | rule BODY always in system prompt |
      | system[0].cache_control.type | ephemeral                                                    | cached, project-stable            |

  Scenario: global and project rules both apply
    Given the isaac file "prompts/rules/ship-wide.md" exists with:
      """
      ---
      type: rule
      description: Ship-wide standing orders
      ---
      Address the Captain formally.
      """
    And the following sessions exist:
      | name       | crew       | cwd           |
      | greenhouse | hieronymus | target/garden |
    And the file "target/garden/.isaac/prompts/rules/greenhouse.md" contains:
      """
      ---
      type: rule
      description: Greenhouse standing orders
      ---
      Never vent atmosphere while specimens are unsealed.
      """
    Then the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                                          | #comment                   |
      | system[0].text | #"(?s).*Address the Captain formally.*Never vent atmosphere.*" | both rules applied (union) |

  Scenario: rules are rendered in a stable (sorted) order
    Given the isaac file "prompts/rules/airlock.md" exists with:
      """
      ---
      type: rule
      description: Airlock discipline
      ---
      Seal both doors before cycling.
      """
    And the isaac file "prompts/rules/quarantine.md" exists with:
      """
      ---
      type: rule
      description: Quarantine discipline
      ---
      Isolate new specimens for one cycle.
      """
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    Then the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                             | #comment                                           |
      | system[0].text | #"(?s).*Seal both doors.*Isolate new specimens.*" | sorted by name (airlock < quarantine) -> cache-safe |
