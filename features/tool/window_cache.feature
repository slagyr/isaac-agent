Feature: Per-window tool cache — repeated reads, greps and skill loads return a stub
  Within one context window a worker re-requests the same read window
  (938 identical file+offset+limit reads in a day), the same grep (552) and
  the same skill (236 loads, 78 of them hail-bean-work). Each repeat costs a
  model cycle (~30 s on grok-4.6) and a full result's worth of context. The
  tool still runs; its result is hashed and compared with the hash seen
  earlier in this window. A match returns a short stub that says the content
  is already in context and names the cycle that has it. The cache holds
  hashes only, lives in the turn's cycle map, is invalidated per file by
  edits, and is cleared on compaction (a read after compaction is honest —
  the content is gone) — pinned at spec level (isaac-yk0u, decisions 2–3).

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: a repeated read of an unchanged window returns a stub, not the content
    Given a file "nest/code.txt" exists with content "alpha\nbeta\ngamma"
    And the crew "main" allows tools: "fs/read"
    And the following sessions exist:
      | name   | crew | cwd  |
      | magpie | main | nest |
    And the following model responses are queued:
      | tool_call | arguments                 |
      | fs__read  | {"file_path": "code.txt"} |
      | fs__read  | {"file_path": "code.txt"} |
    When the user sends "look twice" on session "magpie"
    Then session "magpie" has transcript matching:
      | type     | message.role | message.content                                         |
      | message  | user         | look twice                                              |
      | toolCall | assistant    | #*                                                      |
      | message  | toolResult   | #"(?s)1: alpha.*3: gamma"                               |
      | toolCall | assistant    | #*                                                      |
      | message  | toolResult   | #"(?s)unchanged since cycle 1.*already in your context" |
    And the log has entries matching:
      | event           | tool     | cycle |
      | :tool/cache-hit | fs__read | 1     |

  Scenario: an edit between two reads of the same window invalidates the stub
    Given a file "nest/code.txt" exists with content "alpha\nbeta\ngamma"
    And the crew "main" allows tools: "fs/read,fs/edit"
    And the following sessions exist:
      | name   | crew | cwd  |
      | magpie | main | nest |
    And the following model responses are queued:
      | tool_call | arguments                                                              |
      | fs__read  | {"file_path": "code.txt"}                                              |
      | fs__edit  | {"file_path": "code.txt", "old_string": "beta", "new_string": "BETA"} |
      | fs__read  | {"file_path": "code.txt"}                                              |
    When the user sends "look, change, look" on session "magpie"
    Then session "magpie" has transcript matching:
      | type     | message.role | message.content          |
      | message  | user         | look, change, look       |
      | toolCall | assistant    | #*                       |
      | message  | toolResult   | #"(?s)2: beta"           |
      | toolCall | assistant    | #*                       |
      | message  | toolResult   | #"(?s)2: BETA"           |
      | toolCall | assistant    | #*                       |
      | message  | toolResult   | #"(?s)1: alpha.*2: BETA" |
    And the log has no entries matching:
      | event           |
      | :tool/cache-hit |

  Scenario: a repeated grep over an unchanged tree returns a stub
    Given a file "nest/a.clj" exists with content "(defn seed [] :marigold)"
    And a file "nest/b.clj" exists with content "(defn water [] :marigold)"
    And the crew "main" allows tools: "fs/grep"
    And the following sessions exist:
      | name   | crew | cwd  |
      | magpie | main | nest |
    And the following model responses are queued:
      | tool_call | arguments                                |
      | fs__grep  | {"pattern": "marigold", "path": "."}     |
      | fs__grep  | {"pattern": "marigold", "path": "."}     |
    When the user sends "find it twice" on session "magpie"
    Then session "magpie" has transcript matching:
      | type     | message.role | message.content                                         |
      | message  | user         | find it twice                                           |
      | toolCall | assistant    | #*                                                      |
      | message  | toolResult   | #"(?s)a\.clj.*b\.clj"                                   |
      | toolCall | assistant    | #*                                                      |
      | message  | toolResult   | #"(?s)unchanged since cycle 1.*already in your context" |
    And the log has entries matching:
      | event           | tool     | cycle |
      | :tool/cache-hit | fs__grep | 1     |

  Scenario: a skill loaded twice in one window is served once
    Given the isaac file "prompts/skills/greenhouse-protocol/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when tending specimens
      ---
      Always quarantine new specimens for one cycle before integration.
      """
    And the following sessions exist:
      | name       | crew |
      | greenhouse | main |
    And the following model responses are queued:
      | tool_call   | arguments                      |
      | skill__load | {"name":"greenhouse-protocol"} |
      | skill__load | {"name":"greenhouse-protocol"} |
    When the user sends "Tend the orchid twice." on session "greenhouse"
    Then session "greenhouse" has transcript matching:
      | type     | message.role | message.content                                                 |
      | message  | user         | Tend the orchid twice.                                          |
      | toolCall | assistant    | #*                                                              |
      | message  | toolResult   | #"(?s)Always quarantine new specimens"                          |
      | toolCall | assistant    | #*                                                              |
      | message  | toolResult   | #"(?s)greenhouse-protocol.*already in context since cycle 1"    |
    And the log has entries matching:
      | event           | tool        | cycle |
      | :tool/cache-hit | skill__load | 1     |
