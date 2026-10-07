Feature: A delivery lands in the transcript of the session that talks on its comm target
  A delivery queue only posts. When a cron job, an attention notice, or
  another session's comm__send posts to a comm target where a crew talks
  with someone, that crew's session never saw it, and the crew answers
  out of context (yopp, 2026-10-06: a cron-style DM, then "I got it."
  read as a reply to something else).
  Inbound records which comm targets a session talks on: the session
  carries :comms, "<comm>:<target>" strings, written by the comm when a
  message arrives. After a delivery posts, the comm reports the target
  it actually posted to (:target on its send result; an email is
  reported as the DM space it resolved to). The worker appends a marked
  note to the session whose :comms holds "<comm>:<target>":
    [sent here by crew <crew> from session <session>] <content>
  A delivery sent by that same session is its own reply, already in its
  transcript, and is not appended again. A target no session talks on is
  skipped: there is no conversation to keep continuous. A failed send
  appends nothing.
  Decisions (2026-10-06, Micah): record at delivery time, in agent; gchat
  keeps dropping its own echoes. Say comms and targets, never channels
  (ISAAC.md): :channels → :comms, send result :channel → :target.

  Background:
    Given an Isaac root at "target/test-state"
    And the following sessions exist:
      | name      | crew    | comms     |
      | ada-dm    | lookout | stub:C999 |

  Scenario: a delivery to a session's comm target is appended to that session as a marked note
    Given the comm "stub" returns:
      | ok   | target  |
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
      | ok   | target  |
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

  Scenario: a delivery to a comm target no session talks on appends nothing
    Given the comm "stub" returns:
      | ok   | target  |
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
      | ok    | transient? | target  |
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

  Scenario: a comm's marker leads the note, so the crew can place it
    A comm whose target has sub-places (gchat threads) reports :marker on
    its send result, the same tag an inbound line from that place carries.
    The note leads with it; a comm that reports none gets the plain note.
    Given the comm "stub" returns:
      | ok   | target | marker        |
      | true | C999   | "[thread:T9]" |
    And the isaac EDN file comm/delivery/pending/7f3e.edn exists with:
      | path    | value               |
      | id      | 7f3e                |
      | comm    | stub                |
      | target  | C999                |
      | content | Your weekly digest. |
      | crew    | herald              |
      | session | cron-heartbeat      |
    When the delivery worker ticks
    Then session "ada-dm" has transcript matching:
      | type    | message.role | message.content                                                                              |
      | message | assistant    | #"\[thread:T9\] \[sent here by crew herald from session cron-heartbeat\] Your weekly digest\." |

  @wip
  Scenario: an attention notice is named as attention, not an empty crew and session
    Yopp, 2026-10-07: an attention notice landed as "[sent here by crew  from
    session ] …". Attention deliveries carry no crew or session; they carry
    :origin {:kind :attention}, and the note names that instead.
    Given the comm "stub" returns:
      | ok   | target |
      | true | C999   |
    And the isaac EDN file comm/delivery/pending/7f3f.edn exists with:
      | path        | value                                        |
      | id          | 7f3f                                         |
      | comm        | stub                                         |
      | target      | C999                                         |
      | content     | Session observer :episodes failed for x      |
      | origin.kind | :attention                                   |
    When the delivery worker ticks
    Then session "ada-dm" has transcript matching:
      | type    | message.role | message.content                                                         |
      | message | assistant    | #"\[sent here by attention\] Session observer :episodes failed for x" |
