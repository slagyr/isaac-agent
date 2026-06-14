(ns config.schema-cli-options-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "isaac config schema CLI shows allowed values for dynamic fields"

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

  (it "comm slot :type lists user-configurable comm kinds from manifests"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:defaults  {:crew :main :model :local}\n :crew      {:main {}}\n :models    {:local {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}\n :modules   {:isaac.comm.telly {:local/root \"modules/isaac.comm.telly\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config schema comms.value.type")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["options:.*telly"]]})
    (isaac.foundation.cli-steps/stdout-does-not-match {:headers ["pattern"], :rows [["options:.*acp"] ["options:.*cli"] ["options:.*hooks"] ["options:.*memory"] ["options:.*null"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema renders manifest-supplied comm fields with provenance prefix"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:modules {:isaac.comm.telly {:local/root \"modules/isaac.comm.telly\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config schema comms.value.loft")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [[":loft"] ["\\[telly\\]"] ["string"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema comms.value renders every manifest-supplied field inline"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:modules {:isaac.comm.telly {:local/root \"modules/isaac.comm.telly\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config schema comms.value")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [[":crew"] [":type"] [":loft"] [":color"] [":mood"] ["\\[telly\\]"]]})
    (isaac.foundation.cli-steps/stdout-does-not-match {:headers ["pattern"], :rows [["type:\\s+acp"] ["type:\\s+cli"] ["type:\\s+hooks"] ["type:\\s+memory"] ["type:\\s+null"] ["type:\\s+telly\\s"] ["no manifest fields"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema comms.value with no modules shows only base fields"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{}")
    (isaac.foundation.cli-steps/isaac-run "config schema comms.value")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [[":type"] [":crew"]]})
    (isaac.foundation.cli-steps/stdout-does-not-match {:headers ["pattern"], :rows [["\\[telly\\]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema for a manifest-supplied field errors when the module isn't declared"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{}")
    (isaac.foundation.cli-steps/isaac-run "config schema comms.value.loft")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["Path not found"] ["comms\\.value\\.loft"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "config schema renders manifest-supplied provider fields with provenance prefix"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:modules {:isaac.providers.kombucha {:local/root \"modules/isaac.providers.kombucha\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config schema providers.value.fizz-level")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [[":fizz-level"] ["\\[kombucha\\]"] ["int"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema renders the statically-declared tool config fields"
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{}")
    (isaac.foundation.cli-steps/isaac-run "config schema tools.web_search.api-key")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [[":api-key"] ["string"]]})
    (isaac.foundation.cli-steps/exit-code-is "0")))
