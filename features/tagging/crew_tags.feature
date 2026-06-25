Feature: Crew tags
  Crews carry a flat set of keyword tags for discovery and routing.
  Tags can be plain (:wip, :experimental) or namespaced
  (:role/worker, :project/chess); namespacing is a convention.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: crew with :tags round-trips through config get
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path | value                          |
      | tags | #{:role/worker :project/chess} |
    When isaac is run with "config get crew.joe.tags"
    Then the stdout contains ":role/worker"
    And the stdout contains ":project/chess"
    And the exit code is 0

  @wip
  Scenario: isaac crew list table includes a Tags column
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "crew list"
    Then the stdout matches:
      | pattern                                   |
      | Name .* Model .* Provider .* Soul .* Tags |
    And the exit code is 0

  @wip
  Scenario: isaac crew list --json includes tags on each record
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                          |
      | model | grover                         |
      | tags  | #{:role/worker :project/chess} |
    When isaac is run with "crew list --json"
    Then the stdout JSON contains:
      | path   | value                            |
      | 0.name | "joe"                            |
      | 0.tags | ["project/chess", "role/worker"] |
    And the exit code is 0

  @wip
  Scenario: isaac crew list --edn includes tags as a set on each record
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                          |
      | model | grover                         |
      | tags  | #{:role/worker :project/chess} |
    When isaac is run with "crew list --edn"
    Then the stdout EDN contains:
      | path   | value                          |
      | 0.name | "joe"                          |
      | 0.tags | #{:role/worker :project/chess} |
    And the exit code is 0

  @wip
  Scenario: isaac crew list --tag filters to crews carrying that tag
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/verify} |
    When isaac is run with "crew list --tag role/worker"
    Then the stdout contains "joe"
    And the stdout does not contain "sue"
    And the exit code is 0

  @wip
  Scenario: isaac crew list --tag is repeatable with AND semantics
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                          |
      | model | grover                         |
      | tags  | #{:role/worker :project/chess} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "crew list --tag role/worker --tag project/chess"
    Then the stdout contains "joe"
    And the stdout does not contain "sue"
    And the exit code is 0

  @wip
  Scenario: isaac crew list --without-tag excludes crews carrying that tag
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/verify} |
    When isaac is run with "crew list --without-tag role/worker"
    Then the stdout contains "sue"
    And the stdout does not contain "joe"
    And the exit code is 0

  @wip
  Scenario: isaac crew list --without-tag is repeatable with AND-NOT semantics
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/verify} |
    And the isaac EDN file "config/crew/ann.edn" exists with:
      | path  | value         |
      | model | grover        |
      | tags  | #{:role/plan} |
    When isaac is run with "crew list --without-tag role/worker --without-tag role/verify"
    Then the stdout contains "ann"
    And the stdout does not contain "joe"
    And the stdout does not contain "sue"
    And the exit code is 0

  @wip
  Scenario: isaac crew list --untagged shows only crews with empty tags
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value  |
      | model | grover |
    When isaac is run with "crew list --untagged"
    Then the stdout contains "sue"
    And the stdout does not contain "joe"
    And the exit code is 0

  @wip
  Scenario: isaac crew list composes --tag and --without-tag
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                |
      | model | grover               |
      | tags  | #{:role/worker :wip} |
    And the isaac EDN file "config/crew/sue.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "crew list --tag role/worker --without-tag wip"
    Then the stdout contains "sue"
    And the stdout does not contain "joe"
    And the exit code is 0

  Scenario: isaac crew show <name> displays tags in the detail view
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                          |
      | model | grover                         |
      | tags  | #{:role/worker :project/chess} |
    When isaac is run with "crew show joe"
    Then the stdout contains "Tags"
    And the stdout contains ":role/worker"
    And the stdout contains ":project/chess"
    And the exit code is 0

  @wip
  Scenario: isaac crew show <name> --json includes tags and full soul
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                          |
      | model | grover                         |
      | soul  | You are Joe.\nKeep the lantern dry. |
      | tags  | #{:role/worker :project/chess} |
    When isaac is run with "crew show joe --json"
    Then the stdout JSON contains:
      | path | value                                      |
      | name | "joe"                                      |
      | soul | "You are Joe.\nKeep the lantern dry."      |
      | tags | ["project/chess", "role/worker"]           |
    And the stdout does not contain "\"soul-source\""
    And the stdout does not contain "\"tags-text\""
    And the exit code is 0

  Scenario: isaac config set crew.<name>.tags.<keyword> adds a tag
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "config set crew.joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.tags"
    Then the stdout contains ":role/worker"
    And the stdout contains ":wip"
    And the exit code is 0

  Scenario: isaac config unset crew.<name>.tags.<keyword> removes a tag
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value                |
      | model | grover               |
      | tags  | #{:role/worker :wip} |
    When isaac is run with "config unset crew.joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.tags"
    Then the stdout contains ":role/worker"
    And the stdout does not contain ":wip"
    And the exit code is 0

  Scenario: isaac config set is idempotent when the tag is already present
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "config set crew.joe.tags.role/worker"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.tags"
    Then the stdout contains ":role/worker"
    And the exit code is 0

  Scenario: isaac config unset is idempotent when the tag is absent
    Given default Grover setup
    And the isaac EDN file "config/crew/joe.edn" exists with:
      | path  | value           |
      | model | grover          |
      | tags  | #{:role/worker} |
    When isaac is run with "config unset crew.joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "config get crew.joe.tags"
    Then the stdout contains ":role/worker"
    And the stdout does not contain ":wip"
    And the exit code is 0
