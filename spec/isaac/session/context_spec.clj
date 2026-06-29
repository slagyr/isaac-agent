(ns isaac.session.context-spec
  (:require
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.session.spec-helper :as helper]
    [isaac.session.context :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around describe it should should-be-nil should=]]))

(def test-root "/test/session-context")
(def crew-name marigold/captain)
(def crew-soul (:soul (marigold/crew-cfg crew-name)))

(describe "read-boot-files"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "reads AGENTS.md from the discovered project root"
    (fs/spit (nexus/get :fs) (str test-root "/project/AGENTS.md") "## House Rules\nNo tabs.")
    (let [boot-files (sut/read-boot-files (str test-root "/project"))]
      (should (.contains boot-files "House Rules"))))

  (it "reads AGENTS.md by walking up from a nested cwd"
    (fs/spit (nexus/get :fs) (str test-root "/project/AGENTS.md") "## House Rules\nNo tabs.")
    (let [boot-files (sut/read-boot-files (str test-root "/project/src/deep"))]
      (should (.contains boot-files "House Rules"))))

  (it "returns nil when AGENTS.md is missing"
    (should-be-nil (sut/read-boot-files (str test-root "/missing-project")))))

  (it "reads AGENTS.md from the installed runtime fs without binding a thread-local fs"
    (let [mem (fs/mem-fs)]
      (fs/spit mem (str test-root "/project-runtime/AGENTS.md") "## Runtime Rules\nNo globals.")
      (nexus/-with-nexus {:fs mem}
        (let [boot-files (sut/read-boot-files (str test-root "/project-runtime"))]
          (should (.contains boot-files "Runtime Rules"))))))

(describe "read-rules-text"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "returns global and project rules in stable name order"
    (let [cfg {:root test-root}]
      (fs/spit (nexus/get :fs) (str test-root "/prompts/rules/quarantine.md")
               (str "---\n"
                    "type: rule\n"
                    "description: Quarantine discipline\n"
                    "---\n\n"
                    "Isolate new specimens for one cycle."))
      (fs/spit (nexus/get :fs) (str test-root "/prompts/rules/airlock.md")
               (str "---\n"
                    "type: rule\n"
                    "description: Airlock discipline\n"
                    "---\n\n"
                    "Seal both doors before cycling."))
      (fs/spit (nexus/get :fs) (str test-root "/project/.isaac/prompts/rules/greenhouse.md")
               (str "---\n"
                    "type: rule\n"
                    "description: Greenhouse standing orders\n"
                    "---\n\n"
                    "Never vent atmosphere while specimens are unsealed."))
      (should= (str "Seal both doors before cycling.\n\n"
                    "Isolate new specimens for one cycle.\n\n"
                    "Never vent atmosphere while specimens are unsealed.")
               (sut/read-rules-text cfg test-root (str test-root "/project")))))

  (it "returns nil when no rules are discovered"
    (should-be-nil (sut/read-rules-text {:root test-root} test-root (str test-root "/missing-project")))))

