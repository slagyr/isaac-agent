Feature: Context modes are registered through a berth (isaac-c52a)
  :context-mode picks how a turn's context is built. Agent registers
  :full and :reset through the :isaac.agent/context-mode berth; a module
  may contribute more. Resolution is unchanged: --with-context-mode for
  one turn, then the session, then the crew, then defaults.
  A registration may declare the session observers it needs:
    {:porthole {:factory … :requires {:observers #{:logbook}}}}
  Config validation reports a crew whose context mode needs an observer
  it does not have, and a turn on a session in that state fails before it
  appends. Agent knows no module by name; the requirement is registry data.
  Fixture: the lantern module contributes the `porthole` context mode
  (the soul, the last assistant reply and the current message), which
  requires its `logbook` observer.
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
    And the following sessions exist:
      | name         | crew     |
      | lantern-room | cordelia |
    And session "lantern-room" has transcript:
      | type    | message.role | message.content  |
      | message | user         | Is the lamp lit? |
      | message | assistant    | Lit and trimmed. |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |

  @wip
  Scenario: a crew selects a contributed context mode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value    |
      | context-mode | porthole |
    When the user sends "Check the oil" on session "lantern-room"
    Then the last LLM request matches:
      | key                 | value            |
      | messages[1].role    | assistant        |
      | messages[1].content | Lit and trimmed. |
      | messages[2].role    | user             |
      | messages[2].content | Check the oil    |
    And the last LLM request does not contain "Is the lamp lit?"

  @wip
  Scenario: a session's context mode overrides the crew's
    Given a session "lantern-room" exists with context-mode "porthole"
    When the user sends "Check the oil" on session "lantern-room"
    Then the last LLM request does not contain "Is the lamp lit?"
    And the last LLM request matches:
      | key                 | value         |
      | messages[2].content | Check the oil |

  @wip
  Scenario: --with-context-mode selects a contributed mode for one turn
    When isaac is run with "prompt --session lantern-room --with-context-mode porthole -m 'Check the oil'"
    Then the exit code is 0
    And the last LLM request does not contain "Is the lamp lit?"

  @wip
  Scenario: a turn fails when its session lacks the observer its context mode requires
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value    |
      | context-mode | porthole |
    And a session "lantern-room" exists with observers "[]"
    When isaac is run with "prompt --session lantern-room -m 'Check the oil'"
    Then the exit code is 1
    And the stderr matches:
      | pattern                    |
      | lantern-room               |
      | porthole                   |
      | requires observer :logbook |
    And session "lantern-room" has 2 transcript entries

  @wip
  Scenario: validation reports a context mode whose required observer the crew lacks
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value    |
      | context-mode | porthole |
      | observers    | #delete  |
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                          |
      | crew\.cordelia\.context-mode                                     |
      | context mode :porthole requires observer :logbook; crew has none |
    And the exit code is 1

  @wip
  Scenario: validation accepts a context mode whose required observer is present
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value    |
      | context-mode | porthole |
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  @wip
  Scenario: an unknown context mode names the registered set
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value  |
      | context-mode | ponder |
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                                          |
      | crew\.cordelia\.context-mode                                                     |
      | references undefined context mode \(got "ponder"\); known: full, porthole, reset |
    And the exit code is 1
