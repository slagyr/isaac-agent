Feature: isaac config schema CLI shows allowed values for dynamic fields

  `isaac config schema <path>` is config-aware: it reads `:modules` from
  the on-disk config and consults each declared module's manifest when
  rendering schema paths under the union tables `:comms` and
  `:providers`. Manifest-supplied (variant) fields surface with an
  automatic `[type-name]` prefix on the field entry — the prefix is the
  only visual change from today's renderer.
  The existing color, layout, and `spec->term` formatting MUST be
  preserved: no new section headers, no `type: X` dividers, no per-type
  groupings. An aggregate view (e.g. `comms.value`) is a single flat
  list of base fields plus every manifest-contributed field across
  every known `:type`, each prefixed. When two variants happen to
  declare the same field name, each appears as its own entry
  distinguished by the prefix.

  The list of known `:type` options and the merged field set come from
  the loader's resolved `:module-index` only — NOT from a live registry
  or any other ambient source. Comm/tool/slash-command kinds that are
  not present in any declared manifest do NOT appear in the output.

  Scenario: comm slot :type lists user-configurable comm kinds from manifests
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}
       :modules   {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}}
      """
    When isaac is run with "config schema comms.value.type"
    Then the stdout matches:
      | pattern         |
      | options:.*telly |
    And the stdout does not match:
      | pattern          |
      | options:.*acp    |
      | options:.*cli    |
      | options:.*hooks  |
      | options:.*memory |
      | options:.*null   |
    And the exit code is 0

  Scenario: config schema renders manifest-supplied comm fields with provenance prefix
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}}
      """
    When isaac is run with "config schema comms.value.loft"
    Then the stdout matches:
      | pattern        |
      | :loft          |
      | \[telly\]      |
      | string         |
    And the exit code is 0

  Scenario: config schema comms.value renders every manifest-supplied field inline
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}}
      """
    When isaac is run with "config schema comms.value"
    Then the stdout matches:
      | pattern          |
      | :crew            |
      | :type            |
      | :loft            |
      | :color           |
      | :mood            |
      | \[telly\]        |
    And the stdout does not match:
      | pattern    |
      | type:\s+acp     |
      | type:\s+cli     |
      | type:\s+hooks   |
      | type:\s+memory  |
      | type:\s+null    |
      | type:\s+telly\s |
      | no manifest fields |
    And the exit code is 0

  Scenario: config schema comms.value with no modules shows only base fields
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {}
      """
    When isaac is run with "config schema comms.value"
    Then the stdout matches:
      | pattern |
      | :type   |
      | :crew   |
    And the stdout does not match:
      | pattern   |
      | \[telly\] |
    And the exit code is 0

  Scenario: config schema for a manifest-supplied field errors when the module isn't declared
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {}
      """
    When isaac is run with "config schema comms.value.loft"
    Then the stderr matches:
      | pattern            |
      | Path not found     |
      | comms\.value\.loft |
    And the exit code is 1

  Scenario: config schema renders manifest-supplied provider fields with provenance prefix
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.providers.kombucha {:local/root "modules/isaac.providers.kombucha"}}}
      """
    When isaac is run with "config schema providers.value.fizz-level"
    Then the stdout matches:
      | pattern      |
      | :fizz-level  |
      | \[kombucha\] |
      | int          |
    And the exit code is 0

  Scenario: config schema renders the statically-declared tool config fields
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {}
      """
    When isaac is run with "config schema tools.web_search.api-key"
    Then the stdout matches:
      | pattern  |
      | :api-key |
      | string   |
    And the exit code is 0

