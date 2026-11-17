Feature: one command adds an outbound recipient (isaac-tnry)

  A comm slot's `:comm/allow-to` names the only targets it will send to.
  Micah keeps that list, so adding a recipient is one short command. It writes
  the slot's config file; the running server picks the change up by hot
  reload like any other config edit.

  Background:
    Given an Isaac root at "target/test-state"
    And the user home directory is "/tmp/grover-home"
    And the telly comm module is registered

  @wip
  Scenario: comms allow adds a target to the slot's allow-list
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe {:model :grover}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}
       :comms     {:tannoy {:type :telly :comm/allow-to ["bridge"]}}}
      """
    When isaac is run with "comms allow tannoy lookout"
    Then the stdout contains "tannoy allows lookout"
    And the exit code is 0
    When isaac is run with "comms allow tannoy"
    Then the stdout contains "bridge"
    And the stdout contains "lookout"
    And the exit code is 0

  @wip
  Scenario: comms allow on a slot that does not exist is refused
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :joe} :crew {:model :grover}}
       :crew      {:joe {:model :grover}}
       :models    {:grover {:model "echo" :provider :grover}}
       :providers {:grover {}}
       :comms     {:tannoy {:type :telly}}}
      """
    When isaac is run with "comms allow foghorn lookout"
    Then the stderr contains "foghorn"
    And the exit code is 1
