Feature: Slash command extension
  Modules can register slash commands by declaring
  :slash-commands {<name> {:factory ...}} in their manifest; the command
  is registered under its berth key. Built-in slash commands (status,
  crew, model, cwd) use the same registry, so module-declared and
  built-in commands coexist in available-commands.

  Name collisions are last-wins with a warning — a module declaring a
  built-in's name overrides it, logged so it does not happen silently
  (see the registry spec).

  Scenario: A module-declared slash command is invokable
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.slash.echo {:local/root "modules/isaac.slash.echo"}}}
      """
    When the user sends "/echo Hieronymus's emergency lettuce" on session "main" via memory comm
    Then the reply contains "Hieronymus's emergency lettuce"

  Scenario: Slash module activation registers its commands
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :modules {:isaac.slash.echo {:local/root "modules/isaac.slash.echo"}}}
      """
    When manifest berths are processed for the loaded config
    Then the log has entries matching:
      | level | event             | berth                       | entry | module           |
      | :info | :berth/registration | :isaac.agent/slash-commands | echo  | isaac.slash.echo |

  Scenario: Module-declared slash commands appear alongside built-ins after activation
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.slash.echo {:local/root "modules/isaac.slash.echo"}}}
      """
    When the user sends "/echo Hieronymus's emergency lettuce" on session "main" via memory comm
    Then the available slash commands include:
      | name   | description                   |
      | status | Show session status           |
      | echo   | Echo the input back unchanged |

