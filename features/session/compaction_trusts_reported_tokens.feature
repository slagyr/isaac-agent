@wip
Feature: Compaction trusts the provider's reported prompt tokens

  Compaction decides whether to fold history before the next LLM request.
  isaac-o13p: feed the trigger from the provider's last reported prompt
  tokens when one is available, not from a declared/estimated gauge that
  can drift from it. isaac-izc1 is removing claude-code's fence mode, so
  the provider-driven tool loop (grover "drives its own tool loop") is THE
  claude-code path this now has to hold up under.

  Background:
    Given an Isaac root at "target/test-state"
    Given default Grover setup
    Given config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | hi               |

  Scenario: a first turn with no reported usage yet relies on the existing estimate
    Given the following sessions exist:
      | name    |
      | opening |
    And session "opening" has transcript:
      | type    | message.role | message.content                                                              | tokens |
      | message | user         | block A: planning notes about logging, tools, and the dispatch loop          | 45     |
      | message | assistant    | reply A: we agreed on output sinks, the compaction trigger, and tool dispatch | 45     |
    And the following model responses are queued:
      | type | content          | model      |
      | text | summary of block | test-model |
      | text | here is my reply | test-model |
    When the user sends "go" on session "opening"
    Then session "opening" has transcript matching:
      | type       | message.role | message.content  | summary          |
      | compaction |              |                   | summary of block |
      | message    | assistant    | here is my reply |                  |

  Scenario: a driven loop's declared gauge must not outrank its own reported prompt tokens
    # isaac-o13p: claude-code's provider-driven loop stamps a declared
    # first-cycle gauge (:gauge-prompt-tokens) ahead of the turn's real
    # reported usage (isaac-6ef2's `(or declared-gauge-tokens
    # provider-prompt-tokens)` priority in turn.clj). The contract is that
    # the real reported usage (85) wins over the declared gauge (20), so
    # the next turn compacts. Grover cannot yet declare a gauge separate
    # from its real usage, so `usage.gauge_prompt_tokens` below is a no-op
    # today and this scenario passes trivially (last-input-tokens just
    # takes the only number Grover reports, 85). Once the implementing
    # worker wires `usage.gauge_prompt_tokens` into Grover's driven-loop
    # path, this scenario goes RED against today's declared-gauge-tokens
    # priority (it would stamp 20, not 85) — and the real isaac-o13p fix
    # (making reported usage win) is what turns it back GREEN.
    Given the built-in tools are registered
    And the crew "main" allows tools: "exec/run"
    And the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name   |
      | driven |
    And the following model responses are queued:
      | type      | tool_call | arguments               | content | model      | usage.input_tokens | usage.gauge_prompt_tokens |
      | tool_call | exec__run | {"command": "echo hi"}  |         | test-model | 40                 |                           |
      | text      |           |                         | done    | test-model | 85                 | 20                        |
    When the user sends "run it" on session "driven"
    Then session "driven" matches:
      | key               | value |
      | last-input-tokens | 85    |
    And the following model responses are queued:
      | type | content            | model      |
      | text | summary of driven  | test-model |
      | text | here is my answer  | test-model |
    When the user sends "again" on session "driven"
    Then session "driven" has transcript matching:
      | type       | message.role | message.content   | summary           |
      | compaction |              |                    | summary of driven |
      | message    | assistant    | here is my answer  |                   |

  Scenario: a driven loop whose reported usage stays under threshold does not compact
    Given the built-in tools are registered
    And the crew "main" allows tools: "exec/run"
    And the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name  |
      | quiet |
    And the following model responses are queued:
      | type      | tool_call | arguments               | content | model      | usage.input_tokens |
      | tool_call | exec__run | {"command": "echo hi"}  |         | test-model | 30                  |
      | text      |           |                         | done    | test-model | 50                  |
    When the user sends "run it" on session "quiet"
    Then session "quiet" matches:
      | key               | value |
      | last-input-tokens | 50    |
    And the following model responses are queued:
      | type | content   | model      | usage.input_tokens |
      | text | still ok  | test-model | 55                  |
    When the user sends "again" on session "quiet"
    Then session "quiet" matches:
      | key               | value |
      | last-input-tokens | 55    |
    And session "quiet" has transcript not matching:
      | type       | message.role | message.content |
      | compaction |              |                  |
