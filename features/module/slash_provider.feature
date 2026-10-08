Feature: Slash-command providers
  An entry on the :isaac.agent/slash-commands berth is a provider, not a
  single command. A provider lists the commands it offers for a session and
  answers one by name, either with a reply (no turn) or with an input that
  the bridge runs as a turn. The built-in commands and prompt-template
  commands are providers on the same berth, so the bridge knows no command
  source by name.

  When two providers offer the same name, the lower rank answers. Nobody has
  to state a rank: built-ins default to 100, a module's commands to 500 and
  prompt-template commands to 900. A module may set :rank on its entry, or
  on one command, to move it ahead of or behind the others.

  Fixture: isaac.slash.semaphore offers
    hoist <flag>  starts a turn: "Hoist the <flag> flag and report."
    dip           replies "Flag dipped."
    status        replies "Semaphore status."        (default rank)
    cwd           replies "Semaphore has the conn."  (rank 50)
    tend          replies "Semaphore tends."         (rank 950)

  Background:
    Given default Grover setup
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value                                                                  |
      | modules | {:isaac.slash.semaphore {:local/root "modules/isaac.slash.semaphore"}} |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | echo                                     |
      | soul  | You are Hieronymus, the ship's botanist. |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |

  Scenario: a provider's command can start a turn with its own input
    Given the following model responses are queued:
      | type | content           | model |
      | text | Hoisted, Captain. | echo  |
    When the user sends "/hoist quarantine" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                       |
      | message | user         | Hoist the quarantine flag and report. |
      | message | assistant    | Hoisted, Captain.                     |

  Scenario: a provider's command can reply without starting a turn
    When the user sends "/dip" on session "greenhouse"
    Then the reply contains "Flag dipped."

  Scenario: a provider's commands are advertised alongside the built-ins
    When the user sends "/dip" on session "greenhouse"
    Then the available slash commands include:
      | name   | description            |
      | status | Show session status    |
      | hoist  | Hoist a signal flag    |
      | dip    | Dip the flag in salute |

  Scenario: a built-in outranks a module's command of the same name by default
    When the user sends "/status" on session "greenhouse"
    Then the reply contains "Session Status"
    And the reply does not contain "Semaphore status."

  Scenario: a module's command outranks a prompt-template command of the same name by default
    Given the isaac file "prompts/commands/hoist.md" exists with:
      """
      ---
      type: command
      description: Hoist something from the hold
      params: [cargo]
      ---
      Winch the {{cargo}} up from the hold.
      """
    And the following model responses are queued:
      | type | content           | model |
      | text | Hoisted, Captain. | echo  |
    When the user sends "/hoist quarantine" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                       |
      | message | user         | Hoist the quarantine flag and report. |

  Scenario: a command given a lower rank outranks a built-in
    When the user sends "/cwd" on session "greenhouse"
    Then the reply contains "Semaphore has the conn."

  Scenario: a command given a higher rank yields to a prompt-template command
    Given the isaac file "prompts/commands/tend.md" exists with:
      """
      ---
      type: command
      description: Tend a specimen in the greenhouse
      params: [specimen]
      ---
      Tend the {{specimen}} in the greenhouse.
      """
    And the following model responses are queued:
      | type | content                 | model |
      | text | Tending to it, Captain. | echo  |
    When the user sends "/tend dilithium-orchid" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content                              |
      | message | user         | Tend the dilithium-orchid in the greenhouse. |
      | message | assistant    | Tending to it, Captain.                      |
