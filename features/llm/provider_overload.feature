Feature: An overloaded provider or a dropped connection is weather, not a turn-ending error
  2026-10-07 on zanebot: chatgpt answered two worker turns mid-stream with
  "Our servers are currently overloaded. Please try again later." and reset
  a third ("Connection reset"). The first came back as a plain :api-error,
  the reset as :unknown, so each ended its turn: no fallback, no suspend,
  and the beans sat until a person re-hailed them. Each was one failed
  request among hundreds; Codex was not down.
  Overloaded is reported as :overloaded: a Responses response.failed or
  error event whose code or message says overloaded, HTTP 529 (Anthropic's
  overloaded_error), 502 or 504. A connection that resets or closes mid-
  request (an IOException after connecting) is :connection-lost; a refused
  connection keeps its own :connection-refused. The drive treats both as
  weather beside :stream-stalled and :stream-ended-early: the turn
  continues on the crew's next model, else it suspends and resumes. HTTP
  503 already walls and is unchanged.
  Fixtures: a queued `stream-failed` response makes Grover emit the API's
  failure event with the given message (Responses: response.failed); a
  queued `connection-reset` returns the transport's result for a reset
  (the transport's own classification is a unit spec).
  Decision (2026-10-07, Micah).

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario Outline: <failure> on the head model continues the turn on the next model
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
      | type   | status   | content   | model      |
      | <type> | <status> | <content> | head-wire  |
      | text   |          | carried   | relay-wire |
    When the user sends "status?" on session "dock" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "dock" used model "relay-wire"
    And the log has entries matching:
      | level | event                | skipped-model | model      | reason   |
      | :info | :turn/model-fallback | head-wire     | relay-wire | <reason> |

    Examples:
      | failure                          | provider         | relay-provider   | type             | status | content                                                      | reason           |
      | chatgpt overloaded mid-stream    | grover:chatgpt   | grover:anthropic | stream-failed    |        | Our servers are currently overloaded. Please try again later. | :overloaded      |
      | Anthropic 529 overloaded         | grover:anthropic | grover:chatgpt   | http-error       | 529    | Overloaded                                                   | :overloaded      |
      | a 502 bad gateway                | grover:openai    | grover:anthropic | http-error       | 502    | Bad gateway                                                  | :overloaded      |
      | a 504 gateway timeout            | grover:openai    | grover:anthropic | http-error       | 504    | Gateway timeout                                              | :overloaded      |
      | a connection reset mid-request   | grover:chatgpt   | grover:anthropic | connection-reset |        | Connection reset                                             | :connection-lost |

  Scenario Outline: <failure> with no fallback suspends the turn
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
      | type   | content   | model        |
      | <type> | <content> | snuffy-codex |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key                   | value                |
      | suspended             | true                 |
      | reason                | <reason>             |
      | suspended-on.provider | chatgpt              |
      | retry-at              | 2026-04-21T10:00:30Z |

    Examples:
      | failure            | type             | content                                                      | reason           |
      | overloaded         | stream-failed    | Our servers are currently overloaded. Please try again later. | :overloaded      |
      | a connection reset | connection-reset | Connection reset                                             | :connection-lost |
