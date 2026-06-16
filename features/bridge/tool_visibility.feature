Feature: Crew tools reach every comm path
  A crew's :tools.allow is the source of truth for which tools are
  offered to the model on every turn. Every in-tree channel that
  drives a turn (currently just `prompt`) must surface the same tool
  set. If a crew has no :tools section, no tools are offered —
  regardless of channel.

  Module comms (ACP, Discord, iMessage, …) verify this contract in
  their own repos against the same `the prompt has tools:` assertion.

  Background:
    Given default Grover setup
    And the crew "main" allows tools: "read,write,exec"

  Scenario: prompt command offers the crew's configured tools
    When isaac is run with "prompt hi"
    Then the prompt has tools:
      | name  |
      | read  |
      | write |
      | exec  |

  Scenario: a crew with no :tools section still gets zero tools over every comm
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value                    |
      | model | grover                   |
      | soul  | Marvin. Paranoid droid.  |
      | tools | #delete                  |
    When isaac is run with "prompt hi"
    Then the prompt has 0 tools
