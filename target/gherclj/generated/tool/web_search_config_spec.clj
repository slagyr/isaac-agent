(ns tool.web-search-config-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "web_search tool config schema"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it ":tools key is accepted at root config level without triggering an unknown-key warning"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave :api-key \"sk-test\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-no-validation-warnings))

  (it "Valid brave config with all required fields passes without errors"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.tool.tools-steps/provider-registered-for-tool-with-schema "brave" "web_search" "{:api-key {:type :string :required? true}}")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave :api-key \"sk-test\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-no-validation-errors))

  (it "Missing :api-key for brave produces a required-field validation error"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.tool.tools-steps/provider-registered-for-tool-with-schema "brave" "web_search" "{:api-key {:type :string :required? true}}")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["tools.web_search.api-key" "required"]]}))

  (it "Unknown key under :tools :web_search produces a warning"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.tool.tools-steps/provider-registered-for-tool-with-schema "brave" "web_search" "{:api-key {:type :string}}")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave :api-key \"sk\" :mystery \"value\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-warnings {:headers ["key" "value"], :rows [["tools.web_search.mystery" "unknown key"]]}))

  (it "A :provider value outside the manifest enum is rejected"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :unknown-provider :api-key \"sk\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["tools.web_search.provider" "must be one of :brave"]]}))

  (it "A non-string value for a string field is coerced rather than rejected"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave :api-key 12345}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-no-validation-errors))

  (it "Provider schema keys compose with tool schema — no spurious unknown-key warnings"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac-wsconf\"")
    (isaac.tool.tools-steps/provider-registered-for-tool-with-schema "brave" "web_search" "{:api-key {:type :string}}")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:tools {:web_search {:provider :brave :api-key \"sk-test\"}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-no-validation-warnings)))
