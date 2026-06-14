Feature: Compaction with memory flush
  When a session approaches its context window, Isaac runs a single
  combined turn: the agent calls memory_write for anything durable,
  then produces a summary as its final text output. Only memory
  tools are available during this turn. The summary becomes the
  session's compaction entry.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 100 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the current time is "2026-04-21T10:00:00Z"

  Scenario: compaction-turn memory_write calls persist and the summary is produced
    Given the following sessions exist:
      | name         | total-tokens |
      | mundane-chat | 95          |
    And session "mundane-chat" has transcript:
      | type    | message.role | message.content                    |
      | message | user         | I take tea with two sugars.        |
      | message | assistant    | Noted — tea with two sugars it is. |
    And the following model responses are queued:
      | type      | tool         | arguments                                         | content                           | model      |
      | tool_call | memory_write | {"content": "User prefers tea with two sugars."} |                                   | test-model |
      | text      |              |                                                   | Discussion about tea preferences. | test-model |
      | text      |              |                                                   | Here is my response.              | test-model |
    When the user sends "hello" on session "mundane-chat"
    Then the file "crew/main/memory/2026-04-21.md" contains "User prefers tea with two sugars."
    And session "mundane-chat" has transcript matching:
      | type       | message.role | message.content      | summary                           |
      | compaction |              |                      | Discussion about tea preferences. |
      | message    | user         | hello                |                                   |
      | message    | assistant    | Here is my response. |                                   |

  Scenario: compaction turn with no memory calls still produces a summary
    Given the following sessions exist:
      | name      | total-tokens |
      | quiet-day | 95          |
    And session "quiet-day" has transcript:
      | type    | message.role | message.content                  |
      | message | user         | What is the weather doing?       |
      | message | assistant    | Sunny, 72 degrees, light breeze. |
    And the following model responses are queued:
      | type | content                           | model      |
      | text | Quick chat about today's weather. | test-model |
      | text | Here is my response.              | test-model |
    When the user sends "hello" on session "quiet-day"
    Then session "quiet-day" has transcript matching:
      | type       | summary                           |
      | compaction | Quick chat about today's weather. |
