Feature: Crew model hot-reload
  A crew's :model change via config reload must apply to an already-active
  session's NEXT turn, not only to freshly created sessions. On zanebot,
  flipping crew scrapper :sonnet -> :gpt reloaded config but session
  isaac-work-1 kept resolving claude-sonnet-5 for many turns, while a fresh
  session picked up gpt-5.4 immediately — the model is cached in the session
  context and never invalidated on reload. Contrast :max-in-flight, which the
  delivery worker reads from live config every tick. (isaac-q5ee)

  Background:
    Given default Grover setup

  Scenario: a crew model change applies to the next turn of an already-active session
    Given the isaac EDN file "config/models/alpha.edn" exists with:
      | path     | value   |
      | model    | alpha-1 |
      | provider | grover  |
    And the isaac EDN file "config/models/beta.edn" exists with:
      | path     | value  |
      | model    | beta-1 |
      | provider | grover |
    And the isaac EDN file "config/crew/flipper.edn" exists with:
      | path  | value  |
      | model | :alpha |
    And the following sessions exist:
      | name      | crew    |
      | flip-sess | flipper |
    And the following model responses are queued:
      | type | content | model   |
      | text | one     | alpha-1 |
      | text | two     | beta-1  |
    When the user sends "first" on session "flip-sess"
    Then the last chat request on session "flip-sess" used model "alpha-1"
    When the isaac EDN file "config/crew/flipper.edn" exists with:
      | path  | value |
      | model | :beta |
    And the user sends "second" on session "flip-sess"
    Then the last chat request on session "flip-sess" used model "beta-1"

  Scenario: an explicit session-level model override still wins after a crew reload
    A session that pinned its own :model must not be clobbered when the crew's
    model config changes — the override outranks crew config, before and after.
    Given the isaac EDN file "config/models/alpha.edn" exists with:
      | path     | value   |
      | model    | alpha-1 |
      | provider | grover  |
    And the isaac EDN file "config/models/beta.edn" exists with:
      | path     | value  |
      | model    | beta-1 |
      | provider | grover |
    And the isaac EDN file "config/crew/flipper.edn" exists with:
      | path  | value  |
      | model | :alpha |
    When a session "pinned" is created with explicit crew "flipper"
    And a session "pinned" exists with model ":beta"
    And the following model responses are queued:
      | type | content | model  |
      | text | one     | beta-1 |
      | text | two     | beta-1 |
    When the user sends "first" on session "pinned"
    Then the last chat request on session "pinned" used model "beta-1"
    When the isaac EDN file "config/crew/flipper.edn" exists with:
      | path  | value  |
      | model | :alpha |
    And the user sends "second" on session "pinned"
    Then the last chat request on session "pinned" used model "beta-1"
