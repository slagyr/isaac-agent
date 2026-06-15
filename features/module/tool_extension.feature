Feature: Tool extension
  Tools are declared as :tools extensions in module manifests. Each
  entry has a :factory pointing at code and an optional :schema
  declaring per-tool user-config fields. The manifest installs every
  tool by default; user config under :tools <name> at the root level
  parameterizes the few tools that need it (api keys, etc.). The
  factory receives this config and returns the tool spec — its
  :description, :parameters, and :handler — so implementation and
  LLM-facing contract change together in code.

  Scenario: Tool config is rejected when a required :schema field is missing
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:tools {:web_search {:provider :brave}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                      | value             |
      | tools.web_search.api-key | required          |
