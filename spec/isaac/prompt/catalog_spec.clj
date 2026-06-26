;; mutation-tested: pending
(ns isaac.prompt.catalog-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.prompt.catalog :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(def ^:private root "/test-state")

(defn- write-file! [path content]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path content)))

(defn- write-config-file! [relative content]
  (write-file! (str root "/" relative) content))

(defn- resolve-catalog
  ([] (resolve-catalog {}))
  ([opts]
   (sut/resolve-catalog (merge {:fs        (nexus/get :fs)
                                :root root}
                               opts))))

(describe "prompt catalog"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "discovers a command from prompts/commands"
    (write-config-file! "prompts/commands/work.md"
                        (str "---\n"
                             "type: command\n"
                             "description: Start work on a ready bean\n"
                             "params: [bean]\n"
                             "---\n\n"
                             "Start work on bean {{bean}}."))
    (should= {:description "Start work on a ready bean"
              :name        "work"
              :type        :command}
             (select-keys (get-in (resolve-catalog) [:commands "work"])
                          [:description :name :type])))

  (it "discovers a skill from SKILL.md"
    (write-config-file! "prompts/skills/tdd/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when writing code\n"
                             "---\n\n"
                             "Write a failing test first."))
    (should= {:description "Use when writing code"
              :name        "tdd"
              :type        :skill}
             (select-keys (get-in (resolve-catalog) [:skills "tdd"])
                          [:description :name :type])))

  (it "discovers a rule from prompts/rules"
    (write-config-file! "prompts/rules/greenhouse-standards.md"
                        (str "---\n"
                             "type: rule\n"
                             "description: Greenhouse operating standards\n"
                             "---\n\n"
                             "Never vent atmosphere while specimens are unsealed."))
    (should= {:description "Greenhouse operating standards"
              :name        "greenhouse-standards"
              :type        :rule}
             (select-keys (get-in (resolve-catalog) [:rules "greenhouse-standards"])
                          [:description :name :type])))

  (it "prefers explicit type over a conflicting directory signal and warns"
    (write-config-file! "prompts/commands/helper.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Reusable helper\n"
                             "---\n\n"
                             "Some reusable guidance."))
    (let [catalog (resolve-catalog)
          entry   (first (filter #(= :prompt/type-conflict (:event %)) @log/captured-logs))]
      (should= {:description "Reusable helper"
                :name        "helper"
                :type        :skill}
               (select-keys (get-in catalog [:skills "helper"])
                            [:description :name :type]))
      (should= {:level :warn :event :prompt/type-conflict :name "helper"}
               (select-keys entry [:level :event :name]))))

  (it "uses user-invocable when a generic root has no explicit type"
    (write-file! (str root "/config/isaac.edn")
                 "{:prompt-paths [\"vendor/prompts\"]}")
    (write-config-file! "vendor/prompts/review.md"
                        (str "---\n"
                             "description: Review a pull request\n"
                             "user-invocable: true\n"
                             "---\n\n"
                             "Review PR {{pr}}."))
    (should= {:description "Review a pull request"
              :name        "review"
              :type        :command}
             (select-keys (get-in (resolve-catalog {:config {:prompt-paths ["vendor/prompts"]}}) [:commands "review"])
                          [:description :name :type])))

  (it "falls back to the directory type map when frontmatter provides no signal"
    (write-config-file! "prompts/abilities/refactor.md"
                        (str "---\n"
                             "description: Refactoring guidance\n"
                             "---\n\n"
                             "Make small, safe steps."))
    (should= {:description "Refactoring guidance"
              :name        "refactor"
              :type        :skill}
             (select-keys (get-in (resolve-catalog {:config {:prompt-dir-names {"abilities" "skill"}}})
                                  [:skills "refactor"])
                          [:description :name :type])))

  (it "lets project entries shadow global entries of the same type and name"
    (write-config-file! "prompts/commands/work.md"
                        (str "---\n"
                             "type: command\n"
                             "description: GLOBAL work\n"
                             "---\n\n"
                             "Global work prompt."))
    (write-file! "/workspace/proj/.isaac/prompts/commands/work.md"
                 (str "---\n"
                      "type: command\n"
                      "description: PROJECT work\n"
                      "---\n\n"
                      "Project work prompt."))
    (should= {:description "PROJECT work"
              :name        "work"
              :type        :command}
             (select-keys (get-in (resolve-catalog {:cwd "/workspace/proj"}) [:commands "work"])
                          [:description :name :type])))

  (it "finds the project root by walking up from the cwd"
    (write-file! "/workspace/proj/.isaac/prompts/commands/work.md"
                 (str "---\n"
                      "type: command\n"
                      "description: PROJECT work\n"
                      "---\n\n"
                      "Project work prompt."))
    (should= {:description "PROJECT work"
              :name        "work"
              :type        :command}
             (select-keys (get-in (resolve-catalog {:cwd "/workspace/proj/src/deep"}) [:commands "work"])
                          [:description :name :type])))

  (it "supports extra typed roots from config"
    (write-file! "/workspace/commands/review.md"
                 (str "---\n"
                      "description: Review a pull request\n"
                      "---\n\n"
                      "Review PR {{pr}}."))
    (should= {:description "Review a pull request"
              :name        "review"
              :type        :command}
             (select-keys (get-in (resolve-catalog {:config {:command-paths ["/workspace/commands"]}}) [:commands "review"])
                          [:description :name :type])))

  (it "emits a debug timing log with counts when the catalog resolves"
    (write-config-file! "prompts/commands/work.md"
                        (str "---\n"
                             "type: command\n"
                             "description: Start work on a ready bean\n"
                             "---\n\n"
                             "Start work on bean {{bean}}."))
    (write-config-file! "prompts/skills/tdd/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when writing code\n"
                             "---\n\n"
                             "Write a failing test first."))
    (let [entry (do (resolve-catalog)
                    (first (filter #(= :prompt/catalog-resolved (:event %)) @log/captured-logs)))]
      (should= :debug (:level entry))
      (should= 1 (:command-count entry))
      (should= 1 (:skill-count entry))
      (should (number? (:file-count entry)))
      (should (number? (:elapsed-ms entry)))))

  (it "resolves command prompt text with params substituted"
    (write-config-file! "prompts/commands/work.md"
                        (str "---\n"
                             "type: command\n"
                             "params: [bean]\n"
                             "---\n\n"
                             "Start work on {{bean}}."))
    (should= {:name  "work"
              :input "Start work on isaac-1234."}
             (select-keys (sut/resolve-command-prompt {:fs        (nexus/get :fs)
                                                       :root root}
                                                      "work"
                                                      "isaac-1234")
                          [:name :input])))

  (it "appends declared skill bodies to the resolved command prompt"
    (write-config-file! "prompts/skills/tdd/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "---\n\n"
                             "Write a failing test first."))
    (write-config-file! "prompts/skills/gherclj/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "---\n\n"
                             "Reuse existing feature steps."))
    (write-config-file! "prompts/commands/work.md"
                        (str "---\n"
                             "type: command\n"
                             "params: [bean]\n"
                             "skills: [tdd, gherclj]\n"
                             "---\n\n"
                             "Start work on {{bean}}."))
    (should= {:name  "work"
              :input (str "Start work on isaac-1234.\n\n"
                          "Write a failing test first.\n\n"
                          "Reuse existing feature steps.")}
             (select-keys (sut/resolve-command-prompt {:fs        (nexus/get :fs)
                                                       :root root}
                                                      "work"
                                                      "isaac-1234")
                          [:name :input])))

  (it "renders a stable sorted skill menu and enables load_skill"
    (write-config-file! "prompts/skills/greenhouse-protocol/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when tending specimens\n"
                             "---\n\n"
                             "Always quarantine new specimens for one cycle."))
    (write-config-file! "prompts/skills/aeroponics/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use for soil-free growing\n"
                             "---\n\n"
                             "Mist the roots on a schedule."))
    (should= {:menu-text  (str "Available skills:\n"
                               "- aeroponics: Use for soil-free growing\n"
                               "- greenhouse-protocol: Use when tending specimens\n\n"
                               "Use load_skill to load a skill body on demand.")
              :tool-names #{"load_skill"}}
             (sut/resolve-skill-disclosure {:fs        (nexus/get :fs)
                                            :root root})))

  (it "falls back to list_skills when the menu threshold is exceeded"
    (write-config-file! "prompts/skills/a/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: First\n"
                             "---\n\n"
                             "One."))
    (write-config-file! "prompts/skills/b/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Second\n"
                             "---\n\n"
                             "Two."))
    (should= {:menu-text  nil
              :tool-names #{"list_skills" "load_skill"}}
             (sut/resolve-skill-disclosure {:config    {:skill-menu-threshold 1}
                                            :fs        (nexus/get :fs)
                                            :root root})))

  (it "resolves a discovered skill body by name"
    (write-config-file! "prompts/skills/greenhouse-protocol/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when tending specimens\n"
                             "---\n\n"
                             "Always quarantine new specimens for one cycle."))
    (should= "Always quarantine new specimens for one cycle."
             (sut/resolve-skill-body {:fs        (nexus/get :fs)
                                      :root root}
                                     "greenhouse-protocol")))

  (it "resolves a bundled skill resource by name"
    (write-config-file! "prompts/skills/greenhouse-protocol/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when tending specimens\n"
                             "---\n\n"
                             "Follow checklist.md."))
    (write-config-file! "prompts/skills/greenhouse-protocol/checklist.md"
                        "1. Check soil moisture.\n2. Quarantine new specimens.")
    (should= {:body "1. Check soil moisture.\n2. Quarantine new specimens."}
             (sut/resolve-skill-resource {:fs        (nexus/get :fs)
                                          :root root}
                                         "greenhouse-protocol"
                                         "checklist.md")))

  (it "rejects a bundled skill resource that escapes the skill directory"
    (write-config-file! "prompts/skills/greenhouse-protocol/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when tending specimens\n"
                             "---\n\n"
                             "Follow checklist.md."))
    (should= {:error :path-outside-skill}
             (select-keys (sut/resolve-skill-resource {:fs        (nexus/get :fs)
                                                       :root root}
                                                      "greenhouse-protocol"
                                                      "../../auth.json")
                          [:error])))

  (it "returns a not found error when a bundled skill resource is missing"
    (write-config-file! "prompts/skills/greenhouse-protocol/SKILL.md"
                        (str "---\n"
                             "type: skill\n"
                             "description: Use when tending specimens\n"
                             "---\n\n"
                             "Follow checklist.md."))
    (should= {:error :resource-not-found}
             (select-keys (sut/resolve-skill-resource {:fs        (nexus/get :fs)
                                                       :root root}
                                                      "greenhouse-protocol"
                                                      "missing.md")
                          [:error])))

  (it "does not discover prompts left under the legacy config/ typed-base"
    (write-config-file! "config/commands/legacy.md"
                        (str "---\n"
                             "type: command\n"
                             "description: Legacy location\n"
                             "---\n\n"
                             "Should not be discovered."))
    (should-be-nil (get-in (resolve-catalog) [:commands "legacy"])))

  (it "preserves prompt catalog config keys when config is loaded"
    (write-config-file! "config/isaac.edn"
                        "{:prompt-paths [\"vendor/prompts\"] :prompt-dir-names {\"abilities\" \"skill\"}}")
    (let [config (:config (loader/load-config-result {:root root
                                                      :fs        (nexus/get :fs)}))]
      (should= ["vendor/prompts"] (:prompt-paths config))
      (should= {"abilities" "skill"} (:prompt-dir-names config)))))
