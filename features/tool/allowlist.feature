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

  @wip
  Scenario: Namespaced allow offers the wire name, not the old bare name
    Given the built-in tools are registered
    And the crew "main" allows tools: "fs/read"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name     |
      | fs__read |
    And the prompt does not have tools:
      | name |
      | read |

  @wip
  Scenario: Unqualified allow token fails config validate
    Given an empty Isaac root at "/tmp/isaac-allow-ns"
    And the isaac file "isaac.edn" exists with:
      """
      {:crew {:main {:tools {:allow [:read]}}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                      | value        |
      | crew.main.tools.allow[0] | #"namespace" |

  @wip
  Scenario: Namespaced allow is exact, not the whole family
    Given the built-in tools are registered
    And the crew "main" allows tools: "fs/read"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name     |
      | fs__read |
    And the prompt does not have tools:
      | name      |
      | fs__write |

  @wip
  Scenario: Namespace glob offers the family and no other families
    Given the built-in tools are registered
    And a tool "linear__get_issue" that returns nil is registered
    And a tool "github__get_issue" that returns nil is registered
    And the crew "main" allows tools: "fs/*"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name           |
      | fs__read       |
      | fs__write      |
      | fs__edit       |
      | fs__multi_edit |
      | fs__grep       |
      | fs__glob       |
    And the prompt does not have tools:
      | name              |
      | exec__run         |
      | linear__get_issue |
      | github__get_issue |

  @wip
  Scenario: Tool call for a namespaced name not on the allow list is an error
    Given the built-in tools are registered
    And the crew "main" allows tools: "fs/read"
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call | arguments                                    |
      | echo  | fs__write | {"file_path": "target/test-state/hello.txt"} |
      | model | type      | content                                      |
      | echo  | text      | Sorry about that                             |
    When the user sends "write hello.txt" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |

  @wip
  Scenario: Config validate accepts namespaced allow and a namespace glob
    Given an empty Isaac root at "/tmp/isaac-allow-ok"
    And the isaac file "isaac.edn" exists with:
      """
      {:crew {:main {:tools {:allow [:fs/read :fs/*]}}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                      | value   |
      | crew.main.tools.allow[0] | fs/read |
      | crew.main.tools.allow[1] | fs/*    |

  @wip
  Scenario: Config validate rejects unqualified tokens that are not :all
    Given an empty Isaac root at "/tmp/isaac-allow-bare"
    And the isaac file "isaac.edn" exists with:
      """
      {:crew {:main {:tools {:allow [:nope :linear]}}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                      | value        |
      | crew.main.tools.allow[0] | #"namespace" |
      | crew.main.tools.allow[1] | #"namespace" |
