Feature: Scuttlebutt — mid-turn signals on the Comm surface
  A turn's tool loop runs CYCLES (one LLM call each). Two voices stream
  during a cycle: CHATTER, the model's outward voice, live, not yet
  classifiable — at the cycle boundary it resolves into an ASIDE (tool
  calls followed; said while working) or the REPLY (the answer). RECKONING
  is the inward voice (provider reasoning/thinking), persisted as a
  "reckoning" transcript entry but never re-sent to the model. BULLETINS
  are things the ship did (compaction, recall, episodes, holds) — they
  replace the four on-compaction-* methods. The memory comm is the
  reference implementor: a state-only deftype extended with
  (merge comm/defaults overrides). (isaac-5nxf)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | memory-chat |

  Scenario: chatter resolves into an aside when tools follow, and into the reply when they don't
    Given the built-in tools are registered
    And the following model responses are queued:
      | type      | content                    | tool_call | arguments              | model |
      | text      | Let me check that for you. | exec__run | {"command": "echo hi"} | echo  |
      | text      | All done: hi               |           |                        | echo  |
    When the user sends "Run echo and report" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event       | cycle | text                       | tool-name | outcome |
      | turn-start  |       |                            |           |         |
      | cycle-start | 1     |                            |           |         |
      | chatter     | 1     | Let me check that for you. |           |         |
      | tool-call   |       |                            | exec__run |         |
      | cycle-end   | 1     |                            |           | aside   |
      | aside       | 1     | Let me check that for you. |           |         |
      | tool-result |       |                            | exec__run |         |
      | cycle-start | 2     |                            |           |         |
      | chatter     | 2     | All done: hi               |           |         |
      | cycle-end   | 2     |                            |           | reply   |
      | reply       |       | All done: hi               |           |         |
      | turn-end    |       |                            |           |         |

  Scenario: reckoning is streamed, persisted, and excluded from the next prompt
    Given the following model responses are queued:
      | type      | content              | model |
      | reasoning | Weighing the options | echo  |
      | text      | Go with the sloop    | echo  |
      | text      | Aye                  | echo  |
    When the user sends "Which boat?" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event     | text                 |
      | reckoning | Weighing the options |
      | reply     | Go with the sloop    |
    And session "memory-chat" has transcript matching:
      | type      | message.role | message.content   | text                 |
      | message   | user         | Which boat?       |                      |
      | reckoning |              |                   | Weighing the options |
      | message   | assistant    | Go with the sloop |                      |
    When the user sends "Confirm" on session "memory-chat" via memory comm
    Then the LLM request does not contain "Weighing the options"

  Scenario: compaction reaches the comm as a bulletin
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 200    |
    And the following sessions exist:
      | name        | last-input-tokens |
      | memory-chat | 165               |
    And session "memory-chat" has transcript:
      | type    | message.role | message.content            |
      | message | user         | Tell me about compaction   |
      | message | assistant    | It summarizes old messages |
    And the following model responses are queued:
      | type | content         | model |
      | text | Summary of chat | echo  |
      | text | Here you go     | echo  |
    When the user sends "hello" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event    | kind               |
      | bulletin | compaction/start   |
      | bulletin | compaction/success |
      | reply    |                    |

  Scenario: a streaming tool emits progress through the ctx seam
    Given a streaming tool "test__sounding" is registered that emits progress ["by the mark three" "and a half three"] and returns "depth 4"
    And the following model responses are queued:
      | type      | tool_call      | arguments | model |
      | tool_call | test__sounding | {}        | echo  |
      | text      | Depth is 4     |           | echo  |
    When the user sends "take a sounding" on session "memory-chat" via memory comm
    Then the memory comm has events matching:
      | event         | tool-name      | text              |
      | tool-call     | test__sounding |                   |
      | tool-progress | test__sounding | by the mark three |
      | tool-progress | test__sounding | and a half three  |
      | tool-result   | test__sounding |                   |
      | reply         |                |                   |
