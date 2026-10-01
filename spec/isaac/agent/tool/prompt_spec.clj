;; mutation-tested: pending
(ns isaac.agent.tool.prompt-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.tool.prompt :as sut]
    [speclj.core :refer :all]))

(describe "prompt tools"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (helper/with-memory-store
        (example))))

  (defn- write-prompt! [path content]
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path content)))

  (it "loads a discovered skill body for the calling session"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Always quarantine new specimens for one cycle."))
    (should= {:result "Always quarantine new specimens for one cycle."}
             (sut/load-prompt-tool {"session_key" "work-sess"
                                    "name"        "greenhouse-protocol"})))

  (it "loads a command together with its declared skills"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Always quarantine new specimens for one cycle before integration."))
    (write-prompt! "/test/isaac/prompts/commands/inspect.md"
                   (str "---\n"
                        "description: Inspect the greenhouse\n"
                        "skills: [greenhouse-protocol]\n"
                        "---\n\n"
                        "Walk every bay and log what you find."))
    (should= {:result (str "Walk every bay and log what you find.\n\n"
                           "Always quarantine new specimens for one cycle before integration.")}
             (sut/load-prompt-tool {"session_key" "work-sess"
                                    "name"        "inspect"})))

  (it "uses kind to pick between a skill and a command that share a name"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Always quarantine new specimens for one cycle."))
    (write-prompt! "/test/isaac/prompts/commands/greenhouse-protocol.md"
                   (str "---\n"
                        "description: Run the protocol as a task\n"
                        "---\n\n"
                        "Run the full greenhouse protocol now."))
    (should= {:result "Run the full greenhouse protocol now."}
             (sut/load-prompt-tool {"session_key" "work-sess"
                                    "name"        "greenhouse-protocol"
                                    "kind"        "command"})))

  (it "lists discovered skills and commands with kind in stable sorted order"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Always quarantine new specimens for one cycle."))
    (write-prompt! "/test/isaac/prompts/commands/inspect.md"
                   (str "---\n"
                        "description: Inspect the greenhouse\n"
                        "---\n\n"
                        "Walk every bay and log what you find."))
    (should= {:result (str "- greenhouse-protocol (skill): Use when tending specimens\n"
                           "- inspect (command): Inspect the greenhouse")}
             (sut/list-prompts-tool {"session_key" "work-sess"})))

  (it "errors when the requested prompt does not exist"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (let [result (sut/load-prompt-tool {"session_key" "work-sess"
                                        "name"        "missing"})]
      (should (:isError result))
      (should= "unknown prompt: missing" (:error result))))

  (it "loads a bundled resource from a directory-packaged skill"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Follow checklist.md."))
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/checklist.md"
                   "1. Check soil moisture.\n2. Quarantine new specimens.")
    (should= {:result "1. Check soil moisture.\n2. Quarantine new specimens."}
             (sut/load-prompt-tool {"session_key" "work-sess"
                                    "name"        "greenhouse-protocol"
                                    "resource"    "checklist.md"})))

  (it "rejects a resource path that escapes the skill directory"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Follow checklist.md."))
    (let [result (sut/load-prompt-tool {"session_key" "work-sess"
                                        "name"        "greenhouse-protocol"
                                        "resource"    "../../auth.json"})]
      (should (:isError result))
      (should= "resource path escapes the skill directory: ../../auth.json" (:error result))))

  (it "returns not found when the bundled resource does not exist"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main" :cwd "/workspace/project"})
    (write-prompt! "/test/isaac/prompts/skills/greenhouse-protocol/SKILL.md"
                   (str "---\n"
                        "type: skill\n"
                        "description: Use when tending specimens\n"
                        "---\n\n"
                        "Follow checklist.md."))
    (let [result (sut/load-prompt-tool {"session_key" "work-sess"
                                        "name"        "greenhouse-protocol"
                                        "resource"    "missing.md"})]
      (should (:isError result))
      (should= "skill resource not found: greenhouse-protocol/missing.md" (:error result)))))
