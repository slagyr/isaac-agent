Feature: Session observers watch a session's record off the turn path (isaac-c52a)
  A session observer is contributed through the :isaac.agent/session-observer
  berth. A crew lists its observers under :observers; a session may
  override the list. Never per turn: observers keep state across turns.
  Each observer sees a session's events in order on its own queue, off
  the turn path. A slow observer never delays a reply (unit spec), and a
  failing observer raises attention but never fails the turn. Observers
  watch; what the model sees belongs to the context mode.
  Events: session-opened, message-appended, compaction-spliced,
  turn-started, turn-ended. Test harnesses drain observer queues before
  asserting, the way they already await the turn.
  Fixture: the lantern module contributes the `logbook` observer, which
  appends every event it sees to lantern/logbook.edn as {:events [...]}.
  With :lantern {:logbook {:fail true}} it throws on every event instead.
  Decision (2026-10-04, Micah).

  Background:
    Given default Grover setup
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value                                                                |
      | modules | {:isaac.session.lantern {:local/root "modules/isaac.session.lantern"}} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path      | value            |
      | model     | echo             |
      | soul      | You are Cordelia |
      | observers | [:logbook]       |

  @wip
  Scenario: a crew's observer sees a turn's events in order
    Given the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 0
    And the isaac file "lantern/logbook.edn" EDN contains:
      | path        | value                                                            |
      | events      | #"session-opened"                                                |
      | events      | #"lantern-room"                                                  |
      | events      | #"(?s)session-opened.*turn-started.*message-appended.*user.*message-appended.*assistant.*turn-ended" |

  @wip
  Scenario: a crew without observers notifies none
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path      | value   |
      | observers | #delete |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Status?'"
    Then the exit code is 0
    And the isaac file "lantern/logbook.edn" does not exist

  @wip
  Scenario: a session's observers override the crew's
    Given a session "lantern-room" exists with observers "[]"
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Status?'"
    Then the exit code is 0
    And the isaac file "lantern/logbook.edn" does not exist

  @wip
  Scenario: an observer sees the compaction splice
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value |
      | model | local |
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
    And the isaac file "lantern/logbook.edn" EDN contains:
      | path   | value                                                                                  |
      | events | #"(?s)turn-started.*compaction-spliced.*message-appended.*message-appended.*turn-ended" |

  @wip
  Scenario: a failing observer raises attention and the turn still replies
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
      | lantern.logbook.fail    | true        |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When the user sends "Light the lamp" on session "lantern-room"
    Then the turn result is "reply"
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Light the lamp  |
      | message | assistant    | Lit             |
    And the log has entries matching:
      | level  | event                   | observer | session-id   |
      | :error | :session/observer-error | logbook  | lantern-room |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                 |
      | target  | boiler-room                           |
      | content | contains "logbook" and "lantern-room" |

  @wip
  Scenario: an unknown observer fails config validation
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path      | value     |
      | observers | [:ledger] |
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                               |
      | crew\.cordelia\.observers                                             |
      | references undefined session observer \(got "ledger"\); known: logbook |
    And the exit code is 1
