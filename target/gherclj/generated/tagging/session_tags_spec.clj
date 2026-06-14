(ns tagging.session-tags-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session tags"

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

  (it "isaac prompt --tag tags the created session"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hi" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi' --tag project/chess --tag wip")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions list --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["0.tags" "[\"project/chess\", \"wip\"]"]]}))

  (it "isaac prompt without --tag creates session with empty tags"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hi" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/isaac-run "sessions list --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["0.tags" "[]"]]}))

  (it "isaac sessions list shows a Tags column"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess :wip}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions list")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Name .* Crew .* Tags"] ["joe .* :project/chess"] ["joe .* :wip"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions list --json includes tags on each record"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess :wip}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions list --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["0.name" "\"joe\""] ["0.tags" "[\"project/chess\", \"wip\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions list --tag filters to sessions carrying that tag"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess}"] ["sue" "main" "#{:project/poker}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions list --tag project/chess")
    (isaac.foundation.cli-steps/stdout-contains "joe")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sue")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions list --tag composes with --not-in-flight"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess}"] ["sue" "main" "#{:project/chess}"]]})
    (isaac.session.session-steps/session-is-in-flight "joe")
    (isaac.foundation.cli-steps/isaac-run "sessions list --tag project/chess --not-in-flight")
    (isaac.foundation.cli-steps/stdout-contains "sue")
    (isaac.foundation.cli-steps/stdout-does-not-contain "joe")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions show <id> displays tags in the detail view"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess :role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions show joe")
    (isaac.foundation.cli-steps/stdout-contains "Tags")
    (isaac.foundation.cli-steps/stdout-contains ":project/chess")
    (isaac.foundation.cli-steps/stdout-contains ":role/worker")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "isaac sessions show <id> --json includes tags"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "tags"], :rows [["joe" "main" "#{:project/chess :role/worker}"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions show joe --json")
    (isaac.foundation.cli-steps/stdout-json-contains {:headers ["path" "value"], :rows [["name" "\"joe\""] ["tags" "[\"project/chess\", \"role/worker\"]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0")))
