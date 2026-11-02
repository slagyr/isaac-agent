Feature: Subagents
  agent__spawn starts a child turn on its own session and returns a handle
  at once; the parent stays free. When the child's turn ends, its result —
  final message, stats, child session — goes to the parent as a turn with
  origin :subagent (it joins a running turn per isaac-cgz9). The child
  inherits the parent's crew unless the spawn names a crew, model or effort.
  A child cannot spawn children.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/models/parrot.edn" exists with:
      | path           | value   |
      | model          | squawk  |
      | provider       | :grover |
      | context-window | 16384   |
    And the following sessions exist:
      | name   | crew |
      | bridge | main |
    And the current session is "bridge"

  Scenario: spawn returns a handle at once and queues the child on its own session (isaac-p5d3)
    When the tool "agent__spawn" is called with:
      | prompt | Find where the moorings are configured. |
    Then the tool result is not an error
    And the tool result JSON has:
      | path    | value |
      | handle  | #*    |
      | session | #*    |
    And the following sessions match:
      | parent | crew |
      | bridge | main |
    And grover records zero provider requests

  Scenario: the child's result arrives in the idle parent as a subagent turn (isaac-p5d3)
    Given the following model responses are queued:
      | type | content                             | model |
      | text | Moorings live in config/harbor.edn. | echo  |
      | text | Noted, the scout found them.        | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Find where the moorings are configured. |
    And the turn queue ticks at "2026-03-01T18:00:00"
    And the turn queue ticks at "2026-03-01T18:00:10"
    Then session "bridge" has transcript matching:
      | #index | type    | message.role | message.content                                                                      |
      | -2     | message | user         | #"(?s).*Moorings live in config/harbor\.edn\..*model: grover.*cycles: 1.*session: .+" |
      | -1     | message | assistant    | Noted, the scout found them.                                                         |
    And the latest turn on session "bridge" matches:
      | key         | value    |
      | origin.kind | subagent |
      | state       | finished |

  Scenario: the spawn picks the child's model and effort (isaac-p5d3)
    Given the following model responses are queued:
      | type | content         | model  |
      | text | Three moorings. | squawk |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
      | model  | parrot              |
      | effort | 3                   |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the following sessions match:
      | parent | crew | model  |
      | bridge | main | parrot |
    And LLM request 1 matches:
      | key    | value  |
      | model  | squawk |
      | effort | 3      |

  Scenario: the spawn can name another crew; the child takes that crew's settings (isaac-p5d3)
    Given the isaac EDN file "config/crew/scout.edn" exists with:
      | path  | value                                         |
      | model | parrot                                        |
      | soul  | You are a scout. Report findings, never edit. |
    And the following model responses are queued:
      | type | content         | model  |
      | text | Three moorings. | squawk |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
      | crew   | scout               |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the following sessions match:
      | parent | crew  |
      | bridge | scout |
    And the last LLM request matches:
      | key                 | value                     |
      | model               | squawk                    |
      | messages[0].role    | system                    |
      | messages[0].content | #"(?s).*You are a scout.*" |

  Scenario: an unknown model fails the spawn and names the configured models (isaac-p5d3)
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
      | model  | albatross           |
    Then the tool result is an error
    And the tool result contains "unknown model: albatross"
    And the tool result contains "parrot"
    And the session count is 1

  Scenario: a child's tools do not include agent__spawn (isaac-p5d3)
    Given the following model responses are queued:
      | type | content | model |
      | text | Done.   | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Look around. |
    Then the tool result is not an error
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then the last LLM request does not contain "agent__spawn"
