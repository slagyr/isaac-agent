Feature: System-section extension
  Modules contribute sections of the system prompt by declaring
  :isaac.agent/system-sections {<id> {:factory ... :order N}} in their
  manifest. Each turn the agent asks every contributor for its text, handing
  it the turn's facts (crew, cwd, config), and joins the results after the
  soul in ascending :order (section id breaks ties), so an unchanged set of
  sections never busts the prompt cache.

  A section may also grant tools for the turn. A contributor that throws is
  skipped with a warning; it never fails the turn.

  The built-in sections sit on the same berth: boot files (100), rules (200)
  and the skill menu (300). A module's section defaults to 500.

  Fixtures: isaac.section.beacon contributes the section "Beacon lit for
  <crew id>." and the tool beacon__ping, which that section grants.
  isaac.section.squall contributes a section whose contributor always throws.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.section.beacon {:local/root "modules/isaac.section.beacon"}}}
      """
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path           | value            |
      | model          | claude           |
      | provider       | grover:anthropic |
      | context-window | 200000           |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | claude                                   |
      | soul  | You are Hieronymus, the ship's botanist. |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |

  Scenario: a module's section appears in the cached system prompt, built from the turn's crew
    Then the prompt "Status?" on session "greenhouse" matches:
      | key                          | value                                  | #comment                        |
      | system[0].text               | #"(?s).*Beacon lit for hieronymus\..*" | contributor was handed the crew |
      | system[0].cache_control.type | ephemeral                              | sits in the cached prefix       |

  Scenario: sections render in their declared order, after the soul
    Given the isaac file "prompts/rules/airlock.md" exists with:
      """
      ---
      type: rule
      description: Airlock discipline
      ---
      Seal both doors before cycling.
      """
    Then the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                                                  | #comment                      |
      | system[0].text | #"(?s).*ship's botanist.*Seal both doors.*Beacon lit for hieronymus.*" | soul, then rules, then module |

  Scenario: a section can grant tools for the turn
    Then the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                  |
      | system[0].text | #"(?s).*Beacon lit for hieronymus\..*" |
    And the prompt has tools:
      | name         | #comment                                   |
      | beacon__ping | granted by the section, not by crew config |

  Scenario: a contributor that throws is skipped and the turn still runs
    Given the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :modules {:isaac.section.beacon {:local/root "modules/isaac.section.beacon"}
                 :isaac.section.squall {:local/root "modules/isaac.section.squall"}}}
      """
    And the following model responses are queued:
      | type | content      | model  |
      | text | All is well. | grover |
    When the user sends "Status?" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | All is well.    |
    And the log has entries matching:
      | level | event                  | section | module               |
      | :warn | :system-section/failed | squall  | isaac.section.squall |
    And the prompt "Status?" on session "greenhouse" matches:
      | key            | value                                  | #comment                    |
      | system[0].text | #"(?s).*Beacon lit for hieronymus\..*" | healthy section still there |

  Scenario: each section's tokens are reported separately
    Given the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :modules {:isaac.section.beacon {:local/root "modules/isaac.section.beacon"}}}
      """
    And the following model responses are queued:
      | type | content | model  |
      | text | Noted   | grover |
    When the user sends "Status?" on session "greenhouse"
    Then the log has entries matching:
      | level | event              | session    | beacon-tokens |
      | :info | :turn/request-sent | greenhouse | #"[1-9]\d*"   |

  Scenario: activating the module registers its section
    Given the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :modules {:isaac.section.beacon {:local/root "modules/isaac.section.beacon"}}}
      """
    When manifest berths are processed for the loaded config
    Then the log has entries matching:
      | level | event               | berth                        | entry  | module               |
      | :info | :berth/registration | :isaac.agent/system-sections | beacon | isaac.section.beacon |
