@wip
Feature: Global and crew directory allow/deny
  Path permission is a yes/no per (crew, path). Empty means no paths.
  Longest matching prefix wins; same-length uses the tool cascade
  (global allow, global deny, crew deny, crew allow). Overlay, not
  replace. Applies to fs/* tools. exec is not covered.

  Background:
    Given an Isaac root at "isaac-state"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :echo}
       :providers {:grover {:base-url "http://test" :api "grover"}}
       :models    {:echo {:model "echo" :provider :grover :context-window 32768}}}
      """
    And the built-in tools are registered

  Scenario: No directory grants means cwd is unreadable
    Given config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And file "/work/project/hello.txt" contains "hi there"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                               |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"} |
      | text      |          | Got it                                  |
    When the user sends "read hello" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |

  Scenario: Global cwd grant allows the session workdir and not outside it
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:allow [:cwd]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And file "/work/project/hello.txt" contains "hi there"
    And file "/outside/secret.txt" contains "nope"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                               |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"} |
      | text      |          | inside                                  |
      | tool_call | fs__read | {"file_path": "/outside/secret.txt"}    |
      | text      |          | outside                                 |
    When the user sends "read both" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |
      | message | assistant    |                 |
      | message | toolResult   | true            |

  Scenario: Global quarters grant allows the crew area and not cwd
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:allow [:quarters]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And crew "main" has file "notes.txt" with "hello"
    And file "/work/project/hello.txt" contains "hi there"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                                            |
      | tool_call | fs__read | {"file_path": "/isaac-state/crew/main/notes.txt"}    |
      | text      |          | quarters                                             |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"}             |
      | text      |          | cwd                                                  |
    When the user sends "read both" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |
      | message | assistant    |                 |
      | message | toolResult   | true            |

  Scenario: Crew extra absolute path overlays and inherited cwd still works
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:allow [:cwd]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:allow ["/tmp/isaac-playground"]}}}
      """
    And file "/work/project/hello.txt" contains "hi there"
    And file "/tmp/isaac-playground/data.txt" contains "hello"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                                      |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"}       |
      | text      |          | cwd                                            |
      | tool_call | fs__read | {"file_path": "/tmp/isaac-playground/data.txt"} |
      | text      |          | extra                                          |
    When the user sends "read both" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |
      | message | assistant    |                 |
      | message | toolResult   |                 |

  Scenario: A more specific global deny under a crew-allowed parent still denies
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:deny ["/work/project/secret"]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:allow ["/work/project"]}}}
      """
    And file "/work/project/hello.txt" contains "hi"
    And file "/work/project/secret/key.txt" contains "nope"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                                     |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"}      |
      | text      |          | parent                                        |
      | tool_call | fs__read | {"file_path": "/work/project/secret/key.txt"} |
      | text      |          | child                                         |
    When the user sends "read both" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |
      | message | assistant    |                 |
      | message | toolResult   | true            |

  Scenario: Crew allow of the denied child prefix re-opens it
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:deny ["/work/project/secret"]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:allow ["/work/project" "/work/project/secret"]}}}
      """
    And file "/work/project/secret/key.txt" contains "ok"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                                     |
      | tool_call | fs__read | {"file_path": "/work/project/secret/key.txt"} |
      | text      |          | reopened                                      |
    When the user sends "read secret" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |

  Scenario: Crew deny of a subpath; sibling under the root still works
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:allow ["/work/project"]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow       [:fs/read]
               :directories {:deny ["/work/project/.env"]}}}
      """
    And file "/work/project/hello.txt" contains "hi"
    And file "/work/project/.env" contains "SECRET=1"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                                |
      | tool_call | fs__read | {"file_path": "/work/project/hello.txt"} |
      | text      |          | sibling                                  |
      | tool_call | fs__read | {"file_path": "/work/project/.env"}      |
      | text      |          | env                                      |
    When the user sends "read both" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   |                 |
      | message | assistant    |                 |
      | message | toolResult   | true            |

  Scenario: Symlink under cwd pointing outside an allow root is an error
    Given config file "isaac.edn" containing:
      """
      {:defaults    {:crew :main :model :echo}
       :providers   {:grover {:base-url "http://test" :api "grover"}}
       :models      {:echo {:model "echo" :provider :grover :context-window 32768}}
       :tools       {:directories {:allow [:cwd]}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:fs/read]}}
      """
    And file "/outside/secret.txt" contains "nope"
    And a symlink "/work/project/link.txt" pointing at "/outside/secret.txt"
    And the following sessions exist:
      | name       | cwd           |
      | fence-test | /work/project |
    And the following model responses are queued:
      | type      | tool     | arguments                              |
      | tool_call | fs__read | {"file_path": "/work/project/link.txt"} |
      | text      |          | linked                                  |
    When the user sends "read link" on session "fence-test"
    Then session "fence-test" has transcript matching:
      | type    | message.role | message.isError |
      | message | toolResult   | true            |
