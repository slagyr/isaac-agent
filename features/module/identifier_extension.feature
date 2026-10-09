@wip
Feature: Identifier extension — a module names the party behind a handle
  A handle is how one comm names the party on the other end of a message:
  {:kind :handle :comm … :id …}. Agent does not know who that is. Modules
  that do declare :isaac.agent/identifiers {<id> {:factory …}} in their
  manifest. Just before a turn record is stored, Agent hands each handle
  in :from and :for to the identifiers; one that knows the party returns
  a replacement map, and that is stored instead.

  An identifier that returns nothing leaves the handle as it arrived. One
  that throws is skipped with a warning; it never fails the turn. The
  comm's :authenticated claim survives identification: an identifier can
  name a party, never vouch for one. An identifier must be a plain lookup
  that does not block.

  Fixtures: isaac.roster.almanac knows one handle, cordelia-7 on logbook,
  as {:kind :contact :id "cordelia"} and claims every party is
  authenticated. isaac.roster.fog always throws.

  Tracked by isaac-s715.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value                                                                |
      | modules | {:isaac.roster.almanac {:local/root "modules/isaac.roster.almanac"}} |
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: an identifier replaces a handle it knows, in both from and for
    When a turn is submitted with:
      | key                | value        |
      | id                 | tide-9       |
      | input              | Leave harbor |
      | session            | harbor       |
      | from.kind          | handle       |
      | from.comm          | logbook      |
      | from.id            | cordelia-7   |
      | from.authenticated | true         |
    And isaac is run with "turns show tide-9"
    Then the stdout matches:
      | from.kind: contact       |
      | from.id: cordelia        |
      | from.authenticated: true |
      | for.kind: contact        |
      | for.id: cordelia         |
    And the stdout does not contain "cordelia-7"

  Scenario: a handle no identifier knows is stored as it arrived
    When a turn is submitted with:
      | key       | value        |
      | id        | tide-9       |
      | input     | Leave harbor |
      | session   | harbor       |
      | from.kind | handle       |
      | from.comm | logbook      |
      | from.id   | stranger-1   |
    And isaac is run with "turns show tide-9"
    Then the stdout matches:
      | from.kind: handle   |
      | from.id: stranger-1 |

  Scenario: an identifier cannot make an unverified party verified
    When a turn is submitted with:
      | key                | value        |
      | id                 | tide-9       |
      | input              | Leave harbor |
      | session            | harbor       |
      | from.kind          | handle       |
      | from.comm          | logbook      |
      | from.id            | cordelia-7   |
      | from.authenticated | false        |
    And isaac is run with "turns show tide-9"
    Then the stdout matches:
      | from.kind: contact        |
      | from.id: cordelia         |
      | from.authenticated: false |

  Scenario: an identifier that throws is skipped and the turn is stored with the raw handle
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path       | value                                                        |
      | log.output | :memory                                                      |
      | modules    | {:isaac.roster.fog {:local/root "modules/isaac.roster.fog"}} |
    When a turn is submitted with:
      | key       | value        |
      | id        | tide-9       |
      | input     | Leave harbor |
      | session   | harbor       |
      | from.kind | handle       |
      | from.comm | logbook      |
      | from.id   | cordelia-7   |
    And isaac is run with "turns show tide-9"
    Then the stdout matches:
      | from.kind: handle   |
      | from.id: cordelia-7 |
    And the log has entries matching:
      | level | event              | module           |
      | :warn | :identifier/failed | isaac.roster.fog |
