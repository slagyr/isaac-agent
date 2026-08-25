Feature: Per-crew filesystem boundaries
  Each crew member can only access their quarters and explicitly
  whitelisted directories. File operations outside these boundaries
  are rejected.

  Background:
    Given an Isaac root at "isaac-state"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :echo}
       :providers {:grover {:base-url "http://test" :api "grover"}}
       :models    {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools     {:directories {:allow [:cwd :quarters]}}}
      """

  Scenario: crew can read files in their quarters
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And crew "main" has file "notes.txt" with "hello"
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                       |
      | tool_call | fs__read | {"file_path": "/isaac-state/crew/main/notes.txt"} |
      | text      |      | Got it                                          |
    When the user sends "read notes" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |

  Scenario: crew can read files in whitelisted directories
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:allow ["/tmp/isaac-playground"]}}}
      """
    And file "/tmp/isaac-playground/data.txt" contains "hello"
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                     |
      | tool_call | fs__read | {"file_path": "/tmp/isaac-playground/data.txt"} |
      | text      |      | Got it                                        |
    When the user sends "read data" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |

  Scenario: crew cannot read files outside their boundaries
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                   |
      | tool_call | fs__read | {"file_path": "/etc/passwd"} |
      | text      |      | Sorry                       |
    When the user sends "read passwords" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: crew cannot write files outside their boundaries
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/write]}}
      """
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool  | arguments                                          |
      | tool_call | fs__write | {"file_path": "/tmp/evil.txt", "content": "gotcha"} |
      | text      |       | Sorry                                              |
    When the user sends "write evil" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: crew can access session cwd when it opts in via :cwd
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:allow [:cwd]}}
      """
    And file "/work/project/hello.txt" contains "hi there"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool | arguments                               |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"} |
      | text      |      | Got it                                  |
    When the user sends "read hello" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |

  Scenario: crew cannot access the session role workspace without a directory grant (isaac-ukg4)
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :echo}
       :providers {:grover {:base-url "http://test" :api "grover"}}
       :models    {:echo {:model "echo" :provider :grover :context-window 32768}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And file "/work/project/hello.txt" contains "hi there"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool | arguments                               |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"} |
      | text      |      | Sorry                                  |
    When the user sends "read hello" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |

  Scenario: a crew scoped to its role workspace cannot write outside it (isaac-dwjy)
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/write]}}
      """
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool  | arguments                                          |
      | tool_call | fs__write | {"file_path": "/tmp/evil.txt", "content": "gotcha"} |
      | text      |       | Sorry                                              |
    When the user sends "write evil" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: path traversal that escapes boundaries is rejected
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                                |
      | tool_call | fs__read | {"file_path": "/isaac-state/crew/main/../../etc/passwd"} |
      | text      |      | Sorry                                                   |
    When the user sends "sneaky read" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: crew cannot read its own config file
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                          |
      | tool_call | fs__read | {"file_path": "/isaac-state/config/crew/main.edn"} |
      | text      |      | Sorry                                             |
    When the user sends "read my config" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: crew cannot grep files outside allowed directories
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/grep]}}
      """
    And file "/tmp/secret-stash/passwords.txt" contains "hunter2"
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                          |
      | tool_call | fs__grep | {"pattern": "hunter", "path": "/tmp/secret-stash"} |
      | text      |      | Sorry, I cannot.                                   |
    When the user sends "find passwords" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |

  Scenario: crew cannot glob files outside allowed directories
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/glob]}}
      """
    And file "/tmp/secret-stash/treasure.clj" contains ""
    And the following sessions exist:
      | name       |
      | fence-test |
    And the following model responses are queued:
      | type      | tool | arguments                                         |
      | tool_call | fs__glob | {"pattern": "*.clj", "path": "/tmp/secret-stash"} |
      | text      |      | Sorry, I cannot.                                  |
    When the user sends "hunt for code" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError | message.content                  |
      | message | toolResult   | true            | path outside allowed directories |
