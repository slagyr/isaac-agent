Feature: A delivery lands in the transcript of the session that owns its channel
  A delivery queue only posts. When a cron job, an attention notice, or
  another session's comm__send posts into a channel where a crew talks
  with someone, that crew's session never saw it, and the crew answers
  out of context (yopp, 2026-10-06: a cron-style DM, then "I got it."
  read as a reply to something else).
  Inbound records which channel a session talks on: the session carries
  :channels, "<comm>:<channel>" strings, written by the comm when a
  message arrives. After a delivery posts, the comm reports the channel
  it actually posted to (:channel on its send result; an email target is
  reported as the DM space it resolved to). The worker appends a marked
  note to the session whose :channels holds "<comm>:<channel>":
    [sent here by crew <crew> from session <session>] <content>
  A delivery sent by that same session is its own reply, already in its
  transcript, and is not appended again. A channel no session talks on
  is skipped: there is no conversation to keep continuous. A failed send
  appends nothing.
  Decision (2026-10-06, Micah): record at delivery time, in agent; gchat
  keeps dropping its own echoes.

  Background:
    Given an Isaac root at "target/test-state"
    And the following sessions exist:
      | name      | crew    | channels  |
      | ada-dm    | lookout | stub:C999 |

  Scenario: a delivery into a session's channel is appended to that session as a marked note
    Given the comm "stub" returns:
      | ok   | channel |
      | true | C999    |
    And the isaac EDN file comm/delivery/pending/7f3a.edn exists with:
      | path    | value               |
      | id      | 7f3a                |
      | comm    | stub                |
      | target  | ada@example.com     |
      | content | Your weekly digest. |
      | crew    | herald              |
      | session | cron-heartbeat      |
    When the delivery worker ticks
    Then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    And session "ada-dm" has transcript matching:
      | type    | message.role | message.content                                                                  |
      | message | assistant    | #"\[sent here by crew herald from session cron-heartbeat\] Your weekly digest\." |

  Scenario: a delivery the owning session sent itself is not appended again
    Given the comm "stub" returns:
      | ok   | channel |
      | true | C999    |
    And the isaac EDN file comm/delivery/pending/7f3b.edn exists with:
      | path    | value          |
      | id      | 7f3b           |
      | comm    | stub           |
      | target  | C999           |
      | content | Here you go.   |
      | crew    | lookout        |
      | session | ada-dm         |
    When the delivery worker ticks
    Then the isaac file "comm/delivery/pending/7f3b.edn" does not exist
    And session "ada-dm" has 1 transcript entries

  Scenario: a delivery into a channel no session talks on appends nothing
    Given the comm "stub" returns:
      | ok   | channel |
      | true | C777    |
    And the isaac EDN file comm/delivery/pending/7f3c.edn exists with:
      | path    | value          |
      | id      | 7f3c           |
      | comm    | stub           |
      | target  | C777           |
      | content | Broadcast.     |
      | crew    | herald         |
      | session | cron-heartbeat |
    When the delivery worker ticks
    Then the isaac file "comm/delivery/pending/7f3c.edn" does not exist
    And session "ada-dm" has 1 transcript entries

  Scenario: a failed delivery appends nothing
    Given the comm "stub" returns:
      | ok    | transient? | channel |
      | false | false      | C999    |
    And the isaac EDN file comm/delivery/pending/7f3d.edn exists with:
      | path    | value          |
      | id      | 7f3d           |
      | comm    | stub           |
      | target  | C999           |
      | content | Never arrived. |
      | crew    | herald         |
      | session | cron-heartbeat |
    When the delivery worker ticks
    Then session "ada-dm" has 1 transcript entries
