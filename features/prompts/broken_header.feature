Feature: A prompt whose header does not parse is left out, not fatal
  Every turn resolves the prompt catalog, which parses the YAML header of
  every skill, command and rule. One unquoted ": " in a description made
  that parse throw, and every turn of every crew on the deployment failed
  with the YAML error until a person quoted the line. It happened on
  2026-09-05 with a hand-written skill and again on 2026-10-14 with one a
  crew wrote for itself through exec/run, so a write-time check cannot
  catch it.
  A file whose header does not parse is left out of the catalog. Isaac
  logs :prompt/header-invalid with the file's path and the parser's error,
  the rest of the catalog loads, and the turn goes on: the skill menu
  keeps every good skill, and slash commands keep working. prompt__list names
  the broken file so a crew can find and fix what it wrote.
  Decision (2026-10-14, Micah): "A bad skill header should not cause the
  app to crash like that."

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
    And the isaac file "prompts/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle before integration.
      """
    And the isaac file "prompts/skills/tide-log.md" exists with:
      """
      ---
      description: How the crew logs the tide end to end: high, low, and slack
      ---
      Write the tide down every watch.
      """
    And the isaac file "prompts/commands/inspect.md" exists with:
      """
      ---
      description: Inspect the greenhouse
      ---
      Walk every bay and log what you find.
      """

  Scenario: the catalog leaves the broken skill out and keeps the rest
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name                | type  | description                |
      | greenhouse-protocol | skill | Use when tending specimens |
    And the log has entries matching:
      | level | event                  | path                     |
      | :warn | :prompt/header-invalid | #"prompts/skills/tide-log.md$" |

  Scenario: a slash command still runs when another prompt's header is broken
    Given the following model responses are queued:
      | model  | type | content           |
      | grover | text | All bays walked.  |
    When the user sends "/inspect" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                       |
      | message | user         | Walk every bay and log what you find. |
      | message | assistant    | All bays walked.                      |
    And the available slash commands include:
      | name    | description            |
      | status  | Show session status    |
      | inspect | Inspect the greenhouse |

  Scenario: prompt__list names the broken file so the crew can fix it
    Given the following model responses are queued:
      | model  | type      | content | tool_call    | arguments |
      | grover | tool_call |         | prompt__list | {}        |
      | grover | text      | Noted.  |              |           |
    When the user sends "What can you do?" on session "greenhouse"
    Then the tool result lines match:
      | line                                                                      |
      | - greenhouse-protocol (skill): Use when tending specimens                  |
      | - inspect (command): Inspect the greenhouse                                |
      | - prompts/skills/tide-log.md (broken): its header does not parse; see the log |
