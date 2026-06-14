(ns tagging.crew-tags-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Crew tags"

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

  (it "crew with :tags round-trips through config get"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/stdout-contains ":project/chess")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew table includes a Tags column"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Name .* Model .* Provider .* Soul .* Tags"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --json includes tags on each record"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["0.name" "\"joe\""] ["0.tags" "[\"project/chess\", \"role/worker\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --edn includes tags as a set on each record"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --edn")
    (isaac.foundation.cli-steps/stdout-edn-contains {:headers ["path" "value"], :rows [["0.name" "\"joe\""] ["0.tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --tag filters to crews carrying that tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/verify}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --tag role/worker")
    (isaac.foundation.cli-steps/stdout-contains "joe")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sue")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --tag is repeatable with AND semantics"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --tag role/worker --tag project/chess")
    (isaac.foundation.cli-steps/stdout-contains "joe")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sue")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --without-tag excludes crews carrying that tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/verify}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --without-tag role/worker")
    (isaac.foundation.cli-steps/stdout-contains "sue")
    (isaac.foundation.cli-steps/stdout-does-not-contain "joe")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --without-tag is repeatable with AND-NOT semantics"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/verify}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ann.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/plan}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --without-tag role/worker --without-tag role/verify")
    (isaac.foundation.cli-steps/stdout-contains "ann")
    (isaac.foundation.cli-steps/stdout-does-not-contain "joe")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sue")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew --untagged shows only crews with empty tags"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --untagged")
    (isaac.foundation.cli-steps/stdout-contains "sue")
    (isaac.foundation.cli-steps/stdout-does-not-contain "joe")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew composes --tag and --without-tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :wip}"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/sue.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew --tag role/worker --without-tag wip")
    (isaac.foundation.cli-steps/stdout-contains "sue")
    (isaac.foundation.cli-steps/stdout-does-not-contain "joe")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew show <name> displays tags in the detail view"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew show joe")
    (isaac.foundation.cli-steps/stdout-contains "Tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/stdout-contains ":project/chess")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac crew show <name> --json includes tags"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :project/chess}"]]})
    (isaac.foundation.cli-steps/isaac-run "crew show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["name" "\"joe\""] ["tags" "[\"project/chess\", \"role/worker\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac config set crew.<name>.tags.<keyword> adds a tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/stdout-contains ":wip")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac config unset crew.<name>.tags.<keyword> removes a tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker :wip}"]]})
    (isaac.foundation.cli-steps/isaac-run "config unset crew.joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/stdout-does-not-contain ":wip")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac config set is idempotent when the tag is already present"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "config set crew.joe.tags.role/worker")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac config unset is idempotent when the tag is absent"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/joe.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["tags" "#{:role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "config unset crew.joe.tags.wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "config get crew.joe.tags")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/stdout-does-not-contain ":wip")
    (isaac.foundation.cli-steps/exit-code-is "0")))
