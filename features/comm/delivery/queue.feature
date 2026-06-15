Feature: Delivery queue
  Outbound comm posts that fail transiently go into a persistent
  retry queue instead of being dropped. Each delivery is a small EDN
  file under <root>/comm/delivery/. The worker attempts delivery on
  a schedule; successes are removed; failures after 5 attempts move
  to failed/ for manual review. Permanent failures (transient? false)
  dead-letter immediately without retrying.

  Retry policy (fixed in v1): 5 attempts with exponential backoff
  at 1s, 5s, 30s, 2m, 10m.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a successful delivery is removed from the queue
    Given the comm "stub" returns:
      | ok   |
      | true |
    And the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
      | path    | value                                |
      | id      | 7f3a                                 |
      | comm    | stub                                 |
      | target  | https://stub.test/channels/C999/post |
      | content | Hello from the delivery worker.      |
    When the delivery worker ticks
    Then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    And the comm "stub" was called with:
      | target                               | content                         |
      | https://stub.test/channels/C999/post | Hello from the delivery worker. |

  Scenario: a transient failure reschedules the delivery with backoff
    Given the comm "stub" returns:
      | ok    | transient? |
      | false | true       |
    And the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
      | path     | value                                | #comment                          |
      | id       | 7f3a                                 |                                   |
      | comm     | stub                                 |                                   |
      | target   | https://stub.test/channels/C999/post |                                   |
      | content  | Trying once more.                    |                                   |
      | attempts | 0                                    | fresh delivery, no prior failures |
    When the delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
      | path            | value                | #comment                                  |
      | attempts        | 1                    | incremented from 0 after this failure     |
      | next-attempt-at | 2026-04-21T10:00:01Z | tick time + 1 second (first backoff step) |

  Scenario: delivery moves to failed after max attempts
    Given the comm "stub" returns:
      | ok    | transient? |
      | false | true       |
    And the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
      | path     | value                                | #comment                                          |
      | id       | 7f3a                                 |                                                   |
      | comm     | stub                                 |                                                   |
      | target   | https://stub.test/channels/C999/post |                                                   |
      | content  | Goodbye, world.                      |                                                   |
      | attempts | 4                                    | one short of the 5-attempt max; this tick is last |
    When the delivery worker ticks at "2026-04-21T10:00:00Z"
    Then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    And the EDN isaac file "comm/delivery/failed/7f3a.edn" contains:
      | path     | value | #comment                                |
      | attempts | 5     | hit the max on this tick; dead-lettered |
      | id       | 7f3a  |                                         |
    And the log has entries matching:
      | level | event                   | id   | reason    |
      | error | :delivery/dead-lettered | 7f3a | :exhausted |

  Scenario: a permanent failure dead-letters immediately without retrying
    Given the comm "stub" returns:
      | ok    | transient? |
      | false | false      |
    And the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
      | path    | value   |
      | id      | 7f3a    |
      | comm    | stub    |
      | target  | C999    |
      | content | Hello   |
    When the delivery worker ticks
    Then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    And the EDN isaac file "comm/delivery/failed/7f3a.edn" contains:
      | path     | value | #comment                           |
      | attempts | 0     | not incremented; single-tick close |
      | id       | 7f3a  |                                    |
    And the log has entries matching:
      | level | event                   | id   | reason    |
      | error | :delivery/dead-lettered | 7f3a | :permanent |

  Scenario: delivery worker tick is registered with the shared scheduler
    When the comm delivery system is started
    Then the delivery scheduled tasks include:
      | id            | trigger.kind | trigger.ms |
      | delivery/tick | interval     | 10000      |
