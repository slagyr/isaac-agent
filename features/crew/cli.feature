Feature: Crew Command
  `isaac crew` lists configured crew members with their model,
  provider, and soul source.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: crew is registered and has help
    When isaac is run with "help crew"
    Then the stdout contains "Usage: isaac crew"
    And the exit code is 0

  Scenario: crew --help lists its subcommands
    When isaac is run with "crew --help"
    Then the stdout matches:
      | pattern              |
      | Usage: isaac crew    |
      | Subcommands:         |
      | show\s+Show one crew |
    And the exit code is 0

  Scenario: crew lists configured crew members with underlined headers
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    When isaac is run with "crew"
    Then the stdout matches:
      | pattern              |
      | Name .* Model .* Provider .* Soul |
      | main .* echo         |
      | ketch .* echo        |
    And the exit code is 0

  Scenario: crew with no configured crew members shows the default
    When isaac is run with "crew"
    Then the stdout matches:
      | pattern              |
      | Name .* Model .* Provider .* Soul |
      | main                 |
    And the exit code is 0
