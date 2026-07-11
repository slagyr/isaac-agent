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
    (sut/set-stub! (constantly {:exit 0 :out "hi" :err ""})))

  (it "returns assistant content from stubbed binary output"
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude-code"
                        {:command "claude"})]
      (should= "hi" (get-in res [:message :content]))))

  (it "invokes via Api protocol"
    (let [p   (sut/make "claude-code" {:command "claude"})
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
                       "claude-code"
                       {:command "claude"})
      (should= ["Hello" " " "world"] @chunks)))

  (it "streams deltas through stream-response"
    (let [out (str/join "\n"
                        (map #(json/generate-string
                                {:type "content_block_delta" :delta {:text %}})
                             ["Hello" " " "world"]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          p   (sut/make "claude-code" {:command "claude"})
          chunks (atom [])]
      (turn/stream-response! p {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                             (fn [piece] (swap! chunks conj piece)))
      (should= ["Hello" " " "world"] @chunks)))

  (it "forwards extra-args in argv"
    (let [_ (sut/clear-invocations!)
          _ (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                      "claude-code"
                      {:command "claude" :extra-args ["--foo" "bar"]})
          argv (:argv (first (sut/invocations)))]
      (let [idx (.indexOf argv "--foo")]
        (should= "bar" (nth argv (inc idx))))))

  (it "suppresses all tools with --tools \"\" and never emits the bad flags"
    (sut/clear-invocations!)
    (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
              "claude-code" {:command "claude"})
    (let [argv (:argv (first (sut/invocations)))
          idx  (.indexOf argv "--tools")]
      (should (<= 0 idx))
      (should= "" (nth argv (inc idx)))
      (should (neg? (.indexOf argv "--disallowed-tools")))
      (should (neg? (.indexOf argv "--max-turns")))))

  (it "classifies a login failure as auth-unavailable and reports it loudly"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "Not logged in · Please run /login"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude-code" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "Please run /login"))
      (should (:unavailable? res))
      (should= :auth (:reason res))))

  (it "reports a nonzero exit as a loud error without auth misclassification"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "claude: boom"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude-code" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "claude: boom"))
      (should-not (:unavailable? res)))))