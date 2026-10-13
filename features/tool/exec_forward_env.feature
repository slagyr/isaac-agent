Feature: exec/run forwards named .env keys
  Isaac reads <root>/.env for ${VAR} substitution in config. It does not
  put those values in the environment of the commands exec/run starts, so
  a script a crew runs cannot see a token that lives there. The operator
  names the keys to forward:

    {:tools {:exec {:forward-env ["HARBOR_TOKEN"]}}}

  Only the named keys are copied into the command's environment. Nothing
  is forwarded by default, and a named key with no value is left unset
  rather than set to an empty string.
  Decision (2026-10-13, Micah).

  Background:
    Given default Grover setup
    And the isaac .env file contains:
      """
      HARBOR_TOKEN=tide-pool-7
      LIGHTHOUSE_KEY=lamp-oil-3
      EMPTY_TOKEN=
      """

  Scenario: a named key reaches the command, and an unnamed key does not
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                   | value            |
      | tools.exec.forward-env | ["HARBOR_TOKEN"] |
    When the tool "exec__run" is called with:
      | command | echo "harbor=[$HARBOR_TOKEN] lighthouse=[$LIGHTHOUSE_KEY]" |
    Then the tool result is not an error
    And the tool result contains "harbor=[tide-pool-7] lighthouse=[]"

  Scenario: nothing is forwarded unless it is named
    When the tool "exec__run" is called with:
      | command | echo "harbor=[$HARBOR_TOKEN]" |
    Then the tool result is not an error
    And the tool result contains "harbor=[]"

  Scenario: a named key with no value is left unset
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                   | value                           |
      | tools.exec.forward-env | ["EMPTY_TOKEN" "FOGHORN_TOKEN"] |
    When the tool "exec__run" is called with:
      | command | echo "empty=${EMPTY_TOKEN-unset} foghorn=${FOGHORN_TOKEN-unset}" |
    Then the tool result is not an error
    And the tool result contains "empty=unset foghorn=unset"
