Feature: Top-level CLI usage
  The 'isaac' command shown without a subcommand (or with --help)
  prints usage: global options, then the list of commands. Users
  need to see --root here — otherwise they have no idea the flag
  exists.

  Scenario: top-level usage lists global options including --root
    When isaac is run with "--help"
    Then the stdout matches:
      | pattern                                          |
      | Usage: isaac \[options\] <command> \[args\]      |
      | Global Options:                                  |
      | --root <dir>\s+Override Isaac's root directory   |
      | --help, -h\s+Show this message                   |
      | Commands:                                        |
    And the exit code is 0
