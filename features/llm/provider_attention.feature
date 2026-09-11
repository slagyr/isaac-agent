Feature: Broken-provider attention
  A provider that answers with a non-wall, non-auth error (a 400 for a model
  the account cannot use, a malformed request) is broken, not weather. Walls
  (429, usage limit) and auth (401/403) are deferred and posted by hail;
  context overflow is compaction's problem. Everything else used to end the
  turn quietly with :chat/response-failed and nobody was told — hooks, cron,
  and episode seals have no hail path at all. Observed on zanebot 2026-09-09:
  chatgpt rejected every model for a day; four silent heartbeat mornings,
  every health hook, and an episode seal retrying every 30 s. (isaac-9xtv)

  One seam: every provider call passes through isaac.drive.dispatch, which
  asks provider-wall to classify the result and posts attention for what is
  left. Posts are throttled per provider (one hour); the repost after the
  hour carries the number of failures it swallowed.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a generic provider 400 posts attention to the comm outbox
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
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
      | model        | type       | status | message                                                   |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
    When the user sends "knock knock" on session "trash-can"
    Then the turn result is "api-error"
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                                                     |
      | comm    | :discord                                                                  |
      | target  | boiler-room                                                               |
      | content | contains "chatgpt" and "snuffy-codex" and "trash-can" and "not supported" |

  Scenario: repeated failures on one provider post attention once
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
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
      | dumpster  | oscar |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | model        | type       | status | message                                                   |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
    When the user sends "knock knock" on session "trash-can"
    And the user sends "anyone home" on session "dumpster"
    And the user sends "still there" on session "trash-can"
    Then the directory "comm/delivery/pending" has exactly 1 file
    And the log has entries matching:
      | level | event                         | provider | suppressed |
      | :info | :attention/provider-throttled | chatgpt  | 2          |

  Scenario: a provider still failing after an hour posts again with the suppressed count
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
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
      | model        | type       | status | message                                                   |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
      | snuffy-codex | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
    And the current time is "2026-03-01T10:00:00"
    When the user sends "knock knock" on session "trash-can"
    Given the current time is "2026-03-01T10:30:00"
    When the user sends "anyone home" on session "trash-can"
    Given the current time is "2026-03-01T11:00:30"
    When the user sends "still there" on session "trash-can"
    Then the directory "comm/delivery/pending" has exactly 2 files
    And the newest file in "comm/delivery/pending" EDN contains:
      | path    | value                                                        |
      | comm    | :discord                                                     |
      | target  | boiler-room                                                  |
      | content | contains "chatgpt" and "1 more failure" and "not supported" |

  Scenario: failures on two providers post attention for each
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/models/tinfoil.edn" exists with:
      | path           | value            |
      | model          | tinfoil-sonnet   |
      | provider       | grover:anthropic |
      | context-window | 128000           |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the isaac EDN file "config/crew/bert.edn" exists with:
      | path  | value   |
      | model | tinfoil |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
      | paperclip | bert  |
    And the following model responses are queued:
      | model          | type       | status | message                                                   |
      | snuffy-codex   | http-error | 400    | The 'snuffy-codex' model is not supported on this account |
      | tinfoil-sonnet | http-error | 400    | model: tinfoil-sonnet is not a valid model id             |
    When the user sends "knock knock" on session "trash-can"
    And the user sends "is this thing on" on session "paperclip"
    Then the directory "comm/delivery/pending" has exactly 2 files
