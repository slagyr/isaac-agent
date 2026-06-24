Feature: Memory Comm
  The memory comm records chat events in order without any I/O,
  making it the primary test vehicle for chat flow and forcing a
  clean separation between orchestration and presentation.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | memory-chat |

  Scenario: Text response is recorded as a single chunk
    Given the following model responses are queued:
      | type | content       | model |
      | text | Four, I think | echo  |
    When the user sends "What is 2+2?" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event      | text          |
      | turn-start |               |
      | text-chunk | Four, I think |
      | turn-end   |               |

  Scenario: Streaming chunks are recorded into the memory-channel turn result
    Given the following model responses are queued:
      | type | content                           | model |
      | text | ["chunkA" "chunkB" "chunkC"]     | echo  |
    When the user sends "Tell me a story" on session "memory-chat" via memory comm
    Then session "memory-chat" has transcript matching:
      | type    | message.role | message.content        |
      | message | assistant    | chunkAchunkBchunkC     |

  Scenario: Tool calls are recorded as lifecycle events
    Given the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the user sends "Run echo" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event       | tool-name |
      | turn-start  |           |
      | tool-call   | exec      |
      | tool-result | exec      |
      | turn-end    |           |

  Scenario: Compaction triggers during a memory comm turn
    # Compaction keys off the live outbound-prompt estimate (system prompt +
    # transcript), so a small context window plus a non-trivial transcript
    # pushes the estimate past 0.8 * window. head 0.1 keeps the
    # post-compaction estimate under threshold so compaction makes progress.
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path | value |
      | model | echo |
      | provider | grover |
      | context-window | 200 |
    And the following sessions exist:
      | name        | compaction.head |
      | memory-chat | 0.1             |
    And session "memory-chat" has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | Please summarize the work we did on the logging subsystem and the tool loop   |
      | message | assistant    | We discussed logging output sinks, the compaction trigger, and tool dispatch  |
      | message | user         | And what about the retry behavior we changed in the dispatcher last week      |
      | message | assistant    | We made the dispatcher retry idempotent and added backoff between attempts    |
    And the following model responses are queued:
      | type | content                | model |
      | text | Summary of prior chat  | echo  |
      | text | Here is my answer      | echo  |
    When the user sends "Continue" on session "memory-chat" via memory comm
    Then session "memory-chat" has transcript matching:
      | type       |
      | compaction |
