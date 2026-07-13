Feature: Stateful Responses API chaining (previous_response_id)
  Within a tool-loop turn, providers that support Responses statefulness
  chain cycles with previous_response_id + store:true so cycle 2..N send
  only new tool results instead of the full context.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the built-in tools are registered

  Scenario: cycle 2 chains from cycle 1's response id and sends only the new tool results
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
      | stateful       | true           |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the crew "oscar" allows tools: "exec"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type      | id     | tool_call | arguments           | content |
      | snuffy-codex | tool_call | resp-1 | exec      | {"command": "true"} |         |
      | snuffy-codex | text      | resp-2 |           |                     | done    |
    When the user sends "count the cans" on session "trash-can"
    Then outbound HTTP request 1 matches:
      | key        | value |
      | body.store | true  |
    And outbound HTTP request 1 has no body.previous_response_id
    And outbound HTTP request 2 matches:
      | key                       | value                |
      | body.previous_response_id | resp-1               |
      | body.store                | true                 |
      | body.input.#count         | 1                    |
      | body.input.0.type         | function_call_output |

  Scenario: without stateful, every cycle resends the full context stateless
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the crew "oscar" allows tools: "exec"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type      | id     | tool_call | arguments           | content |
      | snuffy-codex | tool_call | resp-1 | exec      | {"command": "true"} |         |
      | snuffy-codex | text      | resp-2 |           |                     | done    |
    When the user sends "count the cans" on session "trash-can"
    Then outbound HTTP request 2 matches:
      | key               | value                |
      | body.store        | false                |
      | body.input.#count | 3                    |
      | body.input.0.role | user                 |
      | body.input.2.type | function_call_output |
    And outbound HTTP request 2 has no body.previous_response_id

  Scenario: a previous-response-not-found reply resets state and retries with full context
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
      | stateful       | true           |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the crew "oscar" allows tools: "exec"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type       | id     | tool_call | arguments           | status | message                           | content |
      | snuffy-codex | tool_call  | resp-1 | exec      | {"command": "true"} |        |                                   |         |
      | snuffy-codex | http-error |        |           |                     | 404    | Response with id resp-1 not found |         |
      | snuffy-codex | text       | resp-3 |           |                     |        |                                   | done    |
    When the user sends "count the cans" on session "trash-can"
    Then outbound HTTP request 2 matches:
      | key                       | value  |
      | body.previous_response_id | resp-1 |
    And outbound HTTP request 3 matches:
      | key               | value |
      | body.store        | true  |
      | body.input.#count | 3     |
    And outbound HTTP request 3 has no body.previous_response_id
    And the log has entries matching:
      | level | event             | provider |
      | :info | :chat/state-reset | chatgpt  |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | count the cans  |
      | #*      | #*           | #*              |
      | message | assistant    | done            |

  Scenario: a new turn starts a fresh chain — no previous_response_id carried across turns
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
      | stateful       | true           |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the crew "oscar" allows tools: "exec"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type      | id     | tool_call | arguments           | content |
      | snuffy-codex | tool_call | resp-1 | exec      | {"command": "true"} |         |
      | snuffy-codex | text      | resp-2 |           |                     | done    |
      | snuffy-codex | text      | resp-3 |           |                     | again   |
    When the user sends "count the cans" on session "trash-can"
    And the user sends "count them again" on session "trash-can"
    Then outbound HTTP request 3 matches:
      | key               | value |
      | body.store        | true  |
      | body.input.0.role | user  |
    And outbound HTTP request 3 has no body.previous_response_id
    # request 3 is turn 2 cycle 1 (fresh chain); requests 1-2 are turn 1
