Feature: A crew falls through its model chain when the provider is walled (isaac-uyj3)
  :model stays one id. :model-fallback is the ordered list after it.
  A wall, an auth rejection, or a stalled stream continues the turn on the
  next model that can hold the transcript. Tool results already written stay
  put. previous-response-id is not forwarded. A 400 contract error and a
  context overflow do not fall through. The model that answers keeps the
  rest of the turn. The next message returns to the head unless that
  provider is still inside its retry-after, and a walled provider skips
  every later model on that same provider. Discord is told the provider is
  broken only when the chain is exhausted. The jump is logged as
  :turn/model-fallback with the skipped model and the reason.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the isaac EDN file "config/models/primary.edn" exists with:
      | path           | value          |
      | model          | primary-wire   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/models/relay.edn" exists with:
      | path           | value             |
      | model          | relay-wire        |
      | provider       | grover:anthropic  |
      | context-window | 128000            |
    And the isaac EDN file "config/models/kin.edn" exists with:
      | path           | value          |
      | model          | kin-wire       |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/models/roomy.edn" exists with:
      | path           | value            |
      | model          | roomy-wire       |
      | provider       | grover:anthropic |
      | context-window | 128000           |
    And the isaac EDN file "config/models/tiny.edn" exists with:
      | path           | value            |
      | model          | tiny-wire        |
      | provider       | grover:anthropic |
      | context-window | 8                |

  Scenario: a walled primary continues the turn on the next model (isaac-uyj3)
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path            | value    |
      | model           | primary  |
      | model-fallback  | [:relay] |
    And the following sessions exist:
      | name | crew |
      | dock | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | relay-wire   |
    When the user sends "status?" on session "dock" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "dock" used model "relay-wire"
    And session "dock" has transcript matching:
      | type    | message.role | message.model |
      | message | assistant    | relay-wire    |
    And the directory "comm/delivery/pending" has exactly 0 files
    And the log has entries matching:
      | level | event                | skipped-model | model      | reason |
      | :info | :turn/model-fallback | primary-wire  | relay-wire | :wall  |

  Scenario: a 400 contract error does not fall back (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name     | crew |
      | contract | zane |
    And the following model responses are queued:
      | type       | status | message                        | model        |
      | http-error | 400    | invalid request: unknown field | primary-wire |
    When the user sends "status?" on session "contract"
    Then the turn result is "api-error"
    And the last chat request on session "contract" used model "primary-wire"
    And the log has no entries matching:
      | event                |
      | :turn/model-fallback |

  Scenario: context overflow retries the same model and does not fall back (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name     | crew |
      | overflow | zane |
    And the following model responses are queued:
      | type       | status | message                                                     | content | model        |
      | http-error | 400    | maximum prompt length is 128000 but request contains 130000 |         | primary-wire |
      | text       |        |                                                             | shorter | primary-wire |
    When the user sends "status?" on session "overflow" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And LLM request 2 matches:
      | key   | value        |
      | model | primary-wire |
    And the log has no entries matching:
      | event                |
      | :turn/model-fallback |

  Scenario: a failure after tools have run continues from the transcript (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the crew "zane" allows tools: "exec/run"
    And the following sessions exist:
      | name  | crew |
      | tools | zane |
    And the following model responses are queued:
      | type       | tool_call | arguments               | status | retry-after | content | model        |
      | tool_call  | exec__run | {"command": "echo hi"}  |        |             |         | primary-wire |
      | http-error |           |                         | 429    | 60          |         | primary-wire |
      | text       |           |                         |        |             | carried | relay-wire   |
    When the user sends "look" on session "tools" via memory comm
    Then the exec tool is executed 1 times
    And the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "tools" used model "relay-wire"

  Scenario: the fallback request carries no previous-response-id (isaac-uyj3)
    Given the isaac EDN file "config/models/primary.edn" exists with:
      | path           | value          |
      | model          | primary-wire   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
      | stateful       | true           |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the crew "zane" allows tools: "exec/run"
    And the following sessions exist:
      | name  | crew |
      | chain | zane |
    And the following model responses are queued:
      | type       | tool_call | arguments              | id     | status | retry-after | content | model        |
      | tool_call  | exec__run | {"command": "echo hi"} | resp-1 |        |             |         | primary-wire |
      | http-error |           |                        |        | 429    | 60          |         | primary-wire |
      | text       |           |                        |        |        |             | carried | relay-wire   |
    When the user sends "look" on session "chain" via memory comm
    Then LLM request 3 matches:
      | key   | value      |
      | model | relay-wire |
    And LLM request 3 has no previous-response-id

  Scenario: the rest of the turn stays on the model that answered (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the crew "zane" allows tools: "exec/run"
    And the following sessions exist:
      | name | crew |
      | stay | zane |
    And the following model responses are queued:
      | type       | tool_call | arguments              | status | retry-after | content | model        |
      | http-error |           |                        | 429    | 60          |         | primary-wire |
      | tool_call  | exec__run | {"command": "echo hi"} |        |             |         | relay-wire   |
      | text       |           |                        |        |             | carried | relay-wire   |
    When the user sends "look" on session "stay" via memory comm
    Then LLM request 2 matches:
      | key   | value      |
      | model | relay-wire |
    And LLM request 3 matches:
      | key   | value      |
      | model | relay-wire |

  Scenario: the next message returns to the head of the chain after the wall expires (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name  | crew |
      | again | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | relay-wire   |
    When the user sends "status?" on session "again" at "2026-04-21T10:00:00Z"
    And the following model responses are queued:
      | type | content | model        |
      | text | again   | primary-wire |
    And the user sends "again?" on session "again" at "2026-04-21T10:02:00Z"
    Then the last chat request on session "again" used model "primary-wire"

  Scenario: a message inside the retry-after starts on the next provider (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name  | crew |
      | still | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | relay-wire   |
    When the user sends "status?" on session "still" at "2026-04-21T10:00:00Z"
    And the following model responses are queued:
      | type | content | model      |
      | text | still   | relay-wire |
    And the user sends "again?" on session "still" at "2026-04-21T10:00:30Z"
    Then the last chat request on session "still" used model "relay-wire"

  Scenario: a later model on the walled provider is skipped (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value          |
      | model          | primary        |
      | model-fallback | [:kin :relay]  |
    And the following sessions exist:
      | name | crew |
      | kin  | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | relay-wire   |
    When the user sends "status?" on session "kin"
    Then LLM request 2 matches:
      | key   | value      |
      | model | relay-wire |

  Scenario: a fallback model that cannot hold the transcript is skipped (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value           |
      | model          | primary         |
      | model-fallback | [:tiny :roomy]  |
    And the following sessions exist:
      | name | crew |
      | tiny | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | roomy-wire   |
    When the user sends "status?" on session "tiny"
    Then LLM request 2 matches:
      | key   | value      |
      | model | roomy-wire |

  Scenario: an exhausted chain reports the provider broken (isaac-uyj3)
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name      | crew |
      | exhausted | zane |
    And the following model responses are queued:
      | type       | status | retry-after | model        |
      | http-error | 429    | 60          | primary-wire |
      | http-error | 429    | 60          | relay-wire   |
    When the user sends "status?" on session "exhausted"
    Then the turn result is unavailable with retry-after-ms 60000 and reason wall
    And the directory "comm/delivery/pending" has exactly 1 file

  Scenario: the jump log names the skipped model and the reason (isaac-uyj3)
    Given the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | primary  |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name   | crew |
      | logged | zane |
    And the following model responses are queued:
      | type       | status | retry-after | content | model        |
      | http-error | 429    | 60          |         | primary-wire |
      | text       |        |             | carried | relay-wire   |
    When the user sends "status?" on session "logged"
    Then the log has entries matching:
      | level | event                | skipped-model | model      | reason |
      | :info | :turn/model-fallback | primary-wire  | relay-wire | :wall  |