(describe "read-skill-disclosure"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "returns a cached skill menu and load_skill when skills are discovered"
    (fs/spit (nexus/get :fs) (str test-root "/prompts/skills/greenhouse-protocol/SKILL.md")
             (str "---\n"
                  "type: skill\n"
                  "description: Use when tending specimens\n"
                  "---\n\n"
                  "Always quarantine new specimens for one cycle."))
    (should= {:menu-text  (str "Available skills:\n"
                               "- greenhouse-protocol: Use when tending specimens\n\n"
                               "Use load_skill to load a skill body on demand.")
              :tool-names #{"load_skill"}}
             (sut/read-skill-disclosure {:root test-root} test-root (str test-root "/project"))))

  (it "falls back to list_skills when the configured threshold is exceeded"
    (fs/spit (nexus/get :fs) (str test-root "/prompts/skills/a/SKILL.md")
             (str "---\n"
                  "type: skill\n"
                  "description: First\n"
                  "---\n\n"
                  "One."))
    (fs/spit (nexus/get :fs) (str test-root "/prompts/skills/b/SKILL.md")
             (str "---\n"
                  "type: skill\n"
                  "description: Second\n"
                  "---\n\n"
                  "Two."))
    (should= {:menu-text  nil
              :tool-names #{"list_skills" "load_skill"}}
             (sut/read-skill-disclosure {:skill-menu-threshold 1} test-root (str test-root "/project")))))

(describe "behavior funnel"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (helper/with-memory-store
      (nexus/-with-nested-nexus {:root test-root :fs (fs/mem-fs)}
        (example))))

  (it "resolves locked and cascade fields for an existing session"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark" :effort 5 :history-retention :prune}
                           :crew      {crew-name {:model "spark" :soul crew-soul :context-mode :reset :compaction {:threshold 0.7}}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000 :effort 6 :compaction {:threshold 0.6}}}
                           :providers {"grover" {:api "grover" :effort 7 :compaction {:threshold 0.5}}}} "spec")
    (helper/create-session! test-root "s" {:crew crew-name :cwd "/tmp/locked" :history-retention :retain})
    (let [behavior (sut/resolve-behavior "s")]
      (should= crew-name (:crew behavior))
      (should= "/tmp/locked" (:cwd behavior))
      (should= :retain (:history-retention behavior))
      (should= :reset (:context-mode behavior))
      (should= 6 (:effort behavior))
      (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.7} (:compaction behavior)))
    (config/dangerously-install-config! nil "spec"))

  (it "creates a session with resolved locked defaults and explicit overrides"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark" :history-retention :prune}
                           :crew      {crew-name {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (sut/create-with-resolved-behavior! "s" {:effort 9})
    (let [session (helper/get-session test-root "s")]
      (should= crew-name (:crew session))
      (should= (str "/test/session-context/.isaac/crew/" crew-name) (:cwd session))
      (should= :prune (:history-retention session))
      (should= 9 (:effort session))
      (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.8} (:compaction session)))
    (config/dangerously-install-config! nil "spec"))

  (it "falls back to main when defaults.crew is absent"
    (config/dangerously-install-config! {:defaults  {:model "spark" :history-retention :prune}
                           :crew      {"main" {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (sut/create-with-resolved-behavior! "s" {})
    (let [session (helper/get-session test-root "s")]
      (should= "main" (:crew session))
      (should= (str test-root "/.isaac/crew/main") (:cwd session))
      (should= :prune (:history-retention session)))
    (config/dangerously-install-config! nil "spec"))

  (it "creates a session in an explicit session store"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark" :history-retention :prune}
                           :crew      {crew-name {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (let [explicit-store (store/create nil :memory)]
      (sut/create-with-resolved-behavior! "s" {:effort 9 :session-store explicit-store})
      (should-be-nil (helper/get-session test-root "s"))
      (let [session (store/get-session explicit-store "s")]
        (should= crew-name (:crew session))
        (should= (str "/test/session-context/.isaac/crew/" crew-name) (:cwd session))
        (should= :prune (:history-retention session))
        (should= 9 (:effort session))))
    (config/dangerously-install-config! nil "spec"))

  (it "creates sessions with stable distinct nonces"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark" :history-retention :prune}
                           :crew      {crew-name {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (sut/create-with-resolved-behavior! "s1" {})
    (sut/create-with-resolved-behavior! "s2" {})
    (let [session-1 (helper/get-session test-root "s1")
          session-2 (helper/get-session test-root "s2")]
      (should (:nonce session-1))
      (should (:nonce session-2))
      (should= (:nonce session-1) (:nonce (sut/resolve-behavior "s1")))
      (should (not= (:nonce session-1) (:nonce session-2))))
    (config/dangerously-install-config! nil "spec"))

  (it "resolves default compaction when crew and session omit compaction policy"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark"}
                           :crew      {crew-name {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 160}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (helper/create-session! test-root "no-config" {:crew crew-name})
    (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.8}
             (:compaction (sut/resolve-behavior "no-config")))
    (config/dangerously-install-config! nil "spec"))

  (it "keeps crew compaction policy when session only tracks consecutive failures"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark"}
                           :crew      {crew-name {:model "spark" :soul crew-soul
                                                  :compaction {:strategy :slinky :threshold 0.8 :head 0.4}}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 200}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (helper/create-session! test-root "tracked" {:crew crew-name})
    (store/update-session! (store/registered-store) "tracked" {:compaction {:consecutive-failures 2}})
    (should= {:async? false :strategy :slinky :head 0.4 :threshold 0.8}
             (:compaction (sut/resolve-behavior "tracked")))
    (config/dangerously-install-config! nil "spec"))

  (it "backfills a missing nonce for an existing session"
    (config/dangerously-install-config! {:defaults  {:crew crew-name :model "spark" :history-retention :prune}
                           :crew      {crew-name {:model "spark" :soul crew-soul}}
                           :models    {"spark" {:model "echo" :provider "grover" :context-window 1000}}
                           :providers {"grover" {:api "grover"}}} "spec")
    (helper/create-session! test-root "legacy" {:crew crew-name})
    (store/update-session! (store/registered-store) "legacy" {:nonce nil})
    (let [initial (helper/get-session test-root "legacy")]
      (should-be-nil (:nonce initial))
      (let [behavior (sut/resolve-behavior "legacy")
            updated  (helper/get-session test-root "legacy")]
        (should (:nonce behavior))
        (should= (:nonce behavior) (:nonce updated))))
    (config/dangerously-install-config! nil "spec")))
