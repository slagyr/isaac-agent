Feature: Loop driver — the drive chooses who runs the tool loop
  The drive hands the tool loop one contract: a chat function for a cycle,
  a followup builder, THE drive's tool function (record-tool-call!, which
  persists the toolCall/toolResult pair, fires comm events and honors
  cancellation), and hooks (cycle start/end, after-tools, cancelled?, max
  loops). It gets back {:response :tool-calls :token-counts}. A provider
  that declares :drives-tool-loop? true supplies its own loop driver — for
  claude-cli, Claude Code running the native tool loop against isaac's MCP
  tools — and receives exactly the same inputs. Chronicles and episodes
  never see the difference (isaac-1sdl, epic isaac-tuk1).
  Decisions (2026-09-03, Micah): capability flag in provider config;
  provider-driven loops compact BETWEEN turns only (option A) and log
  :turn/compaction-deferred when after-tools would have fired; max-loops
  maps to the provider's own budget; overflow compact-and-retry stays in
  the drive above the seam.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run"

  Scenario: a provider that drives its own loop gets the drive's tool function and persists the pair identically
    Given the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name   |
      | driven |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | done    | echo  |
    When the user sends "run it" on session "driven"
    Then session "driven" has transcript matching:
      | type       | message.role | message.content | name      |
      | toolCall   |              |                 | exec__run |
      | toolResult |              |                 |           |
      | message    | assistant    | done            |           |
    And the log has entries matching:
      | event                | provider | driver   |
      | :turn/loop-driver    | grover   | provider |

  Scenario: a provider without the flag runs the default loop
    Given the following sessions exist:
      | name    |
      | default |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | done    | echo  |
    When the user sends "run it" on session "default"
    Then session "default" has transcript matching:
      | type       | message.role | message.content |
      | toolCall   |              |                 |
      | toolResult |              |                 |
      | message    | assistant    | done            |
    And the log has entries matching:
      | event             | provider | driver  |
      | :turn/loop-driver | grover   | default |

  Scenario: the driver's per-cycle usage stamps the session after every cycle
    Given the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name   |
      | cycles |
    And the following model responses are queued:
      | type      | tool_call | arguments               | content | model | usage.input_tokens |
      | tool_call | exec__run | {"command": "echo one"} |         | echo  | 300                |
      | tool_call | exec__run | {"command": "echo two"} |         | echo  | 320                |
      | text      |           |                         | done    | echo  | 340                |
    When the user sends "twice" on session "cycles"
    Then the following sessions match:
      | name   | last-input-tokens | turn-input-tokens |
      | cycles | 340               | 960               |

  Scenario: cancel mid-loop reaches the driver and the turn ends cancelled
    Given the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name        |
      | cancel-drvn |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | never   | echo  |
    When the user sends "do stuff" on session "cancel-drvn"
    And the turn is cancelled on session "cancel-drvn" after 1 tool call
    Then the turn result is "cancelled"
    And session "cancel-drvn" has transcript not matching:
      | type    | message.role | message.content |
      | message | assistant    | never           |

  Scenario: a provider-driven loop compacts between turns, not mid-turn
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 1000  |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the provider "grover" drives its own tool loop
    And the following sessions exist:
      | name   | last-input-tokens |
      | ledger | 850               |
    And session "ledger" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | older ask       | 20     |
      | message | assistant    | older reply     | 20     |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content      | model | usage.input_tokens |
      | text      |           |                        | folded older | echo  |                    |
      | tool_call | exec__run | {"command": "echo hi"} |              | echo  | 900                |
      | text      |           |                        | here you go  | echo  | 920                |
    When the user sends "and again" on session "ledger"
    Then session "ledger" has transcript matching:
      | type       | message.role | message.content | summary      |
      | compaction |              |                 | folded older |
      | toolCall   |              |                 |              |
      | toolResult |              |                 |              |
      | message    | assistant    | here you go     |              |
    And the log has entries matching:
      | event                     | session | reason          |
      | :turn/compaction-deferred | ledger  | provider-driven |
