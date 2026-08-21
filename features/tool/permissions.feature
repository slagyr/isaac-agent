@wip
Feature: Global and crew tool allow/deny cascade
  Tool permission is a yes/no per (crew, tool). Polar rules, last match
  wins: global allow, global deny, crew deny, crew allow. Empty config
  is deny-all. Crew overlays; a crew deny does not drop global denies.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: No allow anywhere means no tools
    Given the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has 0 tools

  Scenario: Global allow all is inherited when the crew omits :tools
    Given config:
      | key         | value |
      | tools.allow | :all  |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name           |
      | fs__read       |
      | fs__write      |
      | fs__edit       |
      | fs__multi_edit |
      | fs__grep       |
      | fs__glob       |
      | exec__run      |
      | web__fetch     |
      | web__search    |
      | memory__write  |
      | memory__get    |
      | memory__search |
      | session__info  |
      | session__model |
      | skill__load    |
      | skill__list    |
      | comm__send     |
      | hail__send     |

  Scenario: Global deny exec is inherited
    Given config:
      | key         | value        |
      | tools.allow | :all         |
      | tools.deny  | [:exec/run]  |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt does not have tools:
      | name      |
      | exec__run |
    And the prompt has tools:
      | name           |
      | fs__read       |
      | fs__write      |
      | fs__edit       |
      | fs__multi_edit |
      | fs__grep       |
      | fs__glob       |
      | web__fetch     |
      | web__search    |
      | memory__write  |
      | memory__get    |
      | memory__search |
      | session__info  |
      | session__model |
      | skill__load    |
      | skill__list    |
      | comm__send     |
      | hail__send     |

  Scenario: Crew allow re-enables a globally denied tool
    Given config:
      | key         | value       |
      | tools.allow | :all        |
      | tools.deny  | [:exec/run] |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path        | value      |
      | model       | grover     |
      | soul        | Atticus.   |
      | tools.allow | [:exec/run] |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name           |
      | fs__read       |
      | fs__write      |
      | fs__edit       |
      | fs__multi_edit |
      | fs__grep       |
      | fs__glob       |
      | exec__run      |
      | web__fetch     |
      | web__search    |
      | memory__write  |
      | memory__get    |
      | memory__search |
      | session__info  |
      | session__model |
      | skill__load    |
      | skill__list    |
      | comm__send     |
      | hail__send     |

  Scenario: Crew deny overlays and does not drop a global deny
    Given config:
      | key         | value       |
      | tools.allow | :all        |
      | tools.deny  | [:exec/run] |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path       | value     |
      | model      | grover    |
      | soul       | Atticus.  |
      | tools.deny | [:fs/*]   |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt does not have tools:
      | name      |
      | exec__run |
      | fs__read  |
      | fs__write |
    And the prompt has tools:
      | name           |
      | web__fetch     |
      | web__search    |
      | memory__write  |
      | memory__get    |
      | memory__search |
      | session__info  |
      | session__model |
      | skill__load    |
      | skill__list    |
      | comm__send     |
      | hail__send     |

  Scenario: Crew deny all then allow memory leaves only memory tools
    Given config:
      | key         | value |
      | tools.allow | :all  |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path        | value        |
      | model       | grover       |
      | soul        | Atticus.     |
      | tools.deny  | :all         |
      | tools.allow | [:memory/*]  |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name           |
      | memory__write  |
      | memory__get    |
      | memory__search |
    And the prompt does not have tools:
      | name      |
      | fs__read  |
      | exec__run |

  Scenario: Config validate rejects [:all] — :all is the list, not a list item
    Given an empty Isaac root at "/tmp/isaac-allow-all-vec"
    And the isaac file "isaac.edn" exists with:
      """
      {:tools {:allow [:all]}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key         | value   |
      | tools.allow | #":all" |

  Scenario: Crew deny of a family overlays; other global allows remain
    Given config:
      | key         | value |
      | tools.allow | :all  |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path       | value     |
      | model      | grover    |
      | soul       | Atticus.  |
      | tools.deny | [:web/*]  |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the user sends "hello" on session "tools-test"
    Then the prompt does not have tools:
      | name       |
      | web__fetch |
      | web__search |
    And the prompt has tools:
      | name           |
      | fs__read       |
      | fs__write      |
      | fs__edit       |
      | fs__multi_edit |
      | fs__grep       |
      | fs__glob       |
      | exec__run      |
      | memory__write  |
      | memory__get    |
      | memory__search |
      | session__info  |
      | session__model |
      | skill__load    |
      | skill__list    |
      | comm__send     |
      | hail__send     |
