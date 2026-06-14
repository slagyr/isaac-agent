(ns isaac.bridge-spec
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [isaac.charge :as charge]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.bridge.core :as bridge]
    [isaac.bridge.status :as bridge-status]
    [isaac.drive.turn :as single-turn]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.tool.registry :as tool-registry]
    [speclj.core :refer :all]))

(defn- slash-charge
  "Builds a prebuilt charge for slash-command tests — sidesteps charge/build
   resolution so tests can pass arbitrary slash-handler ctx fields directly."
  [root session-key input ctx]
  (assoc ctx :charge/type :charge :session-key session-key :input input :root root
             :session-store (store/registered-store)))

(defn- delete-dir! [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

(defn- write-file! [path content]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path content)))

(def ^:dynamic *root* nil)

(defn- with-bridge-session [state-suffix session-key example]
  (helper/with-memory-store
    (let [root (str (System/getProperty "user.dir") "/target/test-state/" state-suffix "-" (random-uuid))]
      (delete-dir! root)
      (helper/create-session! root session-key)
      (binding [*root* root]
        (example)))))

(describe "bridge"
  (context "status-data"
    (around [it] (with-bridge-session "bridge-spec" "testuser" it))

    (it "includes crew, model, provider from context"
      (let [ctx {:crew "main" :agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= "main" (:crew data))
        (should= "echo" (:model data))
        (should= "grover" (:provider data))))

    (it "includes context-window from context"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= 32768 (:context-window data))))

    (it "includes session-key"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= "testuser" (:session-key data))))

    (it "includes session-file from storage"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should-not-be-nil (:session-file data))
        (should (re-matches #"[a-z0-9-]+\.jsonl" (:session-file data)))))

    (it "counts zero turns on a fresh session"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= 0 (:turns data))))

    (it "counts turns from transcript messages"
      (helper/append-message! *root* "testuser" {:role "user" :content "hello"})
      (helper/append-message! *root* "testuser" {:role "assistant" :content "hi"})
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= 2 (:turns data))))

    (it "includes compaction count from storage"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= 0 (:compactions data))))

    (it "includes tokens from storage"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should (number? (:tokens data)))))

    (it "uses last-input-tokens instead of cumulative total-tokens"
      (helper/update-session! *root* "testuser" {:total-tokens 1000000 :last-input-tokens 5000})
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= 5000 (:tokens data))))

    (it "computes context-pct as percentage of tokens over context-window"
      (helper/update-tokens! *root* "testuser" {:input-tokens 3277 :output-tokens 0})
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should (> (:context-pct data) 0))))

    (it "includes cwd"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should-not-be-nil (:cwd data))))

    (it "prefers the session cwd over the process working directory"
      (helper/update-session! *root* "testuser" {:cwd "/tmp/isaac-cwd-fixture"})
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            data (bridge-status/status-data *root* "testuser" ctx)]
        (should= "/tmp/isaac-cwd-fixture" (:cwd data))))

    (it "includes tool-count from registry"
      (nexus/-with-nexus {:root *root*}
        (helper/with-memory-store
          (tool-registry/clear!)
          (tool-registry/register! {:name "bash" :description "Run bash" :handler identity})
          (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
                data (bridge-status/status-data "testuser" ctx)]
            (should= 1 (:tool-count data))))))
    )

  (context "format-status"
    (it "formats status map as a markdown table"
      (let [data {:boot-files "Micah's AI assistant management tools. This project uses toolbox to manage agent components."
                  :crew "main" :agent "main" :model "echo" :provider "grover" :context-window 32768
                  :session-key "testuser" :session-file "abc12345.jsonl"
                  :soul "You are Isaac." :turns 2 :compactions 2 :tokens 5000 :context-pct 15 :tool-count 1
                  :cwd "/tmp/test"}
            output (bridge-status/format-status data)]
        (should (re-find #"```text" output))
        (should (re-find #"Session Status" output))
        (should (re-find #"Crew\s+main" output))
        (should (re-find #"─+" output))
        (should (re-find #"Model\s+echo \(grover\)" output))
        (should (re-find #"Compactions\s+2" output))
        (should (re-find #"Context\s+5,000 / 32,768 \(15%\)" output))
        (should (re-find #"Soul\s+\"Micah's AI assistant management tools\. This project uses ...\"" output))
        (should (re-find #"```$" output))
        (should-not (re-find #"You are Isaac\." output))))
    )

  (context "slash-command?"
    (it "returns true for /status"
      (should (bridge/slash-command? "/status")))

    (it "returns true for any slash-prefixed input"
      (should (bridge/slash-command? "/help")))

    (it "returns false for normal input"
      (should-not (bridge/slash-command? "hello world")))

    (it "returns false for blank input"
      (should-not (bridge/slash-command? "")))

    (it "returns false for nil"
      (should-not (bridge/slash-command? nil)))
    )

  (context "dispatch"
    (around [it] (with-bridge-session "bridge-dispatch-spec" "testuser" it))

    (it "returns command type for /status"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            result (bridge/dispatch! (slash-charge *root* "testuser" "/status" ctx))]
        (should= :command (:type result))
        (should= :status (:command result))
        (should-not-be-nil (:data result))))

    (it "includes status data in command result"
      (let [ctx {:crew "main" :agent "main" :model "echo" :provider "grover" :context-window 32768}
            result (bridge/dispatch! (slash-charge *root* "testuser" "/status" ctx))]
        (should= "main" (get-in result [:data :crew]))
        (should= "echo" (get-in result [:data :model]))))

    (it "returns unknown command error for unrecognized slash commands"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            result (bridge/dispatch! (slash-charge *root* "testuser" "/unknown" ctx))]
        (should= :command (:type result))
        (should= :unknown (:command result))))

    (it "rejects an unknown interactive slash command without running a turn"
      (let [called? (atom false)
            ctx     {:agent "main" :model "echo" :origin {:kind :cli}}]
        (with-redefs [single-turn/run-turn! (fn [_]
                                              (reset! called? true)
                                              {:content "should not run"})]
          (let [result (bridge/dispatch! (slash-charge *root* "testuser" "/prune orchid" ctx))]
            (should= :command (:type result))
            (should= :unknown (:command result))
            (should= "unknown command: prune" (:message result))))
        (should-not @called?)))

    (it "routes an unknown autonomous slash command through run-turn!"
      (let [captured (atom nil)
            ctx      {:agent "main" :model "echo" :origin {:kind :hail}}]
        (with-redefs [single-turn/run-turn! (fn [charge*]
                                              (reset! captured charge*)
                                              {:content "delivered"})]
          (bridge/dispatch! (slash-charge *root* "testuser" "/prune orchid" ctx)))
        (should= "/prune orchid" (:input @captured))))

    (it "expands configured prompt-template commands before running the turn"
      (write-file! (str *root* "/config/commands/tend.md")
                   (str "---\n"
                        "type: command\n"
                        "params: [specimen]\n"
                        "---\n\n"
                        "Tend the {{specimen}} in the greenhouse."))
      (let [captured (atom nil)
            ctx      {:config {:root *root*}}]
        (with-redefs [single-turn/run-turn! (fn [charge*]
                                              (reset! captured charge*)
                                              {:content "done"})]
          (bridge/dispatch! (slash-charge *root* "testuser" "/tend dilithium-orchid" ctx)))
        (should= "Tend the dilithium-orchid in the greenhouse." (:input @captured))))

    (it "inlines declared skill bodies into expanded prompt-template commands"
      (write-file! (str *root* "/config/skills/greenhouse-protocol/SKILL.md")
                   (str "---\n"
                        "type: skill\n"
                        "---\n\n"
                        "Always quarantine new specimens for one cycle before integration."))
      (write-file! (str *root* "/config/commands/tend.md")
                   (str "---\n"
                        "type: command\n"
                        "params: [specimen]\n"
                        "skills: [greenhouse-protocol]\n"
                        "---\n\n"
                        "Tend the {{specimen}} in the greenhouse."))
      (let [captured (atom nil)
            ctx      {:config {:root *root*}}]
        (with-redefs [single-turn/run-turn! (fn [charge*]
                                              (reset! captured charge*)
                                              {:content "done"})]
          (bridge/dispatch! (slash-charge *root* "testuser" "/tend dilithium-orchid" ctx)))
        (should (str/includes? (:input @captured) "Tend the dilithium-orchid in the greenhouse."))
        (should (str/includes? (:input @captured) "Always quarantine new specimens for one cycle before integration."))))

    (it "routes non-slash input through run-turn!"
      (let [ctx {:agent "main" :model "echo" :provider "grover" :context-window 32768}
            called (atom nil)]
        (with-redefs [single-turn/run-turn! (fn [charge*]
                                              (reset! called {:input (:input charge*)})
                                              {:content "hi"})]
          (bridge/dispatch! (slash-charge *root* "testuser" "hello" ctx)))
        (should= "hello" (:input @called))))

    (it "marks a session in flight while a turn is running and clears it afterward"
      (let [session-store (store/registered-store)
            started       (promise)
            release       (promise)]
        (with-redefs [single-turn/run-turn! (fn [_]
                                              (deliver started true)
                                              @release
                                              {:content "done"})]
          (let [dispatch-future (future (bridge/dispatch! {:charge/type    :charge
                                                           :session-key    "testuser"
                                                           :input          "hello"
                                                           :session-store  session-store
                                                           :comm           nil}))]
            @started
            (should= true (store/in-flight? session-store "testuser"))
            (deliver release true)
            @dispatch-future
            (should= false (store/in-flight? session-store "testuser"))))))

    (it "refuses dispatch when the session is already in flight"
      (let [session-store (store/registered-store)]
        (store/mark-in-flight! session-store "testuser")
        (log/capture-logs
          (let [result (bridge/dispatch! {:charge/type   :charge
                                          :session-key   "testuser"
                                          :input         "hello"
                                          :session-store session-store
                                          :comm          nil})
                entry  (last @log/captured-logs)]
            (should= {:dispatched? false :reason :session-in-flight} result)
            (should= :dispatch/refused (:event entry))
            (should= "testuser" (:session entry))))))
    )

  (context "dispatch - /model command"
    (around [it] (with-bridge-session "bridge-model-spec" "model-test" it))

    (it "shows the current model when no argument is given"
      (let [ctx {:model "echo" :provider "grover" :context-window 32768
                  :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}}
            result (bridge/dispatch! (slash-charge *root* "model-test" "/model" ctx))]
        (should= :command (:type result))
        (should= :model (:command result))
        (should= "grover (grover/echo) is the current model" (:message result))))

    (it "switches model and returns confirmation message"
      (let [alt-model "starcore-7-fast"
            ctx       {:model "echo" :provider "grover" :context-window 32768
                       :models {"grover"          {:alias "grover"          :model "echo"      :provider "grover"          :context-window 32768}
                                marigold/starcore {:alias marigold/starcore :model alt-model :provider marigold/starcore :context-window 32768}}}
            result    (bridge/dispatch! (slash-charge *root* "model-test" (str "/model " marigold/starcore) ctx))]
        (should= :command (:type result))
        (should= :model (:command result))
        (should= (str "switched model to " marigold/starcore " (" marigold/starcore "/" alt-model ")") (:message result))))

    (it "persists the alias in the session :model field, leaves :provider unset"
      (let [ctx {:model "echo" :provider "grover" :context-window 32768
                 :models {"grover"          {:alias "grover"          :model "echo"             :provider "grover"          :context-window 32768}
                          marigold/starcore {:alias marigold/starcore :model "starcore-7-fast" :provider marigold/starcore :context-window 32768}}}]
        (bridge/dispatch! (slash-charge *root* "model-test" (str "/model " marigold/starcore) ctx))
        (let [session (helper/get-session *root* "model-test")]
          (should= marigold/starcore (:model session))
          (should-be-nil (:provider session)))))

    (it "returns an error for an unknown model alias"
      (let [ctx {:model "echo" :provider "grover" :context-window 32768
                  :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}}
            result (bridge/dispatch! (slash-charge *root* "model-test" "/model nonexistent" ctx))]
        (should= :command (:type result))
        (should= :unknown (:command result))
        (should= "unknown model: nonexistent" (:message result))))
    )

  (context "dispatch - /crew command"
    (around [it] (with-bridge-session "bridge-crew-spec" "crew-test" it))

    (it "shows the current crew when no argument is given"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}
            result (bridge/dispatch! (slash-charge *root* "crew-test" "/crew" ctx))]
        (should= :command (:type result))
        (should= :crew (:command result))
        (should= "main is the current crew member" (:message result))))

    (it "switches crew and returns confirmation message"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}
            result (bridge/dispatch! (slash-charge *root* "crew-test" "/crew ketch" ctx))]
        (should= :command (:type result))
        (should= :crew (:command result))
        (should= "switched crew to ketch" (:message result))))

    (it "logs crew changes when switching to a known crew"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}]
        (log/capture-logs
          (bridge/dispatch! (slash-charge *root* "crew-test" "/crew ketch" ctx))
          (let [entry (last @log/captured-logs)]
            (should= :session/crew-changed (:event entry))
            (should= "crew-test" (:session entry))
            (should= "main" (:from entry))
            (should= "ketch" (:to entry))))))

    (it "persists the switched crew in the session"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}]
        (bridge/dispatch! (slash-charge *root* "crew-test" "/crew ketch" ctx))
        (let [session (helper/get-session *root* "crew-test")]
          (should= "ketch" (:crew session))
          (should-not (contains? session :agent))
          (should= nil (:model session))
          (should= nil (:provider session)))))

    (it "keeps locked session fields like cwd when switching crews"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}]
        (helper/update-session! *root* "crew-test" {:cwd "/tmp/work" :model "parrot" :provider "grover"})
        (bridge/dispatch! (slash-charge *root* "crew-test" "/crew ketch" ctx))
        (let [session (helper/get-session *root* "crew-test")]
          (should= "ketch" (:crew session))
          (should= "/tmp/work" (:cwd session))
          (should= nil (:model session))
          (should= nil (:provider session)))))

    (it "returns an error for an unknown crew name"
      (let [ctx {:crew "main" :crew-members {"main" {} "ketch" {}}}
            result (bridge/dispatch! (slash-charge *root* "crew-test" "/crew nonexistent" ctx))]
        (should= :command (:type result))
        (should= :unknown (:command result))
        (should= "unknown crew: nonexistent" (:message result))))
    )

  (context "cancellation"
    (it "cancels an active turn and runs cancel hooks"
      (let [called? (atom false)
            turn (bridge-cancel/begin-turn! "cancel-test")]
        (bridge-cancel/on-cancel! "cancel-test" #(reset! called? true))
        (bridge-cancel/cancel! "cancel-test")
        (should @called?)
        (should (bridge-cancel/cancelled? "cancel-test"))
        (bridge-cancel/end-turn! "cancel-test" turn)))

    (it "applies a pending cancel to the next turn"
      (bridge-cancel/cancel! "cancel-later")
      (let [turn (bridge-cancel/begin-turn! "cancel-later")]
        (should (bridge-cancel/cancelled? "cancel-later"))
        (bridge-cancel/end-turn! "cancel-later" turn)
        (should-not (bridge-cancel/cancelled? "cancel-later"))))
    )

  (context "dispatch!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (helper/with-memory-store
        (let [dir (str (System/getProperty "user.dir") "/target/test-state/bridge-dispatch-bang-" (random-uuid))]
          (delete-dir! dir)
          (nexus/-with-nested-nexus {:root dir :fs (fs/mem-fs)}
            (example)))))

    (it "uses the stored session crew when no override is provided"
      (let [captured (atom nil)
            cfg      {:defaults  {:crew "main" :model "fast"}
                      :crew      {"main"  {:soul "Main soul"  :model "fast"}
                                  "pinky" {:soul "Pinky soul" :model "smart"}}
                      :models    {"fast"  {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}
                                  "smart" {:model "anvil-x"      :provider marigold/quantum-anvil :context-window 128000}}
                      :providers {marigold/quantum-anvil {:api marigold/anvil-api}}}]
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "pinky-session" {:crew "pinky"})
        (with-redefs [single-turn/run-turn! (fn [charge]
                                              (reset! captured charge)
                                              {:message {:role "assistant" :content "ok"}})]
          (bridge/dispatch! (charge/build {:session-key "pinky-session" :input "hello" :root (nexus/get :root)})))
        (should= "anvil-x" (:model @captured))
        (should= "Pinky soul" (:soul @captured))))

    (it "lets an explicit crew override win over the stored session crew"
      (let [captured (atom nil)
            cfg      {:defaults  {:crew "main" :model "fast"}
                      :crew      {"main"  {:soul "Main soul"  :model "fast"}
                                  "pinky" {:soul "Pinky soul" :model "smart"}}
                      :models    {"fast"  {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}
                                  "smart" {:model "anvil-x"      :provider marigold/quantum-anvil :context-window 128000}}
                      :providers {marigold/quantum-anvil {:api marigold/anvil-api}}}]
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "pinky-session" {:crew "pinky"})
        (with-redefs [single-turn/run-turn! (fn [charge]
                                              (reset! captured charge)
                                              {:message {:role "assistant" :content "ok"}})]
          (bridge/dispatch! (charge/build {:session-key "pinky-session" :input "hello" :crew "main" :root (nexus/get :root)})))
        (should= "anvil-x-mini" (:model @captured))
        (should= "Main soul" (:soul @captured))))

    (it "includes --crew hint in unknown-crew message for cli origin"
      (let [cfg {:defaults {:crew "main" :model "fast"}
                 :crew     {"main" {:soul "Main soul" :model "fast"}}
                 :models   {"fast" {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}}
                 :providers {marigold/quantum-anvil {:api marigold/anvil-api}}}]
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "cli-origin-unknown-crew" {:crew "ghost"})
        (let [result (bridge/dispatch! (charge/build {:session-key "cli-origin-unknown-crew"
                                                      :input       "hello"
                                                      :origin      {:kind :cli}
                                                      :root   (nexus/get :root)}))]
          (should= :unknown-crew (:error result))
          (should (re-find #"pass --crew to override" (:message result))))))

    (it "omits all hints in unknown-crew message for webhook origin"
      (let [cfg {:defaults {:crew "main" :model "fast"}
                 :crew     {"main" {:soul "Main soul" :model "fast"}}
                 :models   {"fast" {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}}
                 :providers {marigold/quantum-anvil {:api marigold/anvil-api}}}]
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "webhook-unknown-crew" {:crew "ghost"})
        (let [result (bridge/dispatch! (charge/build {:session-key "webhook-unknown-crew"
                                                      :input       "hello"
                                                      :root   (nexus/get :root)
                                                      :origin      {:kind :webhook}}))]
          (should= :unknown-crew (:error result))
          (should-not (re-find #"pass --crew to override" (:message result)))
          (should-not (re-find #"/crew" (:message result))))))

    (it "routes a prebuilt unresolved charge directly and includes --crew hint for cli origin"
      (let [c {:charge/type      :charge
               :charge/unresolved true
               :charge/reason    :unknown-crew
               :session-key      "prebuilt-cli-origin"
               :input            "hello"
               :crew             "ghost"
               :origin           {:kind :cli}}
            result (bridge/dispatch! c)]
        (should= :unknown-crew (:error result))
        (should (re-find #"pass --crew to override" (:message result)))))

    (it "routes a prebuilt unresolved charge directly and shows /crew hint for acp origin"
      (let [c {:charge/type      :charge
               :charge/unresolved true
               :charge/reason    :unknown-crew
               :session-key      "prebuilt-acp-origin"
               :input            "hello"
               :crew             "ghost"
               :origin           {:kind :acp}}
            result (bridge/dispatch! c)]
        (should= :unknown-crew (:error result))
        (should-not (re-find #"pass --crew to override" (:message result)))
        (should (re-find #"/crew" (:message result))))))

  (context "session cwd cascade"
    (it "explicit override beats crew and channel default"
      (should= "/explicit/path"
               (bridge/resolve-session-cwd "/explicit/path" {:cwd "/crew/path"} "/channel/default")))

    (it "crew cwd beats channel default when no explicit override"
      (should= "/crew/path"
               (bridge/resolve-session-cwd nil {:cwd "/crew/path"} "/channel/default")))

    (it "channel default is used when neither explicit nor crew cwd is set"
      (should= "/channel/default"
               (bridge/resolve-session-cwd nil {} "/channel/default"))))
  )
