Feature: Prompt tools — list and load skills, commands and rules
  One tool family serves every kind of prompt in the catalog (isaac-3rac).
  prompt__list shows each entry with its kind; prompt__load loads one by name.
  A command loads with the bodies of the skills it declares, exactly as the
  slash-command path renders it. When a skill and a command share a name,
  `kind` picks one. The grant is :prompt/*; prompt__* replaces the former
  skill__list / skill__load (clean cutover).

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
    And the isaac file "prompts/commands/inspect.md" exists with:
      """
      ---
      description: Inspect the greenhouse
      skills: [greenhouse-protocol]
      ---
      Walk every bay and log what you find.
      """

  Scenario: prompt__list shows every prompt with its kind
    Given the following model responses are queued:
      | model  | type      | content | tool_call    | arguments |
      | grover | tool_call |         | prompt__list | {}        |
      | grover | text      | Noted.  |              |           |
    When the user sends "What can you do?" on session "greenhouse"
    Then the tool result lines match:
      | line                                                     |
      | - greenhouse-protocol (skill): Use when tending specimens |
      | - inspect (command): Inspect the greenhouse               |

  Scenario: prompt__load loads a command together with its declared skills
    Given the following model responses are queued:
      | model  | type      | content | tool_call    | arguments          |
      | grover | tool_call |         | prompt__load | {"name":"inspect"} |
      | grover | text      | On it.  |              |                    |
    When the user sends "Run the inspection." on session "greenhouse"
    Then the tool result lines match:
      | line                                                              |
      | Walk every bay and log what you find.                             |
      |                                                                   |
      | Always quarantine new specimens for one cycle before integration. |

  Scenario: kind picks between a skill and a command that share a name
    Given the isaac file "prompts/commands/greenhouse-protocol.md" exists with:
      """
      ---
      description: Run the protocol as a task
      ---
      Run the full greenhouse protocol now.
      """
    And the following model responses are queued:
      | model  | type      | content | tool_call    | arguments                                         |
      | grover | tool_call |         | prompt__load | {"name":"greenhouse-protocol","kind":"command"}   |
      | grover | text      | On it.  |              |                                                   |
    When the user sends "Protocol, please." on session "greenhouse"
    Then the tool result lines match:
      | line                                  |
      | Run the full greenhouse protocol now. |

  Scenario: prompt__load reports an unknown prompt
    Given the following model responses are queued:
      | model  | type      | content | tool_call    | arguments              |
      | grover | tool_call |         | prompt__load | {"name":"no-such-one"} |
      | grover | text      | Hmm.    |              |                        |
    When the user sends "Load it." on session "greenhouse"
    Then the tool result contains "unknown prompt: no-such-one"
