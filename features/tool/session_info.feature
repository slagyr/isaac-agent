Feature: session_info tool
  The session_info tool reports the current session's crew, model,
  provider, session key, working directory, origin, timing, context
  usage, and compaction count. Read-only — mutation lives in
  session_model. Always operates on the calling session; the runtime
  injects the session key into every tool call, so the LLM cannot
  address another session through this tool.

  Background:
    Given default Grover setup

  Scenario: session_info reports current crew, model, provider, origin, and timing
    Given the current time is "2026-04-28T10:00:00Z"
    And the following sessions exist:
      | name        | crew | cwd           |
      | status-test | main | /work/project |
    And the current session is "status-test"
    When the tool "session_info" is called
    Then the tool result is not an error
    And the tool result JSON has:
      | path           | value                |
      | crew           | main                 |
      | model.alias    | grover               |
      | model.upstream | echo                 |
      | provider       | grover               |
      | session        | status-test          |
      | cwd            | /work/project        |
      | origin.kind    | cli                  |
      | created_at     | 2026-04-28T10:00:00Z |
      | updated_at     | 2026-04-28T10:00:00Z |
      | context.used   | 0                    |
      | context.window | 32768                |
      | compactions    | 0                    |

  Scenario: session_info reports origin name when the session was started by a webhook
    Given the following sessions exist:
      | name         | crew | origin.kind | origin.name |
      | hook:lettuce | main | webhook     | lettuce     |
    And the current session is "hook:lettuce"
    When the tool "session_info" is called
    Then the tool result is not an error
    And the tool result JSON has:
      | path        | value   |
      | origin.kind | webhook |
      | origin.name | lettuce |

  Scenario: session_info resolves model when session :model holds the upstream name
    Given the isaac file "config/providers/hieronymus.edn" exists with:
      """
      {:api "grover" :auth "none"}
      """
    And the isaac file "config/models/lettuce.edn" exists with:
      """
      {:model "lettuce-grande" :provider :hieronymus :context-window 128000}
      """
    And the following sessions exist:
      | name       | crew | model          |
      | salad-bowl | main | lettuce-grande |
    And the current session is "salad-bowl"
    When the tool "session_info" is called
    Then the tool result is not an error
    And the tool result JSON has:
      | path           | value          |
      | model.alias    | lettuce        |
      | model.upstream | lettuce-grande |
      | provider       | hieronymus     |
      | context.window | 128000         |
