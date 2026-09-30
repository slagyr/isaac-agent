Feature: Config set / unset
  `isaac config set <path> [<value>]` and `isaac config unset <path>`
  mutate config via a schema-aware path walker. The walker consults
  the config schema at each segment to decide how to interpret the
  next one: map keys are keywords, vector segments are indices, set
  members terminate the path. Both subcommands persist the updated
  config and are idempotent.

  Background:
    Given an Isaac root at "target/test-state"

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

  Scenario: setting the first of two required fields is refused and hints at --force
    Given default Grover setup
    When isaac is run with "config set models.echo.model echo-v1"
    Then the stderr contains "models.echo.provider"
    And the stderr contains "is required"
    And the stderr contains "--force"
    And the stderr contains "echo '{…}' | isaac config set models.echo -"
    And the exit code is 1

  Scenario: --force writes the first required field and the second set validates clean
    Given default Grover setup
    When isaac is run with "config set models.echo.model echo-v1 --force"
    Then the stdout lines contain in order:
      | pattern                                |
      | set models.echo.model = "echo-v1"      |
      | Validation warnings (1):               |
      | models.echo.provider - is required     |
    And the stdout does not contain "error(s) outstanding"
    And the stderr does not contain "warning:"
    And the exit code is 0
    When isaac is run with "config set models.echo.provider grover"
    Then the exit code is 0
    And the stdout does not contain "Validation warnings"
    When isaac is run with "config get models.echo.model"
    Then the stdout contains "echo-v1"
    And the exit code is 0

  Scenario: --force still refuses a value that does not parse
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "config set crew.joe.effort not-a-number --force"
    Then the stderr contains "effort"
    And the exit code is 1

  Scenario: config unset --force removes a required field and warns
    Given default Grover setup
    And stdin is:
      """
      {:model "echo-v1" :provider :grover}
      """
    When isaac is run with "config set models.echo -"
    Then the exit code is 0
    When isaac is run with "config unset models.echo.model --force"
    Then the stdout lines contain in order:
      | pattern                         |
      | unset models.echo.model         |
      | Validation warnings (1):        |
      | models.echo.model - is required |
    And the exit code is 0

  Scenario: the stdin map form sets both required fields in one write with no warning
    Given default Grover setup
    And stdin is:
      """
      {:model "echo-v1" :provider :grover}
      """
    When isaac is run with "config set models.echo -"
    Then the exit code is 0
    And the stderr does not contain "warning:"
    When isaac is run with "config get models.echo.model"
    Then the stdout contains "echo-v1"
    And the exit code is 0

  Scenario: config set --help documents --force and the stdin-map form
    Given default Grover setup
    When isaac is run with "config set --help"
    Then the stdout contains "--force"
    And the stdout contains "isaac config set google.oauth -"
    And the exit code is 0
