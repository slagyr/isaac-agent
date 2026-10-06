Feature: Session selection at admission
  A turn addressed by crew or tags is held WITHOUT a session. At each
  wake Agent re-resolves its frequencies: the matching sessions, minus
  those already running a turn, in :prefer order; the first free one
  wins, then its resource pools. A match that is only busy is not
  missing — :create :if-missing waits instead of creating. A turn
  waiting on sessions holds no pool leases. An explicit :session stays
  bound and waits for that session. `turns list` shows each held turn's
  target. (isaac-l3vb)

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value             |
      | model | echo              |
      | soul  | You are a pirate. |
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
      | slipway | ketch |

  Scenario: a free candidate wins over a busy one
    Given the following model responses are queued:
      | type | content   | model | wait |
      | text | Hold fast | echo  | true |
      | text | On deck.  | echo  |      |
    When the user sends "keep watch" on session "mooring"
    And isaac is run with "prompt --crew ketch -m 'Status?'"
    Then the stdout contains "On deck."
    And the exit code is 0
    And session "slipway" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | On deck.        |
    And session "mooring" has transcript not matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |

  Scenario: with every candidate busy the turn waits and takes whichever frees first
    Given the following model responses are queued:
      | type | content    | model | wait |
      | text | Hold fast  | echo  | true |
      | text | Trim sails | echo  | true |
      | text | On deck.   | echo  |      |
    When the user sends "keep watch" on session "mooring"
    And the user sends "trim the sails" on session "slipway"
    And isaac is run with "prompt --crew ketch -m 'Status?'"
    Then the stdout contains "held"
    And the exit code is 0
    And the session count is 2
    When isaac is run with "turns list"
    Then the stdout matches:
      | target     | state |
      | crew ketch | held  |
    When the turn ends on session "mooring"
    Then session "mooring" has transcript matching:
      | type    | message.role | message.content | #comment                                        |
      | message | user         | Status?         | slipway is preferred (:recent) but still busy   |
      | message | assistant    | On deck.        |                                                 |
    And session "slipway" has transcript not matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |

  Scenario: a turn waiting on sessions holds no pool lease
    Given a scripted resource pool "dock" admits 1 turn at a time
    And the following sessions exist:
      | name    |
      | lookout |
    And the following model responses are queued:
      | type | content       | model | wait |
      | text | Hold fast     | echo  | true |
      | text | Trim sails    | echo  | true |
      | text | Lookout reply | echo  |      |
    When the user sends "keep watch" on session "mooring"
    And the user sends "trim the sails" on session "slipway"
    And isaac is run with "prompt --crew ketch --pool dock -m 'Load cargo'"
    Then the stdout contains "held"
    When isaac is run with "prompt --session lookout --pool dock -m 'Anything on the horizon?'"
    Then the stdout contains "Lookout reply"
    And the exit code is 0

  Scenario: two hails to the same frequencies claimed in one tick land on different sessions (isaac-r209)
    Admission must reserve the session it picks the moment the queue claims
    the charge, not when the drive later accepts the turn — otherwise two
    charges claimed in the same pass can both see every matching session
    as idle and pile onto the same one.
    Given the following sessions exist:
      | name       | crew  | updated-at          |
      | mooring    | ketch | 2026-10-02T09:00:00 |
      | slipway    | ketch | 2026-10-02T09:05:00 |
      | forecastle | ketch | 2026-10-02T09:10:00 |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye one | echo  |
      | text | Aye two | echo  |
    When a turn with input "Haul one" is submitted to crew "ketch"
    And a turn with input "Haul two" is submitted to crew "ketch"
    And the turn queue ticks at "2026-10-02T09:15:00Z"
    Then session "forecastle" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Haul one         |
    And session "slipway" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Haul two         |
    And session "mooring" has transcript not matching:
      | type    | message.role | message.content   |
      | message | user         | #"Haul (one|two)" |

  Scenario: with every matching session busy a hail waits, then runs as its own turn on the session that frees (isaac-r209)
    Separate hails never merge into a running turn (decision, 2026-10-02) —
    even one that waited for a session to free runs as ITS OWN turn, never
    folded into the turn that freed the session.
    Given the following model responses are queued:
      | type | content    | model | wait |
      | text | Hold fast  | echo  | true |
      | text | Trim sails | echo  | true |
      | text | On deck.   | echo  |      |
    When the user sends "keep watch" on session "mooring"
    And the user sends "trim the sails" on session "slipway"
    And a turn with input "Status?" is submitted to crew "ketch"
    And the turn queue ticks at "2026-10-02T09:15:00Z"
    When isaac is run with "turns list"
    Then the stdout matches:
      | target     | state |
      | crew ketch | held  |
    When the turn ends on session "mooring"
    Then session "mooring" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | On deck.        |
    When isaac is run with "turns show #turn-id"
    Then the stdout does not contain "merged-into"

  Scenario: an empty match with create never fails immediately and never waits (isaac-r209)
    When a turn with input "Signal the fleet" is submitted to crew "ghost" with create "never"
    Then the hail submission failed with "no session for crew: ghost"
    And the hail submission did not wait

  Scenario: an empty match with create if-missing creates a session and runs there (isaac-r209)
    Given the isaac EDN file "config/crew/lookout.edn" exists with:
      | path  | value              |
      | model | echo               |
      | soul  | You are a lookout. |
    And the following model responses are queued:
      | type | content   | model |
      | text | Aye, aye. | echo  |
    When a turn with input "Signal the fleet" is submitted to crew "lookout" with create "if-missing"
    And the turn queue ticks at "2026-10-02T09:15:00Z"
    Then the session count is 3
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | finished |
      | ok       |

  Scenario: a free session held by a pool-busy charge is not reserved — another charge can still use it (isaac-r209)
    Given a scripted resource pool "dock" admits 1 turn at a time
    And the following model responses are queued:
      | type | content       | model | wait |
      | text | Tied off      | echo  | true |
      | text | Lookout reply | echo  |      |
    When the user sends "Come alongside" on session "mooring" with resource pools "dock"
    And a turn with input "Load cargo" is submitted to crew "ketch" with resource pools "dock"
    And the turn queue ticks at "2026-10-02T09:15:00Z"
    Then session "slipway" has transcript not matching:
      | type    | message.role | message.content |
      | message | user         | Load cargo       |
    When isaac is run with "prompt --session slipway -m 'Anything on the horizon?'"
    Then the stdout contains "Lookout reply"
    And the exit code is 0

  Scenario: an explicit session stays bound even when another candidate is free
    Given the following model responses are queued:
      | type | content       | model | wait |
      | text | Hold fast     | echo  | true |
      | text | Mooring again | echo  |      |
    When the user sends "keep watch" on session "mooring"
    And isaac is run with "prompt --session mooring -m 'Status?'"
    Then the exit code is 0
    When isaac is run with "turns list"
    Then the stdout matches:
      | target  | state |
      | mooring | held  |
    When the turn ends on session "mooring"
    Then session "mooring" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | Mooring again   |
    And session "slipway" has transcript not matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |

  @wip
  Scenario: a named session that is missing is created with the crew its frequencies name (isaac-oas8)
    :crew picks the session; when :create makes it, :crew is the new
    session's crew. A Foreman bean-work turn is addressed this way:
    {:crew "scrapper" :session "bean-<id>" :create :if-missing}.
    Given the isaac EDN file "config/crew/lookout.edn" exists with:
      | path  | value              |
      | model | echo               |
      | soul  | You are a lookout. |
    And the following model responses are queued:
      | type | content   | model |
      | text | Aye, aye. | echo  |
    When a turn with input "Signal the fleet" is submitted with frequencies:
      | key     | value      |
      | session | crows-nest |
      | crew    | lookout    |
      | create  | if-missing |
    And the turn queue ticks at "2026-10-02T09:15:00Z"
    Then the following sessions match:
      | name       | crew    |
      | crows-nest | lookout |
    And session "crows-nest" has transcript matching:
      | type    | message.role | message.content  |
      | message | user         | Signal the fleet |
      | message | assistant    | Aye, aye.        |
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | finished |
      | ok       |
