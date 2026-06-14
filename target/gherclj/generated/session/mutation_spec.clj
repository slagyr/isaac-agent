(ns session.mutation-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session mutation"

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

  (it "isaac sessions set <id>.tags.<keyword> adds a tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/x}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["tags" "[\"project/x\", \"wip\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions unset <id>.tags.<keyword> removes a tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/x :wip}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions unset joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["tags" "[\"project/x\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions set <id>.crew reassigns to a known crew"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/alice.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["joe" "main"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.crew alice")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["crew" "\"alice\""]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions set <id>.crew errors when the target crew doesn't exist"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["joe" "main"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.crew nobody")
    (isaac.foundation.cli-steps/stderr-contains "nobody")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "isaac sessions set errors on an immutable field"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["joe" "main"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.id different-id")
    (isaac.foundation.cli-steps/stderr-contains "immutable")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "isaac sessions set errors on a system-managed field"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["joe" "main"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.input-tokens 42")
    (isaac.foundation.cli-steps/stderr-contains "system-managed")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "isaac sessions set errors on an unknown field"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew"], :rows [["joe" "main"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.bogus value")
    (isaac.foundation.cli-steps/stderr-contains "bogus")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "isaac sessions set is idempotent when the value is already present"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/x}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.tags.project/x")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["tags" "[\"project/x\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions unset is idempotent when the value is absent"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/x}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions unset joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["tags" "[\"project/x\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "successful session mutation bumps :updated-at"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags" "updated-at"], :rows [["joe" "main" "#{}" "1999-12-31T23:59:59Z"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions set joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-does-not-contain "1999-12-31T23:59:59Z")
    (isaac.foundation.cli-steps/stdout-contains "wip")
    (isaac.foundation.cli-steps/exit-code-is "0")))
