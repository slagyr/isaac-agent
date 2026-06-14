Feature: web_search tool config schema
  The :tools :web_search config surface is formally validated from the
  manifest-declared :schema for the built-in tool.

  Background:
    Given an empty Isaac root at "/tmp/isaac-wsconf"

  Scenario: :tools key is accepted at root config level without triggering an unknown-key warning
    Given config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave :api-key "sk-test"}}}
      """
    When the config is loaded
    Then the config has no validation warnings

  Scenario: Valid brave config with all required fields passes without errors
    Given a brave provider is registered for web_search with schema:
      """
      {:api-key {:type :string :required? true}}
      """
    And config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave :api-key "sk-test"}}}
      """
    When the config is loaded
    Then the config has no validation errors

  Scenario: Missing :api-key for brave produces a required-field validation error
    Given a brave provider is registered for web_search with schema:
      """
      {:api-key {:type :string :required? true}}
      """
    And config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                      | value    |
      | tools.web_search.api-key | required |

  Scenario: Unknown key under :tools :web_search produces a warning
    Given a brave provider is registered for web_search with schema:
      """
      {:api-key {:type :string}}
      """
    And config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave :api-key "sk" :mystery "value"}}}
      """
    When the config is loaded
    Then the config has validation warnings matching:
      | key                      | value       |
      | tools.web_search.mystery | unknown key |

  Scenario: A :provider value outside the manifest enum is rejected
    Given config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :unknown-provider :api-key "sk"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                       | value                 |
      | tools.web_search.provider | must be one of :brave |

  Scenario: A non-string value for a string field is coerced rather than rejected
    Given config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave :api-key 12345}}}
      """
    When the config is loaded
    Then the config has no validation errors

  Scenario: Provider schema keys compose with tool schema — no spurious unknown-key warnings
    Given a brave provider is registered for web_search with schema:
      """
      {:api-key {:type :string}}
      """
    And config file "isaac.edn" containing:
      """
      {:tools {:web_search {:provider :brave :api-key "sk-test"}}}
      """
    When the config is loaded
    Then the config has no validation warnings
