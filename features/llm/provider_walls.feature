Feature: Provider wall classification
  The drive owns provider semantics: a provider wall (HTTP 429, usage-limit
  or credit-exhaustion errors) is not a turn failure — it is unavailability.
  The drive classifies such responses as {:unavailable? true :retry-after-ms N},
  N from the 429 Retry-After header when present, else the configured default
  (:defaults :provider-retry-after-ms, 30 min). Consumers (e.g. the hail
  delivery worker) never parse provider errors; they react to the classified
  result. Observed on zanebot 2026-07-06: codex usage_limit_reached and
  anthropic credit exhaustion dead-lettered six healthy hails in ~30 minutes
  because walls were priced as failures. (isaac-3tvq)

  A provider 400 whose message is a hard prompt/context overflow is the same
  kind of weather: context-exhausted, not :api-error. A generic 400 stays an
  error. The rejected user turn must not remain on the transcript. (isaac-bs5b)

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a provider 429 with Retry-After classifies the turn as unavailable
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
      | model        | type       | status | retry-after |
      | snuffy-codex | http-error | 429    | 60          |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 60000 and reason wall
    And the log has entries matching:
      | level | event                 | provider | status |
      | :warn | :chat/provider-walled | chatgpt  | 429    |

  Scenario: a usage-limit error without Retry-After uses the configured default
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
      | model        | type       | status | message                                               |
      | snuffy-codex | http-error | 429    | usage_limit_reached: The usage limit has been reached |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 1800000 and reason wall

  Scenario: a provider 401 classifies the turn as auth unavailability (isaac-5a4n)
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
      | model        | type       | status | message       |
      | snuffy-codex | http-error | 401    | Unauthorized  |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 300000 and reason auth
    And the log has entries matching:
      | level | event                        | provider | status |
      | :warn | :chat/provider-auth-rejected | chatgpt  | 401    |

  Scenario: a provider 403 permission-denied classifies as auth (grok scope)
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
      | model        | type       | status | message                                                      |
      | snuffy-codex | http-error | 403    | OAuth2 token missing required scope: api:access              |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 300000 and reason auth

  @wip
  Scenario: a provider 400 for prompt length classifies as context-exhausted
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
      | model        | type       | status | message                                                     |
      | snuffy-codex | http-error | 400    | maximum prompt length is 128000 but request contains 130000 |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 300000 and reason context-exhausted
    And the log has entries matching:
      | level | event                            | provider | status |
      | :warn | :chat/provider-context-exhausted | chatgpt  | 400    |

  @wip
  Scenario: a generic provider 400 stays an api-error
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
      | model        | type       | status | message                        |
      | snuffy-codex | http-error | 400    | invalid request: unknown field |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is "api-error"
    And the log has no entries matching:
      | level | event                            |
      | :warn | :chat/provider-context-exhausted |

  @wip
  Scenario: overflow weather does not leave the rejected user turn on the transcript
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
      | model        | type       | status | message                                                     |
      | snuffy-codex | http-error | 400    | maximum prompt length is 128000 but request contains 130000 |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is unavailable with retry-after-ms 300000 and reason context-exhausted
    And session "trash-can" has no transcript entries with role "user"
