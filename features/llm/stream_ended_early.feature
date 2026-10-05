Feature: A stream that ends without its end marker is provider weather (isaac-l1b6)
  Every streaming API ends a good response with an end marker:
    responses (chatgpt)   response.completed
    messages (anthropic)  message_stop
    chat completions      a chunk carrying finish_reason
    ollama                a chunk with done true
  A stream that closes before its marker was cut off, whether it carried
  nothing, half a reply, or a tool call. The adapter reports the fact as
  :stream-ended-early and keeps nothing it received. The drive treats it as
  weather, like a stall: the turn continues on the crew's next model, and
  with no next model it suspends and resumes later. Nothing partial reaches
  the transcript, and a tool call from a cut stream never runs.
  Background: on 2026-10-05 chatgpt closed seven streams empty within
  seconds; each ended its turn as a plain :llm-error with no fallback and
  no retry. Anthropic and chat completions never checked their markers, so
  a mid-reply cut passed half a message off as complete.
  Fixture: a queued `cut-off` response streams its content or tool call and
  stops before the API's end marker. Grover now sends every API's real end
  marker on complete responses.
  Decision (2026-10-05, Micah).

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario Outline: <provider> cut off before any output continues on the next model
    Given the isaac EDN file "config/models/head.edn" exists with:
      | path           | value      |
      | model          | head-wire  |
      | provider       | <provider> |
      | context-window | 128000     |
    And the isaac EDN file "config/models/relay.edn" exists with:
      | path           | value            |
      | model          | relay-wire       |
      | provider       | <relay-provider> |
      | context-window | 128000           |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | head     |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name | crew |
      | dock | zane |
    And the following model responses are queued:
      | type    | content | model      |
      | cut-off |         | head-wire  |
      | text    | carried | relay-wire |
    When the user sends "status?" on session "dock" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "dock" used model "relay-wire"
    And the log has entries matching:
      | level | event                | skipped-model | model      | reason              |
      | :info | :turn/model-fallback | head-wire     | relay-wire | :stream-ended-early |

    Examples:
      | provider         | relay-provider   |
      | grover:chatgpt   | grover:anthropic |
      | grover:anthropic | grover:chatgpt   |
      | grover:openai    | grover:anthropic |
      | grover:ollama    | grover:anthropic |

  Scenario Outline: <provider> cut off mid-reply keeps none of the partial text
    Given the isaac EDN file "config/models/head.edn" exists with:
      | path           | value      |
      | model          | head-wire  |
      | provider       | <provider> |
      | context-window | 128000     |
    And the isaac EDN file "config/models/relay.edn" exists with:
      | path           | value            |
      | model          | relay-wire       |
      | provider       | <relay-provider> |
      | context-window | 128000           |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | head     |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name | crew |
      | dock | zane |
    And the following model responses are queued:
      | type    | content                  | model      |
      | cut-off | The reef lies two leagu | head-wire  |
      | text    | The reef lies north.     | relay-wire |
    When the user sends "where is the reef?" on session "dock" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And session "dock" has transcript matching:
      | type    | message.role | message.content      | message.model |
      | message | user         | where is the reef?   |               |
      | message | assistant    | The reef lies north. | relay-wire    |
    And session "dock" has 3 transcript entries

    Examples:
      | provider         | relay-provider   |
      | grover:chatgpt   | grover:anthropic |
      | grover:anthropic | grover:chatgpt   |
      | grover:openai    | grover:anthropic |
      | grover:ollama    | grover:anthropic |

  Scenario Outline: <provider> tool call from a cut stream never runs
    Given the isaac EDN file "config/models/head.edn" exists with:
      | path           | value      |
      | model          | head-wire  |
      | provider       | <provider> |
      | context-window | 128000     |
    And the isaac EDN file "config/models/relay.edn" exists with:
      | path           | value            |
      | model          | relay-wire       |
      | provider       | <relay-provider> |
      | context-window | 128000           |
    And the isaac EDN file "config/crew/zane.edn" exists with:
      | path           | value    |
      | model          | head     |
      | model-fallback | [:relay] |
    And the crew "zane" allows tools: "exec/run"
    And the following sessions exist:
      | name  | crew |
      | tools | zane |
    And the following model responses are queued:
      | type    | tool_call | arguments              | content     | model      |
      | cut-off | exec__run | {"command": "echo hi"} |             | head-wire  |
      | text    |           |                        | Nothing ran | relay-wire |
    When the user sends "look" on session "tools" via memory comm
    Then the exec tool is executed 0 times
    And the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "tools" used model "relay-wire"

    Examples:
      | provider         | relay-provider   |
      | grover:chatgpt   | grover:anthropic |
      | grover:anthropic | grover:chatgpt   |
      | grover:openai    | grover:anthropic |
      | grover:ollama    | grover:anthropic |

  Scenario: a cut stream with no fallback suspends the turn
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type    | content | model        |
      | cut-off |         | snuffy-codex |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key                   | value                |
      | suspended             | true                 |
      | reason                | :stream-ended-early  |
      | suspended-on.provider | chatgpt              |
      | suspended-on.model    | snuffy-codex         |
      | retry-at              | 2026-04-21T10:00:30Z |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content | #comment                        |
      | message | user         | knock knock     | last entry — nothing fabricated |
