Feature: Config hot-reload
  The Isaac server watches its config directory and reloads the
  in-memory config when a file changes. The next turn sees the
  new state without a restart.

  Reload is atomic: if a change-set fails to parse or fails
  validation, the reload is rejected in full and the previous
  in-memory cfg is preserved. The failure is logged with enough
  detail (file path, reason, error) to debug the config.

  Writes outside the config directory do not trigger reload.

  What propagates to live systems vs. requires a restart is a
  separate, per-subsystem concern (documented, not enforced
  here): the cfg atom always reflects disk, but e.g. a running
  socket does not rebind on port changes.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key              | value |
      | bind-server-port | false |
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 32768  |
    And the isaac EDN file "config/providers/grover.edn" exists with:
      | path | value |
      | api  | grover |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value                            |
      | model | grover                           |
      | soul  | Keep the Marigold on course. |
    And the Isaac config harness is started

  Scenario: a change under config/ fires a reload and updates the cfg
    When the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value                            |
      | model | grover                           |
      | soul  | Storm glass says rough weather ahead. |
    Then the log has entries matching:
      | level | event            | path            |
      | :info | :config/reloaded | crew/cordelia.edn |
    And the loaded config has:
      | key                | value                                |
      | crew.cordelia.soul | Storm glass says rough weather ahead. |

  Scenario: parse failure on reload is rejected and logged with the error
    When the isaac file "config/crew/cordelia.edn" exists with:
      """
      {:model :grover
       :soul "only half a ma
      """
    Then the log has entries matching:
      | level  | event                 | path            | reason | error                |
      | :error | :config/reload-failed | crew/cordelia.edn | :parse | #"EOF while reading.*" |
    And the loaded config has:
      | key                | value                         |
      | crew.cordelia.soul | Keep the Marigold on course. |

  Scenario: validation failure on reload is rejected and logged with the errors
    When the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | ""     |
      | provider       | grover |
      | context-window | 32768  |
    Then the log has entries matching:
      | level  | event                 | path              | reason      | error                               |
      | :error | :config/reload-failed | models/grover.edn | :validation | models.grover.model is required |
    And the loaded config has:
      | key                 | value |
      | models.grover.model | echo  |

  Scenario: writes outside config/ do not fire a reload
    When the isaac file "random.txt" exists with:
      """
      just some content
      """
    Then the log has no entries matching:
      | event            |
      | :config/reloaded |
