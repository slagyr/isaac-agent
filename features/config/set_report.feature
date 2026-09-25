@wip
Feature: config set / unset report the result first, then labelled validation warnings
  A config mutation's first line is its outcome — the confirmation on
  stdout, or the error on stderr. Validation warnings follow on the same
  stream under a "Validation warnings (N):" heading, and only for the
  path being changed; warnings elsewhere in the config collapse into a
  single count that points at `isaac config validate`, which still lists
  everything.

  A crew can acknowledge a deliberately broad directory grant with
  `:acknowledge-broad? true` beside `:allow`. An acknowledged grant is
  never reported again, not even by `config validate`; an unacknowledged
  one still is.

  Background:
    Given an Isaac root at "target/test-state"
    And the user home directory is "/tmp/grover-home"

  Scenario: warnings elsewhere in the config collapse to a count after the confirmation
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe    {:model :grover}
                   :marvin {:model :grover :tools {:directories {:allow [:cwd "/tmp/grover-home"]}}}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}}
      """
    When isaac is run with "config set crew.joe.model echo"
    Then the stdout lines contain in order:
      | pattern                                                |
      | set crew.joe.model = :echo                             |
      | 1 other validation warning — run: isaac config validate |
    And the stdout does not contain "grants the entire user home"
    And the stderr does not contain "grants the entire user home"
    And the exit code is 0

  Scenario: a refused set leads with the error, then the warning count
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe    {:model :grover}
                   :marvin {:model :grover :tools {:directories {:allow [:cwd "/tmp/grover-home"]}}}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}}
      """
    When isaac is run with "config set crew.joe.effort not-a-number"
    Then the stderr matches:
      | pattern                                        |
      | (?s)^error:.*crew\.joe\.effort.*1 other validation warning |
    And the stderr does not contain "grants the entire user home"
    And the exit code is 1

  Scenario: config validate still reports an unacknowledged broad directory grant
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe    {:model :grover}
                   :marvin {:model :grover :tools {:directories {:allow [:cwd "/tmp/grover-home"]}}}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "crew.marvin.tools.directories"
    And the stderr contains "grants the entire user home"
    And the exit code is 0

  Scenario: an acknowledged broad directory grant is never reported
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe    {:model :grover}
                   :marvin {:model :grover
                            :tools {:directories {:allow              [:cwd "/tmp/grover-home"]
                                                  :acknowledge-broad? true}}}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}}
      """
    When isaac is run with "config validate"
    Then the stderr does not contain "grants the entire user home"
    And the stdout does not contain "grants the entire user home"
    And the exit code is 0
    When isaac is run with "config set crew.joe.model echo"
    Then the stdout does not contain "validation warning"
    And the exit code is 0
