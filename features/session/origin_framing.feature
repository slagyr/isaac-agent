Feature: Per-turn origin framing
  A dispatched turn can carry origin framing — a behavioral guidance string and
  origin metadata — set on the charge (:guidance + :origin) by whoever
  dispatches it. The framing helper renders the metadata generically, combines
  it with the guidance, tags it with the session nonce (see
  features/session/injection_guard.feature), and injects it as a block into the
  CURRENT user turn. It is never persisted to the transcript, so a multi-origin
  conversation (CLI + hail + cron interleaved) never accumulates stale guidance
  and the cached history stays clean. The framing block is uncached — the cache
  breakpoint stays on the origin-free historical message.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path           | value            |
      | model          | claude           |
      | provider       | grover:anthropic |
      | context-window | 200000           |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value                |
      | model | claude               |
      | soul  | You are Bartholomew. |

  Scenario: a turn with origin + guidance frames the current user turn, not the system prompt
    Given the following sessions exist:
      | name      | crew        | nonce        |
      | engine-rm | bartholomew | N0NCE-abc123 |
    Then the prompt "Seal the leak." on session "engine-rm" with origin {:kind :hail :hail-id "hail-1"} and guidance "Autonomous hail; the user may not see your reply." matches:
      | key                         | value                                            | #comment                                                |
      | messages[0].content[0].text | #"(?s).*N0NCE-abc123.*Autonomous hail.*hail-1.*" | nonce-tagged block: guidance + rendered origin metadata |
      | messages[0].content[1].text | Seal the leak.                                   | user's text, separate block                             |
      | messages[0].cache_control   |                                                  | origin-bearing turn not cached                          |

  Scenario: the framing block is current-turn-only; history stays clean and cacheable
    Given the following sessions exist:
      | name      | crew        | nonce        |
      | engine-rm | bartholomew | N0NCE-abc123 |
    And session "engine-rm" has transcript:
      | type    | message.role | message.content |
      | message | user         | First request.  |
      | message | assistant    | First reply.    |
    Then the prompt "Seal the leak." on session "engine-rm" with origin {:kind :hail :hail-id "hail-2"} and guidance "Autonomous hail; the user may not see your reply." matches:
      | key                                       | value                                            | #comment                                         |
      | messages[0].content[0].text               | First request.                                   | historical user turn — clean, no framing block   |
      | messages[0].content[0].cache_control.type | ephemeral                                        | breakpoint on the origin-free historical message |
      | messages[2].content[0].text               | #"(?s).*N0NCE-abc123.*Autonomous hail.*hail-2.*" | current turn's framing block — the only one      |
      | messages[2].content[1].text               | Seal the leak.                                   | current user text                                |
      | messages[2].content[1].cache_control      |                                                  | current turn not cached                          |
