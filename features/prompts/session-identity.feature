Feature: Session identity in the cached system prompt
  Every turn's system prompt carries the session's identity — Session and
  Crew — alongside soul, boot files, and rules. Identity is ambient (system
  prompt), events are per-turn (trusted block): the block renders once per
  request, joins the cached prefix, never varies for an unchanged session,
  and is never stored in the transcript.

  (Bean: isaac-s0ho. Design settled with Micah 2026-07-02: identity in the
  system prompt with soul/AGENTS.md etc.; per-turn guidance stays reserved
  for genuinely per-turn data like hail deliveries.)

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path           | value            |
      | model          | claude           |
      | provider       | grover:anthropic |
      | context-window | 200000           |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value                                    |
      | model | claude                                   |
      | soul  | You are Hieronymus, the ship's botanist. |

  Scenario: every turn's cached system prompt carries the session identity
    Given the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    Then the prompt "Status?" on session "greenhouse" matches:
      | key                          | value                            | #comment                       |
      | system[0].text               | #"(?s).*Session:\s*greenhouse.*" | identity ambient, not per-turn |
      | system[0].text               | #"(?s).*Crew:\s*hieronymus.*"    |                                |
      | system[0].cache_control.type | ephemeral                        | part of the cached prefix      |

  Scenario: an unchanged session renders byte-identical system text across turns
    Given the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the following model responses are queued:
      | type | content  | model  |
      | text | Nominal. | claude |
      | text | Still.   | claude |
    When the user sends "Status?" on session "greenhouse"
    And the user sends "And now?" on session "greenhouse"
    Then the system text of the last 2 chat requests on session "greenhouse" is identical

  Scenario: identity lives in the system prompt only — never stored in the transcript
    Given the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the following model responses are queued:
      | type | content  | model  |
      | text | Nominal. | claude |
    When the user sends "Status?" on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | message.role | message.content | #comment                                 |
      | user         | Status?         | exact match — no identity lines injected |
      | assistant    | Nominal.        |                                          |
