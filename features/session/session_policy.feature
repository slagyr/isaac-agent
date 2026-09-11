Feature: Session policy berth — chronicle and episodes are per-crew policies over a primitive session store (isaac-mmod)
  A session id is the surface's identity inside and out. Two protocols: the
  session STORE is root-level persistence (disk, memory, a database later)
  exposing primitives addressed by ids — session record, transcript streams
  and container records per session+container, container documents, crew
  documents, the sessions index. A session POLICY is what the bridge, drive,
  comms and tools talk to, implemented per crew on top of those primitives:
  chronicle = one container per session; episodes = a container per episode,
  opened on a cold first append (recall injected ahead of the message), sealed
  on the turn-marker clear, closed and chained on the compaction methods.
  A crew selects with :session-policy (:chronicle when absent; an unknown name
  is a config error). Callers never resolve episodes; the session id never
  changes across a turn or a compaction. Fixture: `logbook` is a recording
  policy over the chronicle policy.
  Decisions (2026-09-09, Micah): berth `:isaac.agent/session-policy`; the
  policy protocol gains `default-session`; `thread` is retired — episode
  records carry :session-id; the disk store's layout is isaac-b6w0.

  Background:
    Given default Grover setup

  Scenario: a crew selects a session policy by name
    Given a recording session policy "logbook" is registered
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | logbook          |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Then the logbook policy recorded calls matching:
      | method              | session-id   |
      | open-session!       | lantern-room |
      | record-turn-marker! | lantern-room |
      | append-message!     | lantern-room |
      | append-message!     | lantern-room |
      | clear-turn-marker!  | lantern-room |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |

  Scenario: a crew with no session-policy setting is a chronicle
    Given a recording session policy "logbook" is registered
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When a charge is dispatched with:
      | key         | value        |
      | session-key | lantern-room |
      | crew        | main         |
      | input       | Status?      |
    Then the following sessions match:
      | id           | crew |
      | lantern-room | main |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | Aye             |
    And the logbook policy recorded no calls

  Scenario: an unknown session policy fails config validation
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | ledger           |
    When isaac is run with "config validate"
    Then the config has validation errors matching:
      | key                         | value                                                                 |
      | crew.cordelia.session-policy | references undefined session policy \(got "ledger"\); known: chronicle |

  Scenario: the session id is stable across compaction
    Given a recording session policy "logbook" is registered
    And the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | local            |
      | soul          | You are Cordelia |
      | session-policy | logbook          |
    And the following sessions exist:
      | name         | crew     | last-input-tokens |
      | lantern-room | cordelia | 85                |
    And session "lantern-room" has transcript:
      | type    | message.role | message.content  |
      | message | user         | old message one  |
      | message | assistant    | old response one |
      | message | user         | old message two  |
      | message | assistant    | old response two |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Full summary of prior | test-model |
      | text | New response          | test-model |
    When the user sends "new input" on session "lantern-room"
    Then session "lantern-room" has compaction
    And the logbook policy recorded calls matching:
      | method             | session-id   |
      | splice-compaction! | lantern-room |
      | append-message!    | lantern-room |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | new input       |
      | message | assistant    | New response    |

  Scenario: turn markers are the store's turn signals, for the CLI too
    The bridge records a turn marker at dispatch and clears it at turn end
    for every origin. A policy that needs a turn-end signal (episodes seals
    on it) reads the clear; the CLI is no longer exempt.
    Given a recording session policy "logbook" is registered
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | logbook          |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 0
    And the stdout contains "Lit"
    And the logbook policy recorded calls matching:
      | method              | session-id   |
      | record-turn-marker! | lantern-room |
      | append-message!     | lantern-room |
      | append-message!     | lantern-room |
      | clear-turn-marker!  | lantern-room |

  Scenario: an episodes crew keeps its session id through a cold open
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | episodes         |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Then an episode exists for crew "cordelia" matching:
      | key        | value                          |
      | id         | #"\d{17}" |
      | status     | open                           |
      | session-id | lantern-room                   |
    And the following sessions match:
      | id           | crew     |
      | lantern-room | cordelia |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |

  Scenario: a warm second turn on an episodes crew appends to the open episode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | episodes         |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | Wick trimmed       | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Given the current time is "2026-03-01T10:10:00"
    When a charge is dispatched with:
      | key         | value         |
      | session-key | lantern-room  |
      | crew        | cordelia      |
      | input       | Trim the wick |
    Then crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key        | value        |
      | status     | open         |
      | session-id | lantern-room |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |
      | message | user         | Trim the wick      |
      | message | assistant    | Wick trimmed       |

  Scenario: a conversation start without a session id asks the policy for one
    Chronicle answers the crew's existing session; episodes answers a fresh
    id (isaac-6yg0's acp scenario stays the proof of that side). Frequencies
    and ACP session/new no longer branch on the crew's mode themselves.
    Given a recording session policy "logbook" is registered
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path          | value            |
      | model         | echo             |
      | soul          | You are Cordelia |
      | session-policy | logbook          |
    And the following sessions exist:
      | name         | crew     |
      | lantern-room | cordelia |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia -m 'Light the lamp'"
    Then the exit code is 0
    And the logbook policy recorded calls matching:
      | method              | crew     | session-id   |
      | default-session     | cordelia |              |
      | record-turn-marker! |          | lantern-room |
      | append-message!     |          | lantern-room |
      | clear-turn-marker!  |          | lantern-room |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Light the lamp  |
      | message | assistant    | Lit             |
