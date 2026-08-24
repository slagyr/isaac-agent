Feature: Mid-turn compaction keeps the request in flight
  Rubberband folds the whole effective history. That is correct at a
  turn boundary — the new user line is appended after the splice. Mid-turn
  it is not: the hail already on disk is the request being served. After a
  tool batch trips the threshold, the next LLM request in the same turn
  must still contain that hail and must not re-send the fat tool dump.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: rubberband mid-turn still carries the originating hail
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 400    |
    And the crew "main" allows tools: "fs/read"
    And the isaac file "crew/main/fridge.txt" exists with:
      """
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD HUGE-LEMON-PAYLOAD
      """
    And the following sessions exist:
      | name   |
      | mid-rb |
    And the following model responses are queued:
      | type      | tool_call | arguments                                              | content                      | model |
      | tool_call | fs__read  | {"file_path":"/target/test-state/crew/main/fridge.txt"} |                              | echo  |
      | text      |           |                                                        | Folded the prior tools       | echo  |
      | text      |           |                                                        | One sad lemon, as requested  | echo  |
    When the user sends "What's in fridge.txt? Don't lose this ask." on session "mid-rb"
    Then session "mid-rb" has compaction
    And the last LLM request mentions "Don't lose this ask." exactly 1 time
    And the last LLM request mentions "HUGE-LEMON-PAYLOAD" exactly 0 times
