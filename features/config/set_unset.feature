Feature: Config set / unset
  `isaac config set <path> [<value>]` and `isaac config unset <path>`
  mutate config via a schema-aware path walker. The walker consults
  the config schema at each segment to decide how to interpret the
  next one: map keys are keywords, vector segments are indices, set
  members terminate the path. Both subcommands persist the updated
  config and are idempotent.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: scalar set writes a value at a known map path
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.model echo"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.model"
    Then the stdout contains "echo"
    And the exit code is 0

  Scenario: scalar unset removes a value at a known map path
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
      | soul  | test   |
    When isaac is run with "config unset crew.joe.model"
    Then the exit code is 0
    When isaac is run with "config get crew.joe"
    Then the stdout does not contain "grover"
    And the exit code is 0

  Scenario: config set is idempotent when the value is already present
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value |
      | model | echo  |
    When isaac is run with "config set crew.joe.model echo"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.model"
    Then the stdout contains "echo"
    And the exit code is 0

  Scenario: config unset is idempotent when the value is absent
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config unset crew.joe.effort"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.model"
    Then the stdout contains "grover"
    And the exit code is 0

  Scenario: config set errors on a path the schema doesn't recognize
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.bogus value"
    Then the stderr contains "bogus"
    And the exit code is 1

  Scenario: config set errors when the value doesn't match the schema type
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.effort not-a-number"
    Then the stderr contains "effort"
    And the exit code is 1

  Scenario: config set tools.defaults.max-lines succeeds and the value lands
    Given default Grover setup
    When isaac is run with "config set tools.defaults.max-lines 500"
    Then the exit code is 0
    When isaac is run with "config get tools.defaults.max-lines"
    Then the stdout contains "500"
    And the exit code is 0

  Scenario: config set refuses a value a schema validator rejects
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.tags jackalope"
    Then the stderr contains "crew.joe.tags"
    And the stderr contains "must be a set of keywords"
    And the exit code is 1
    When isaac is run with "config get crew.joe"
    Then the stdout does not contain "jackalope"
    And the exit code is 0

  Scenario: config set still accepts a reference to an entity that is not defined yet
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.model not-yet-defined"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.model"
    Then the stdout contains "not-yet-defined"
    And the exit code is 0

  Scenario: config set confirms what it wrote and where
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.model echo"
    Then the stdout matches:
      | pattern                                    |
      | set crew\.joe\.model = :echo.*crew/joe\.edn |
    And the exit code is 0

  Scenario: config set confirms a set member it added
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.tags.wip"
    Then the stdout matches:
      | pattern                                       |
      | set crew\.joe\.tags \+= :wip.*crew/joe\.edn   |
    And the exit code is 0

  Scenario: config unset confirms what it removed
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config unset crew.joe.model"
    Then the stdout matches:
      | pattern                                |
      | unset crew\.joe\.model.*crew/joe\.edn  |
    And the exit code is 0

  Scenario: config set --edn prints only the structured record
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.model echo --edn"
    Then the stdout does not contain "set crew.joe.model"
    And the exit code is 0

  Scenario: config set --help documents the set-member path form
    Given default Grover setup
    When isaac is run with "config set --help"
    Then the stdout matches:
      | pattern                                              |
      | Set-typed fields take the member in the path         |
      | isaac config set crew\.marvin\.tags\.role/worker     |
    And the exit code is 0
