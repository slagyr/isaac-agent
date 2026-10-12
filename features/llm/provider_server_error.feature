Feature: A provider's own server error is weather, not a turn-ending error
  2026-10-12 on zanebot: chatgpt failed one worker request mid-stream with
  "An error occurred while processing your request. You can retry your
  request, or contact us through our help center ...". It came back as a
  plain :api-error, so the turn ended: no fallback to the crew's next
  model, no suspend, and the claimed bean sat until a person re-hailed it.
  The provider said the fault was its own and that a retry was fine.
  A server error is reported as :server-error, decided by codes and never
  by the wording of the message: a Responses response.failed or error event
  whose code is server_error, one that carries no code at all (the provider
  blamed nothing on the request), or HTTP 500. The drive treats it as
  weather beside :overloaded and :connection-lost: the turn continues on
  the crew's next model, else it suspends and resumes. A failure the
  provider blames on the request (any other code, any other 4xx) still
  ends the turn. The failure's code is logged on :chat/response-failed.
  Fixtures: a queued `stream-failed` response makes Grover emit the API's
  failure event with the given message and, new here, the given `code`.
  Decision (2026-10-12, Micah).

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the isaac EDN file "config/models/head.edn" exists with:
      | path           | value          |
      | model          | head-wire      |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/models/relay.edn" exists with:
      | path           | value            |
      | model          | relay-wire       |
      | provider       | grover:anthropic |
      | context-window | 128000           |

  Scenario Outline: <failure> on the head model continues the turn on the next model
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path           | value    |
      | model          | head     |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
    And the following model responses are queued:
      | type   | status   | code   | content   | model      |
      | <type> | <status> | <code> | <content> | head-wire  |
      | text   |          |        | carried   | relay-wire |
    When the user sends "status?" on session "mooring" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the last chat request on session "mooring" used model "relay-wire"
    And the log has entries matching:
      | level | event                | skipped-model | model      | reason        |
      | :info | :turn/model-fallback | head-wire     | relay-wire | :server-error |

    Examples:
      | failure                          | type          | status | code         | content               |
      | a server_error mid-stream        | stream-failed |        | server_error | Something went wrong. |
      | a stream failure with no code    | stream-failed |        |              | Something went wrong. |
      | an HTTP 500                      | http-error    | 500    |              | Something went wrong. |

  Scenario: a server error with no fallback suspends the turn
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value |
      | model | head  |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type          | code         | content               | model     |
      | stream-failed | server_error | Something went wrong. | head-wire |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key                   | value                |
      | suspended             | true                 |
      | reason                | :server-error        |
      | suspended-on.provider | chatgpt              |
      | retry-at              | 2026-04-21T10:00:30Z |

  Scenario Outline: <failure> is the request's fault and still ends the turn
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path           | value    |
      | model          | head     |
      | model-fallback | [:relay] |
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
    And the following model responses are queued:
      | type   | status   | code   | content   | model      |
      | <type> | <status> | <code> | <content> | head-wire  |
      | text   |          |        | carried   | relay-wire |
    When the user sends "status?" on session "mooring" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :error          |
    And the last chat request on session "mooring" used model "head-wire"

    Examples:
      | failure                          | type          | status | code           | content               |
      | an invalid_prompt stream failure | stream-failed |        | invalid_prompt | Something went wrong. |
      | an HTTP 400                      | http-error    | 400    |                | Something went wrong. |
