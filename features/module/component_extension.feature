Feature: Foundation component berth
  Runtime components are contributed by modules, while the platform foundation
  owns the berth contract that validates those contributions.

  Scenario: A module contribution to the foundation component berth validates
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "/tmp/modules/isaac.fixture.component/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/isaac.fixture.component/resources/isaac-manifest.edn" exists with:
      """
      {:id :isaac.fixture.component
       :version "0.1.0"
       :factory isaac.fixture.component/create-module
       :isaac/component {:fixture {:namespace isaac.fixture.component}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.fixture.component {:local/root "/tmp/modules/isaac.fixture.component"}}}
      """
    When the config is loaded
    Then the config has no validation errors

  Scenario: A module contribution to an unknown berth still fails
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "/tmp/modules/isaac.fixture.unknown/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/isaac.fixture.unknown/resources/isaac-manifest.edn" exists with:
      """
      {:id :isaac.fixture.unknown
       :version "0.1.0"
       :factory isaac.fixture.unknown/create-module
       :isaac.unknown/berth {:fixture {:namespace isaac.fixture.unknown}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.fixture.unknown {:local/root "/tmp/modules/isaac.fixture.unknown"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                        | value                                      |
      | module-index["isaac.fixture.unknown"][:isaac.unknown/berth] | berth not declared by any installed module |
