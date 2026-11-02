@wip
Feature: Subagent control
  A parent sees and controls the children it spawned with agent__spawn
  (features/tool/subagents.feature). agent__list reports each child's
  handle, crew, model, state (queued, running, done, cancelled, failed) and
  cycles. agent__cancel stops a child; the parent still gets a result turn,
  marked cancelled. A crew's :max-subagents (default 4) caps how many
  children a parent has unfinished at once. Each child logs
  :subagent/started and :subagent/ended.

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

  Scenario: agent__list shows the parent's children and their state (isaac-urog)
    Given the following model responses are queued:
      | type | content         | model |
      | text | Three moorings. | echo  |
      | text | Noted.          | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
    And the tool "agent__list" is called
    Then the tool result JSON has:
      | path               | value  |
      | children[0].handle | #*     |
      | children[0].crew   | main   |
      | children[0].model  | grover |
      | children[0].state  | queued |
    When the turn queue ticks at "2026-03-01T18:00:00"
    And the tool "agent__list" is called
    Then the tool result JSON has:
      | path               | value |
      | children[0].state  | done  |
      | children[0].cycles | 1     |

  Scenario: agent__cancel stops a child and the parent hears it was cancelled (isaac-urog)
    Given the following model responses are queued:
      | type | content                | model |
      | text | Noted, scout recalled. | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
    Then the tool result JSON has:
      | path   | value        |
      | handle | #"\S+":scout |
    When the tool "agent__cancel" is called with:
      | handle | #scout |
    Then the tool result is not an error
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "bridge" has transcript matching:
      | #index | type    | message.role | message.content        |
      | -2     | message | user         | #"(?s).*cancelled.*"   |
      | -1     | message | assistant    | Noted, scout recalled. |
    When the tool "agent__list" is called
    Then the tool result JSON has:
      | path              | value     |
      | children[0].state | cancelled |

  Scenario: spawning past the per-parent limit fails until a child finishes (isaac-urog)
    Given config:
      | key                         | value |
      | defaults.crew.max-subagents | 2     |
    And the following model responses are queued:
      | type | content | model |
      | text | Done.   | echo  |
      | text | Done.   | echo  |
      | text | Noted.  | echo  |
      | text | Noted.  | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
    And the tool "agent__spawn" is called with:
      | prompt | Count the buoys. |
    And the tool "agent__spawn" is called with:
      | prompt | Count the gulls. |
    Then the tool result is an error
    And the tool result contains "subagent limit reached (2 running)"
    And the session count is 3
    When the turn queue ticks at "2026-03-01T18:00:00"
    And the tool "agent__spawn" is called with:
      | prompt | Count the gulls. |
    Then the tool result is not an error

  Scenario: a child's start and end are logged with its parent, crew, model and outcome (isaac-urog)
    Given the following model responses are queued:
      | type | content         | model  |
      | text | Three moorings. | squawk |
      | text | Noted.          | echo   |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
      | model  | parrot              |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the log has entries matching:
      | level | event             | parent | session | crew | model  | cycles | tokens | outcome |
      | :info | :subagent/started | bridge | #*      | main | parrot |        |        |         |
      | :info | :subagent/ended   | bridge | #*      | main | parrot | 1      | #*     | ok      |
