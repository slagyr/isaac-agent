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
    Then the turn result is unavailable with retry-after-ms 60000
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
    Then the turn result is unavailable with retry-after-ms 1800000
