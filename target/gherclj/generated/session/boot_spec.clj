(ns session.boot-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Boot Files"

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

  (it "session includes AGENTS.md from cwd in system prompt"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "cwd"], :rows [["boot-test" "target/test-project"]]})
    (isaac.foundation.fs-steps/file-exists-with "target/test-project/AGENTS.md" "## House Rules\nNo tabs. Ever. Hieronymus will judge you.")
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "boot-test")
    (isaac.session.session-steps/system-prompt-contains "You are Atticus.")
    (isaac.session.session-steps/system-prompt-contains "Hieronymus will judge you"))

  (it "session works without AGENTS.md in cwd"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name" "cwd"], :rows [["boot-test" "target/empty-project"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "boot-test")
    (isaac.session.session-steps/system-prompt-contains "You are Atticus.")
    (isaac.session.session-steps/system-prompt-not-contains "Hieronymus")))
