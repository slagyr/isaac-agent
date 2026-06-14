Feature: Per-crew tool allowlist
  Each crew member has an explicit list of allowed tools.
  Only allowed tools are registered for the session.
  A crew member with no tools configured has no tools.

  Background:
    Given default Grover setup

  Scenario: crew member with allowed tools can use them
    Given the crew "main" allows tools: "read,write,edit"
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call | arguments                                    |
      | echo  | read      | {"file_path": "target/test-state/hello.txt"}   |
      | model | type      | content                                      |
      | echo  | text      | Got it                                       |
    When the user sends "read hello.txt" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    |                 |
      | message | toolResult   |                 |
      | message | assistant    | Got it          |

  Scenario: crew member cannot use tools not in their allow list
    Given the crew "main" allows tools: "read"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name |
      | read |
    And the prompt does not have tools:
      | name  |
      | write |
      | edit  |
      | exec  |

  Scenario: crew member with no tools configured has no tools
    Given the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has 0 tools

  Scenario: exec requires explicit opt-in
    Given the crew "main" allows tools: "read,write,edit"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has 3 tools
    And the prompt has tools:
      | name  |
      | read  |
      | write |
      | edit  |
    And the prompt does not have tools:
      | name |
      | exec |

  Scenario: tool call for a disallowed tool returns an error
    Given the crew "main" allows tools: "read"
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call | arguments               |
      | echo  | exec      | {"command": "rm -rf /"} |
      | model | type      | content                 |
      | echo  | text      | Sorry about that        |
    When the user sends "do something dangerous" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |

  Scenario: crew member without a tools section has no tools
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | grover |
      | soul | Marvin. Paranoid droid. |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has 0 tools

  Scenario: tool call from a crew with no tools section returns an error
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | grover |
      | soul | Marvin. Paranoid droid. |
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call | arguments         |
      | echo  | exec      | {"command": "ls"} |
      | model | type      | content           |
      | echo  | text      | Fine, I give up.  |
    When the user sends "list files" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |
