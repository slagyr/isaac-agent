Feature: Turns suspend and resume on provider weather (isaac-nqeq, epic isaac-ugpq)
  A provider wall (429 / usage limit), auth failure (401/403) or stall mid-turn
  is, from the turn's point of view, the same event as a restart: the work was
  interrupted, not finished. The drive no longer ENDS the turn with
  :unavailable? — it SUSPENDS it: the durable turn marker (isaac-7li9) is
  stamped :suspended true, :reason, :suspended-on {:provider :model},
  :retry-at, and the thread is released. A resume sweep on the shared
  scheduler re-drives suspended turns whose :retry-at has passed — the same
  cycle loop, from the transcript, exactly as boot resume does. Every re-drive
  is the probe; walling again backs off and re-suspends. Suspensions are never
  attempts and never fail the turn. Nothing is retried by the origin: hail,
  cron and comms just wait for the turn to complete (isaac-q2v5, isaac-a0q6).

  Background:
    Given default Grover setup
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

  Scenario: a wall mid-turn suspends the turn instead of ending it
    Given the following model responses are queued:
      | model        | type       | status | retry-after |
      | snuffy-codex | http-error | 429    | 60          |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key                    | value                |
      | suspended              | true                 |
      | reason                 | :wall                |
      | suspended-on.provider  | chatgpt              |
      | suspended-on.model     | snuffy-codex         |
      | suspended-at           | 2026-04-21T10:00:00Z |
      | retry-at               | 2026-04-21T10:01:00Z |
    And the log has entries matching:
      | level | event           | session   | reason | retry-at             |
      | :warn | :turn/suspended | trash-can | :wall  | 2026-04-21T10:01:00Z |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content | #comment                          |
      | message | user         | knock knock     | last entry — nothing fabricated   |

  Scenario: the resume sweep leaves a suspended turn alone before retry-at
    Given a suspended turn marker exists for session "trash-can" with:
      | key       | value                |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:30:00Z |
    When the resume sweep runs at "2026-04-21T10:15:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key       | value |
      | suspended | true  |
    And the log has no entries matching:
      | event         |
      | :turn/resumed |

  Scenario: the resume sweep re-drives a suspended turn after retry-at and it completes
    Given session "trash-can" has transcript:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
    And a suspended turn marker exists for session "trash-can" with:
      | key       | value                |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:01:00Z |
    And the following model responses are queued:
      | type | content    | model        |
      | text | Who's there | snuffy-codex |
    When the resume sweep runs at "2026-04-21T10:02:00Z"
    And the turn ends on session "trash-can"
    Then the log has entries matching:
      | level | event         | session   | suspended-ms |
      | :info | :turn/resumed | trash-can | #*           |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
      | message | assistant    | Who's there     |
    And no turn marker exists for session "trash-can"

  Scenario: a resumed turn continues from the transcript — tool results already persisted are not re-run
    Given the crew "oscar" allows tools: "exec/run"
    And the built-in tools are registered
    And session "trash-can" has transcript:
      | type    | message.role | message.content                              |
      | message | user         | list the files                               |
      | message | assistant    | (toolCall exec/run {"command":"ls"})         |
      | message | toolResult   | a.txt b.txt                                  |
    And a suspended turn marker exists for session "trash-can" with:
      | key       | value                |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:01:00Z |
    And the following model responses are queued:
      | type | content            | model        |
      | text | Two files: a and b | snuffy-codex |
    When the resume sweep runs at "2026-04-21T10:02:00Z"
    And the turn ends on session "trash-can"
    Then the exec tool is executed 0 times
    And the llm request for session "trash-can" includes the tool result "a.txt b.txt"
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content    |
      | message | assistant    | Two files: a and b |

  Scenario: walling again on resume backs off and re-suspends — no attempt is consumed
    Given a suspended turn marker exists for session "trash-can" with:
      | key           | value                |
      | reason        | :wall                |
      | retry-at      | 2026-04-21T10:01:00Z |
      | suspend-count | 1                    |
    And the following model responses are queued:
      | model        | type       | status | message                |
      | snuffy-codex | http-error | 429    | usage_limit_reached    |
    When the resume sweep runs at "2026-04-21T10:02:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key           | value                |
      | suspended     | true                 |
      | suspend-count | 2                    |
      | retry-at      | 2026-04-21T10:32:00Z |
    And the log has entries matching:
      | level | event           | session   | suspend-count |
      | :warn | :turn/suspended | trash-can | 2             |

  Scenario: backoff without a provider Retry-After grows from 30 s and caps at 30 min
    Given the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 503    |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key      | value                |
      | retry-at | 2026-04-21T10:00:30Z |
    Given a suspended turn marker exists for session "trash-can" with:
      | key           | value                |
      | reason        | :wall                |
      | retry-at      | 2026-04-21T10:00:00Z |
      | suspend-count | 12                   |
    And the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 503    |
    When the resume sweep runs at "2026-04-21T11:00:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key      | value                |
      | retry-at | 2026-04-21T11:30:00Z |

  Scenario: an auth failure suspends with reason auth and waits for re-login
    Given the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 401    |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key       | value |
      | suspended | true  |
      | reason    | :auth |

  Scenario: a resumed turn re-resolves the crew's model — moving the crew unblocks its parked turns
    Given session "trash-can" has transcript:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
    And a suspended turn marker exists for session "trash-can" with:
      | key                    | value                |
      | reason                 | :wall                |
      | suspended-on.provider  | chatgpt              |
      | suspended-on.model     | snuffy-codex         |
      | retry-at               | 2026-04-21T10:01:00Z |
    And the isaac EDN file "config/models/grover-mini.edn" exists with:
      | path           | value        |
      | model          | grover-mini  |
      | provider       | grover       |
      | context-window | 128000       |
    And the following model responses are queued:
      | type | content   | model       |
      | text | Rerouted  | grover-mini |
    When the isaac EDN file "config/crew/oscar.edn" changes to:
      | path  | value       |
      | model | grover-mini |
    And the turn ends on session "trash-can"
    Then the log has entries matching:
      | level | event         | session   | trigger        |
      | :info | :turn/resumed | trash-can | :config-reload |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Rerouted        |

  Scenario: a charge that pinned a model keeps waiting on that model
    Given session "trash-can" has transcript:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
    And a suspended turn marker exists for session "trash-can" with:
      | key                    | value                |
      | reason                 | :wall                |
      | suspended-on.model     | snuffy-codex         |
      | model-override         | snuffy               |
      | retry-at               | 2026-04-21T10:01:00Z |
    And the isaac EDN file "config/models/grover-mini.edn" exists with:
      | path           | value        |
      | model          | grover-mini  |
      | provider       | grover       |
      | context-window | 128000       |
    And the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 429    |
    When the isaac EDN file "config/crew/oscar.edn" changes to:
      | path  | value       |
      | model | grover-mini |
    Then a turn marker exists for session "trash-can" with:
      | key                 | value        |
      | suspended           | true         |
      | suspended-on.model  | snuffy-codex |

  Scenario: boot resume honours retry-at for a suspended marker
    Given a suspended turn marker exists for session "trash-can" with:
      | key       | value                |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:30:00Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key       | value |
      | suspended | true  |
    And the log has entries matching:
      | level | event                    | session   | retry-at             |
      | :info | :resume/weather-deferred | trash-can | 2026-04-21T10:30:00Z |

  Scenario: a turn parked past the attention threshold posts one attention notice and stays parked
    Given config:
      | turn.suspended-attention-ms | 21600000    |
      | attention.notify.comm       | discord     |
      | attention.notify.target     | boiler-room |
    And a suspended turn marker exists for session "trash-can" with:
      | key           | value                |
      | reason        | :auth                |
      | suspended-at  | 2026-04-21T04:00:00Z |
      | retry-at      | 2026-04-21T10:00:00Z |
    And the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 401    |
    When the resume sweep runs at "2026-04-21T10:01:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key                 | value |
      | suspended           | true  |
      | attention-posted    | true  |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                              |
      | comm    | :discord                                           |
      | target  | boiler-room                                        |
      | content | contains "trash-can" and "suspended" and "auth"    |
    When the resume sweep runs at "2026-04-21T10:31:00Z"
    Then the directory "comm/delivery/pending" has exactly 1 file

  Scenario: cancelling a suspended turn deletes its marker — cancel is not suspend
    Given a suspended turn marker exists for session "trash-can" with:
      | key       | value                |
      | reason    | :wall                |
      | retry-at  | 2026-04-21T10:30:00Z |
    When the turn is cancelled on session "trash-can"
    Then no turn marker exists for session "trash-can"
    And the resume sweep runs at "2026-04-21T11:00:00Z"
    And the log has no entries matching:
      | event         |
      | :turn/resumed |

  @wip
  Scenario: the weather sweep is registered with the shared scheduler (isaac-f3hq)
    The sweep had no production caller — it was reachable only from the
    feature steps. It ticks on the shared scheduler like the turn queue does;
    the smallest backoff is 30 s, so a 10 s tick is prompt enough.
    When the weather sweep is started
    Then the scheduled tasks include:
      | id                 | trigger.kind | trigger.ms |
      | turn/sweep-weather | interval     | 10000      |

  @wip
  Scenario: boot resume and the sweep never double-drive a due marker (isaac-f3hq)
    Boot resume hands a due suspended marker to the turn queue and clears it
    (isaac-yxch); the first sweep tick after boot finds nothing to drive. One
    resume note, one reply.
    Given session "trash-can" has transcript:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
    And a suspended turn marker exists for session "trash-can" with:
      | key      | value                |
      | reason   | :wall                |
      | retry-at | 2026-04-21T10:01:00Z |
    And the following model responses are queued:
      | type | content     | model        |
      | text | Who's there | snuffy-codex |
    When interrupted turns are resumed at "2026-04-21T10:02:00Z"
    And the resume sweep runs at "2026-04-21T10:02:01Z"
    And the turn queue ticks at "2026-04-21T10:02:05Z"
    Then session "trash-can" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | knock knock        |
      | message | user         | #".*interrupted.*" |
      | message | assistant    | Who's there        |
    And the log has entries matching:
      | level | event         | session   | trigger |
      | :info | :turn/resumed | trash-can | :boot   |
    And the log has no entries matching:
      | event         | trigger |
      | :turn/resumed | :sweep  |
    And no turn marker exists for session "trash-can"

  @wip
  Scenario: an empty terminal response is weather — the turn parks and the sweep resumes it (isaac-f3hq)
    Field: an expired provider login looks like a model that returns nothing
    (fleet auth expiry ⇒ :empty-terminal-response on every turn). After the
    one continuation nudge (isaac-k4mf) the turn does not fail; it parks like
    a wall with reason :silence, and the sweep re-drives it from the
    transcript when the retry comes due. Ruling (Micah, 2026-09-21,
    isaac-9azm): a turn that errors is weather and resuming it is the
    drive's job.
    Given the following model responses are queued:
      | type | content     | model        |
      | text |             | snuffy-codex |
      | text |             | snuffy-codex |
      | text | Who's there | snuffy-codex |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then the turn result is "suspended"
    And a turn marker exists for session "trash-can" with:
      | key       | value                |
      | suspended | true                 |
      | reason    | :silence             |
      | retry-at  | 2026-04-21T10:00:30Z |
    And the log has entries matching:
      | level | event           | session   | reason   |
      | :warn | :turn/suspended | trash-can | :silence |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content | #comment                        |
      | message | user         | knock knock     | last entry — nothing fabricated |
    When the resume sweep runs at "2026-04-21T10:00:31Z"
    And the turn ends on session "trash-can"
    Then session "trash-can" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
      | message | assistant    | Who's there     |
    And no turn marker exists for session "trash-can"

  @wip
  Scenario: an auth park posts attention at once, throttled per provider (isaac-5a4n, isaac-f3hq)
    A wall clears on its own; an expired login does not — only a human can
    re-login. Hail used to post this notice from its deferral branch; now the
    drive posts it when it parks: the first :auth park at once, then at most
    hourly per provider while the park persists. The immediate notice sets
    :attention-posted so the suspended-attention-ms notice does not double up.
    Given config:
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the following model responses are queued:
      | model        | type       | status |
      | snuffy-codex | http-error | 401    |
      | snuffy-codex | http-error | 401    |
      | snuffy-codex | http-error | 401    |
    When the user sends "knock knock" on session "trash-can" at "2026-04-21T10:00:00Z"
    Then a turn marker exists for session "trash-can" with:
      | key              | value |
      | suspended        | true  |
      | reason           | :auth |
      | attention-posted | true  |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                         |
      | comm    | :discord                      |
      | target  | boiler-room                   |
      | content | contains "auth" and "chatgpt" |
    When the resume sweep runs at "2026-04-21T10:05:00Z"
    Then the directory "comm/delivery/pending" has exactly 1 file
    When the resume sweep runs at "2026-04-21T11:06:00Z"
    Then the directory "comm/delivery/pending" has exactly 2 files
