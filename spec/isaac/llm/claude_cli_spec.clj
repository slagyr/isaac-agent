(ns isaac.llm.claude-cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.drive.turn :as turn]
    [isaac.llm.api.claude-cli :as sut]
    [isaac.llm.api.protocol :as api]
    [speclj.core :refer [before describe it should should= should-not]]))

(describe "claude-cli api"
  (before
    (sut/clear-stub!)
    (sut/clear-invocations!)
    (sut/set-stub! (constantly {:exit 0
                                :out  (json/generate-string {:type   "result"
                                                             :result "hi"
                                                             :usage  {:input_tokens 1 :output_tokens 1}})
                                :err  ""})))

  (it "returns assistant content from stubbed binary output"
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude"
                        {:command "claude"})]
      (should= "hi" (get-in res [:message :content]))))

  (it "invokes via Api protocol"
    (let [p   (sut/make "claude" {:command "claude"})
          res (api/chat p {:model "sonnet" :messages [{:role "user" :content "yo"}]})]
      (should= "hi" (get-in res [:message :content]))))

  (it "streams NDJSON deltas"
    (let [out (str/join "\n"
                        (map #(json/generate-string
                                {:type "content_block_delta" :delta {:text %}})
                             ["Hello" " " "world"]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          chunks (atom [])]
      (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                       (fn [chunk]
                         (when-let [piece (get-in chunk [:message :content])]
                           (swap! chunks conj piece)))
                       "claude"
                       {:command "claude"})
      (should= ["Hello" " " "world"] @chunks)))

  (it "streams deltas through stream-response"
    (let [out (str/join "\n"
                        (map #(json/generate-string
                                {:type "content_block_delta" :delta {:text %}})
                             ["Hello" " " "world"]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          p   (sut/make "claude" {:command "claude"})
          chunks (atom [])]
      (turn/stream-response! p {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                             (fn [piece] (swap! chunks conj piece)))
      (should= ["Hello" " " "world"] @chunks)))

  (it "forwards extra-args in argv"
    (let [_ (sut/clear-invocations!)
          _ (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                      "claude"
                      {:command "claude" :extra-args ["--foo" "bar"]})
          argv (:argv (first (sut/invocations)))]
      (let [idx (.indexOf argv "--foo")]
        (should= "bar" (nth argv (inc idx))))))

  (it "passes soul text via --system-prompt, not the conversation prompt"
    (sut/clear-invocations!)
    (sut/chat {:model    "sonnet"
               :messages [{:role "system" :content "Be wise."}
                          {:role "user" :content "yo"}]}
              "claude" {:command "claude"})
    (let [argv   (:argv (first (sut/invocations)))
          idx    (.indexOf argv "--system-prompt")
          system (when (<= 0 idx) (nth argv (inc idx)))
          prompt (last argv)]
      (should (str/includes? system "Be wise."))
      (should-not (str/includes? prompt "Be wise."))
      (should= "User: yo" prompt)))

  (it "puts the tool protocol contract in --system-prompt when tools are present"
    (sut/clear-invocations!)
    (sut/chat {:model    "sonnet"
               :messages [{:role "system" :content "Be wise."}
                          {:role "user" :content "run it"}]
               :tools    [{:type "function" :function {:name "exec"}}]}
              "claude" {:command "claude"})
    (let [argv   (:argv (first (sut/invocations)))
          idx    (.indexOf argv "--system-prompt")
          system (when (<= 0 idx) (nth argv (inc idx)))
          prompt (last argv)]
      (should (str/includes? system sut/tool-protocol-contract))
      (should (str/includes? system "## Tools"))
      (should-not (str/includes? prompt sut/tool-protocol-contract))
      (should-not (str/includes? prompt "## Tools"))))

  (it "suppresses all tools with --tools \"\" and never emits the bad flags"
    (sut/clear-invocations!)
    (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
              "claude" {:command "claude"})
    (let [argv (:argv (first (sut/invocations)))
          idx  (.indexOf argv "--tools")]
      (should (<= 0 idx))
      (should= "" (nth argv (inc idx)))
      (should (neg? (.indexOf argv "--disallowed-tools")))
      (should (neg? (.indexOf argv "--max-turns")))))

  (it "classifies a login failure as auth-unavailable and reports it loudly"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "Not logged in · Please run /login"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "Please run /login"))
      (should (:unavailable? res))
      (should= :auth (:reason res))))

  (it "reports a nonzero exit as a loud error without auth misclassification"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "claude: boom"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "claude: boom"))
      (should-not (:unavailable? res))))

  (it "parses json result text and usage"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type   "result"
                                                :result "answer"
                                                :usage  {:input_tokens  50
                                                         :output_tokens 9
                                                         :cache_read_input_tokens      2
                                                         :cache_creation_input_tokens 1}})
                   :err  ""}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude" {:command "claude"})]
      (should= "answer" (get-in res [:message :content]))
      (should= 50 (:input-tokens (:usage res)))
      (should= 9 (:output-tokens (:usage res)))
      (should= 2 (:cache-read (:usage res)))
      (should= 1 (:cache-write (:usage res)))))

  (it "degrades to zero usage when json omits usage"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type "result" :result "ok"})
                   :err  ""}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude" {:command "claude"})]
      (should= "ok" (get-in res [:message :content]))
      (should= 0 (:input-tokens (:usage res)))
      (should= 0 (:output-tokens (:usage res)))))

  (it "uses stream-json terminal result usage"
    (let [out (str/join "\n"
                        [(json/generate-string {:type "content_block_delta" :delta {:text "Hi"}})
                         (json/generate-string {:type   "result"
                                                :result "Hi"
                                                :usage  {:input_tokens 10 :output_tokens 2}})])
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                               (fn [_]) "claude" {:command "claude"})]
      (should= "Hi" (get-in res [:message :content]))
      (should= 10 (:input-tokens (:usage res)))
      (should= 2 (:output-tokens (:usage res))))))