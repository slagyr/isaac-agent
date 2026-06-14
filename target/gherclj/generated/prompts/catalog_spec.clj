(ns prompts.catalog-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.prompt.catalog-steps :as catalog-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Prepared-prompt catalog (commands + skills)"

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

  (it "a markdown file with type command is discovered as a command"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/commands/work.md" "---\ntype: command\ndescription: Start work on a ready bean\nparams: [bean]\n---\nStart work on bean {{bean}}. Follow the project conventions.")
    (isaac.prompt.catalog-steps/prompt-catalog-resolved)
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "description"], :rows [["work" "command" "Start work on a ready bean"]]}))

  (it "a SKILL.md file with type skill is discovered as a skill"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/tdd/SKILL.md" "---\ntype: skill\ndescription: Use when writing or changing code\n---\nWrite a failing test first, then the simplest code to pass it, then refactor.")
    (isaac.prompt.catalog-steps/prompt-catalog-resolved)
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "description"], :rows [["tdd" "skill" "Use when writing or changing code"]]}))

  (it "an explicit type overrides a conflicting directory location"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/commands/helper.md" "---\ntype: skill\ndescription: A skill that happens to live under commands/\n---\nSome reusable guidance.")
    (isaac.prompt.catalog-steps/prompt-catalog-resolved)
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "#comment"], :rows [["helper" "skill" "explicit type wins over the commands/ dir"]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "name"], :rows [["warn" ":prompt/type-conflict" "helper"]]}))

  (it "user-invocable provides the type for a file in a generic root"
    ;; given an Isaac root at "target/test-state"
    ;; given config:
    ;; given the isaac file "config/prompts/review.md" exists with:
    ;; when the prompt catalog is resolved
    ;; then the prompt catalog contains:
    (pending "not yet implemented"))

  (it "directory decides type when neither type nor user-invocable is present"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/skills/clojure/SKILL.md" "---\ndescription: Clojure conventions\n---\nPrefer threading macros; keep functions small.")
    (isaac.prompt.catalog-steps/prompt-catalog-resolved)
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "#comment"], :rows [["clojure" "skill" "no type:/user-invocable -> skills/ dir decides"]]}))

  (it "a project command shadows a global command of the same name"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/commands/work.md" "---\ntype: command\ndescription: GLOBAL work\n---\nGlobal work prompt.")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["proj-s" "main" "target/proj"]]})
    (isaac.foundation.fs-steps/file-with-content "target/proj/.isaac/commands/work.md" "---\ntype: command\ndescription: PROJECT work\n---\nProject work prompt.")
    (isaac.prompt.catalog-steps/prompt-catalog-for-session-resolved "\"proj-s\"")
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "description" "#comment"], :rows [["work" "command" "PROJECT work" "project shadows global"]]}))

  (it "the project root is found by walking up from a subdirectory cwd"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["deep-s" "main" "target/proj/src/deep"]]})
    (isaac.foundation.fs-steps/file-with-content "target/proj/.isaac/commands/work.md" "---\ntype: command\ndescription: PROJECT work\n---\nProject work prompt.")
    (isaac.prompt.catalog-steps/prompt-catalog-for-session-resolved "\"deep-s\"")
    (isaac.prompt.catalog-steps/prompt-catalog-contains {:headers ["name" "type" "description" "#comment"], :rows [["work" "command" "PROJECT work" "root found at ancestor target/proj/.isaac"]]}))

  (it "custom directory names map to types via prompt-dir-names config"
    ;; given an Isaac root at "target/test-state"
    ;; given config:
    ;; given the isaac file "config/abilities/refactor.md" exists with:
    ;; when the prompt catalog is resolved
    ;; then the prompt catalog contains:
    (pending "not yet implemented")))
