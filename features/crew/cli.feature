Feature: Crew Command
  `isaac crew` is a management command; `isaac crew list` lists
  configured crew members.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: crew is registered and has help
    When isaac is run with "help crew"
    Then the stdout contains "Usage: isaac crew"
    And the exit code is 0

  Scenario: crew --help shows management help and lists subcommands
    When isaac is run with "crew --help"
    Then the stdout contains "Usage: isaac crew"
    And the stdout contains "List configured crew members"
    And the stdout contains "Show one crew member"
    And the exit code is 0

  Scenario: bare crew shows management help
    When isaac is run with "crew"
    Then the stdout contains "Usage: isaac crew"
    And the stdout contains "Subcommands:"
    And the exit code is 0

  Scenario: crew show --help shows subcommand help
    When isaac is run with "crew show --help"
    Then the stdout contains "Usage: isaac crew show <name>"
    And the stdout contains "Show one crew member"
    And the exit code is 0

  Scenario: crew list shows configured crew members
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    When isaac is run with "crew list"
    Then the stdout matches:
      | pattern              |
      | Name .* Model .* Provider .* Soul |
      | main .* echo         |
      | ketch .* echo        |
    And the exit code is 0

  Scenario: crew list with no configured crew members shows the default
    When isaac is run with "crew list"
    Then the stdout matches:
      | pattern              |
      | Name .* Model .* Provider .* Soul |
      | main                 |
    And the exit code is 0

  Scenario: crew show renders key-value detail with the full soul
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | "You are Ketch.\nAlways watch the reef." |
    When isaac is run with "crew show ketch"
    Then the stdout contains "Name"
    And the stdout contains "ketch"
    And the stdout contains "Soul"
    And the stdout contains "You are Ketch."
    And the stdout contains "Always watch the reef."
    And the exit code is 0

  Scenario: crew show --edn emits full soul without presentation fields
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | "You are Ketch.\nAlways watch the reef." |
      | tags | #{:role/worker} |
    When isaac is run with "crew show ketch --edn"
    Then the stdout EDN contains:
      | path | value |
      | name | "ketch" |
      | soul | "You are Ketch.\nAlways watch the reef." |
      | tags | #{:role/worker} |
    And the stdout does not contain ":soul-source"
    And the stdout does not contain ":tags-text"
    And the exit code is 0
