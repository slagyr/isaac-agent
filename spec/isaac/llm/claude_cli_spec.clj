(ns isaac.llm.claude-cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.drive.turn :as turn]
    [isaac.llm.api.claude-cli :as sut]
    [isaac.llm.api.protocol :as api]
    [speclj.core :refer [before describe it should=]]))

(describe "claude-cli api"
  (before
    (sut/clear-stub!)
    (sut/clear-invocations!)
    (sut/set-stub! (constantly {:exit 0 :out "hi" :err ""})))

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
        (should= "bar" (nth argv (inc idx)))))))