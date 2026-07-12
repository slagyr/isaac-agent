(ns isaac.llm.claude-cli-real-spec
  "Spec-vs-reality smoke (isaac-kn7y / isaac-ozv9 / isaac-l70j): executes the ACTUAL claude
   binary. Stubs prove spec-conformance; only this proves spec-CORRECTNESS.

   Opt-in and self-skipping so normal CI never shells out or hits the network:
   runs only when ISAAC_CLAUDE_REAL is set AND the binary is installed AND a
   login is present; otherwise it reports `pending` (never a failure)."
  (:require
    [babashka.process :as process]
    [clojure.string :as str]
    [isaac.bridge.core :as bridge]
    [isaac.comm.null :as null-comm]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.llm.api.claude-cli :as sut]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as session-helper]
    [speclj.core :refer [describe it should should-not pending tags around]]))

(defn- enabled? []
  (not (str/blank? (System/getenv "ISAAC_CLAUDE_REAL"))))

(defn- claude-installed? []
  (try
    (zero? (:exit @(process/process ["claude" "--version"]
                                    {:out :string :err :string})))
    (catch Exception _ false)))

(defn- last-assistant-message [root session-key]
  (->> (session-helper/get-transcript root session-key)
       (filter #(= "message" (:type %)))
       (filter #(= "assistant" (get-in % [:message :role])))
       last
       :message))

(def ^:private exec-tool-def
  {:type     "function"
   :function {:name        "exec"
              :description "Run a shell command and return stdout"
              :parameters  {:type       "object"
                            :properties {:command {:type "string"}}
                            :required   ["command"]}}})

(describe "claude-cli real binary smoke (isaac-kn7y)"
  (tags :real :slow)

  (it "returns a real, non-empty answer from a logged-in claude binary"
    (cond
      (not (enabled?))
      (pending "set ISAAC_CLAUDE_REAL=1 on a logged-in box to run the @real smoke")

      (not (claude-installed?))
      (pending "claude binary not installed")

      :else
      (let [res (sut/chat {:model    "sonnet"
                           :messages [{:role "user" :content "Reply with exactly the word: pong"}]}
                          "claude"
                          {:command "claude"})]
        (if (:unavailable? res)
          (pending (str "no claude login present: " (:message res)))
          (do
            (should-not (:error res))
            (should (seq (str/trim (or (get-in res [:message :content]) "")))))))))

(describe "claude-cli real turn persists token usage on transcript (isaac-l70j)"
  (tags :real :slow)

  (around [example]
    (marigold.agent/with-real-manifest*
      (fn []
        (session-helper/with-memory-store
          (let [root (str (System/getProperty "java.io.tmpdir")
                          "/isaac-claude-real-"
                          (java.util.UUID/randomUUID))]
            (nexus/-with-nested-nexus {:root root :fs (fs/mem-fs)}
              (example)))))))

  (it "drives a real turn and stores nonzero usage on the assistant transcript entry"
    (cond
      (not (enabled?))
      (pending "set ISAAC_CLAUDE_REAL=1 on a logged-in box to run transcript @real smoke")

      (not (claude-installed?))
      (pending "claude binary not installed")

      :else
      (let [root        (nexus/get :root)
            session-key "claude-usage-real"
            cfg         {:defaults  {:crew "thinker" :model "sub-sonnet"}
                         :crew      {"thinker" {:model "sub-sonnet" :soul "You are a test harness."}}
                         :models    {"sub-sonnet" {:model "sonnet" :provider "claude" :context-window 200000}}
                         :providers {"claude" {:api "claude-cli" :command "claude"}}}]
        (config/dangerously-install-config! cfg "claude-cli real transcript smoke")
        (let [result (bridge/dispatch!
                       {:session-key session-key
                        :input       "Reply with exactly the word: pong"
                        :comm        null-comm/channel
                        :origin      {:kind :cli}
                        :config      cfg})]
          (cond
            (:unavailable? result)
            (pending (str "no claude login present: " (or (:message result) (:reason result))))

            (:error result)
            (throw (ex-info "real turn failed" result)))
          (let [assistant (last-assistant-message root session-key)
                usage     (:usage assistant)]
            (should (seq (str/trim (or (:content assistant) ""))))
            (should (pos? (or (:input-tokens usage) 0)))
            (should (pos? (or (:output-tokens usage) 0)))))))))

(describe "claude-cli witnessed tool roundtrip (isaac-ozv9)"
  (tags :real :slow)

  (it "executes exec and replies from the unguessable result, not a prediction"
    (cond
      (not (enabled?))
      (pending "set ISAAC_CLAUDE_REAL=1 on a logged-in box to run the witnessed roundtrip")

      (not (claude-installed?))
      (pending "claude binary not installed")

      :else
      (let [nonce     (str (java.util.UUID/randomUUID))
            command   (str "printf '%s'" nonce)
            request   {:model    "sonnet"
                       :messages [{:role "system" :content "You are a test harness."}
                                  {:role "user"
                                   :content (str "Use the exec tool once with command: "
                                                 command
                                                 ". After you receive the tool result, reply with ONLY that exact output — no other text.")}]
                       :tools    [exec-tool-def]}
            cfg       {:command "claude"}
            chat-fn   (fn [req] (sut/chat req "claude" cfg))
            followup  (fn [req resp tcs trs] (sut/followup-messages req resp tcs trs))
            executed? (atom false)
            tool-fn   (fn [name args]
                        (when (= "exec" name)
                          (reset! executed? true)
                          (str/trim (:out @(process/process ["sh" "-c" (:command args)]
                                                           {:out :string :err :string})))))
            probe     (sut/chat (select-keys request [:model :messages :tools])
                                "claude" cfg)
            result    (if (:unavailable? probe)
                        probe
                        (tool-loop/run chat-fn followup request tool-fn {:max-loops 4}))]
        (cond
          (:unavailable? result)
          (pending (str "no claude login present: " (:message result)))

          (:error result)
          (throw (ex-info "witnessed roundtrip failed" result))

          :else
          (do
            (should @executed?)
            (should (pos? (count (:tool-calls result))))
            (let [content (str/trim (or (get-in result [:response :message :content]) ""))]
              (should (str/includes? content nonce))
              (should-not (str/includes? content "isn't available")))))))))