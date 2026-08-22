Feature: Sessions migrate
  `isaac sessions migrate [session-id]` converts leftover flat jsonl
  sessions into sessions/<id>/current.ednl directories. No id migrates
  every leftover session. Already-migrated directories are skipped.
  Runtime never converts on a turn.

  Background:
    Given default Grover setup

  Scenario: migrate converts a compacted jsonl session into frozen segments and current.ednl
    Given the isaac file "sessions/long.jsonl" exists with:
      """
      {"type":"session","id":"hdr1"}
      {"type":"message","id":"m1","message":{"role":"user","content":"old question"}}
      {"type":"message","id":"m2","message":{"role":"assistant","content":"old answer"}}
      {"type":"compaction","id":"c1","summary":"Caught up.","firstKeptEntryId":"m3"}
      {"type":"message","id":"m3","message":{"role":"user","content":"recent question"}}
      {"type":"message","id":"m4","message":{"role":"assistant","content":"recent answer"}}
      """
    And the isaac EDN file "sessions/long.edn" exists with:
      | path | value     |
      | id   | "long"    |
      | key  | "long"    |
      | name | "long"    |
    When isaac is run with "sessions migrate long"
    Then the stdout contains "migrated: long"
    And the exit code is 0
    And the isaac file "sessions/long.jsonl" does not exist
    And the isaac file "sessions/long/current.ednl" exists
    And the isaac file "sessions/long/0.ednl" exists

  Scenario: migrate without an id reports when nothing is leftover
    When isaac is run with "sessions migrate"
    Then the stdout contains "no leftover jsonl sessions"
    And the exit code is 0

  Scenario: migrate of an unknown id is a clean error
    When isaac is run with "sessions migrate ghost-ship"
    Then the stdout contains "session not found: ghost-ship"
    And the exit code is 1

  Scenario: migrate of an already-migrated session is a skip
    Given the following sessions exist:
      | name |
      | done |
    When isaac is run with "sessions migrate done"
    Then the stdout contains "already migrated: done"
    And the exit code is 0
