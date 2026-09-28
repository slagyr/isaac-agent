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
