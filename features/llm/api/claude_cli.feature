Feature: Claude subscription provider via CLI shell-out

  The `claude` provider uses the local `claude` binary (logged in with
  subscription) for raw completions. Isaac owns the full prompt, transcript,
  and tool loop. The binary is invoked with --print, no claude tools,
  no claude session management.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the following sessions exist:
      | name | crew    |
      | main | thinker |

  Scenario: non-tool prompt uses raw shell-out
    Given the claude binary is stubbed to return "4"
    When the user sends "What is 2+2?" on session "main"
    Then the response is "4"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --system-prompt          |       |
      | (system prompt contains soul text) | |
      | (conversation prompt as final arg) | |
      | (user prompt does not contain soul text) | |

  Scenario: streaming response from claude subscription provider
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path                  | value |
      | command               | claude |
      | stream-non-tool-turns | true  |
    And the claude binary is stubbed to stream ["Hello", " ", "world"]
    When the user sends "hi" on session "main"
    Then the response streams as ["Hello", " ", "world"]
    And the claude binary was invoked exactly once with:
      | arg                      | value         |
      | --print                  |               |
      | --output-format          | stream-json   |
      | --include-partial-messages|              |
      | --tools                  |       |
      | --no-session-persistence |               |
      | --model                  | sonnet        |
      | --system-prompt          |               |
      | (conversation prompt as final arg) |      |

  Scenario: tool-using turn with Isaac-managed tools
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the crew has tools: [exec]
    And the claude binary is stubbed to first return tool call text for exec, then "done"
    When the user sends "list files" on session "main"
    Then the exec tool is executed
    And the claude binary was invoked exactly twice
    And the second invocation included the tool result serialized in the prompt text
    And the response is "done"

  Scenario: tool protocol contract rides on system prompt authority
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return "ok"
    When the user sends "run a command" on session "main"
    Then the claude binary was invoked exactly once with:
      | arg                      | value |
      | --system-prompt          |       |
      | (system prompt contains protocol contract) | |
      | (user prompt does not contain protocol contract) | |
      | (conversation prompt as final arg) | |

  Scenario: error from claude binary is reported
    Given the claude binary is stubbed to fail with exit code 1 and message "claude: boom"
    When the user sends "hi" on session "main"
    Then an error is reported indicating the claude binary failed
    And the error message contains "claude: boom"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|

  Scenario: login failure is a loud error and classifies as auth-unavailable
    Given the claude binary is stubbed to fail with exit code 1 and message "Not logged in · Please run /login"
    When the user sends "hi" on session "main"
    Then an error is reported indicating the claude binary failed
    And the error message contains "Please run /login"
    And the error is classified as auth-unavailable

  Scenario: claude subscription provider with custom binary path
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value              |
      | command | /custom/path/claude|
    And the claude binary at "/custom/path/claude" is stubbed to return "42"
    When the user sends "What is 6*7?" on session "main"
    Then the response is "42"
    And the claude binary at "/custom/path/claude" was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|

  Scenario: extra args from provider config are forwarded
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value         |
      | command     | claude        |
      | extra-args  | ["--foo","bar"]|
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --foo                    | bar   |

  Scenario: full history passed each turn (Isaac controls transcript)
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | math | thinker |
    And session "math" has transcript:
      | type    | message.role | message.content    |
      | message | user         | previous turn      |
      | message | assistant    | previous reply     |
    And the claude binary is stubbed to return "42"
    When the user sends "new question" on session "math"
    Then the response is "42"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --system-prompt          |       |
      | (prompt arg contains full history) | |
      | (no --continue or --resume) | |

  Scenario: claude subscription provider uses subscription login (no raw API key)
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | sub  | thinker |
    And the file "~/.claude/.credentials.json" exists with the subscription login
    And ANTHROPIC_API_KEY is not set in the environment
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "sub"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | (no ANTHROPIC_API_KEY in env) | |

  Scenario: claude subscription provider with extra args from config
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value         |
      | command     | claude        |
      | extra-args  | ["--foo","bar"]|
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | main | thinker |
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | text  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --foo                    | bar   |
