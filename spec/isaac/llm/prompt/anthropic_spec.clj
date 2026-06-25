(ns isaac.llm.prompt.anthropic-spec
  (:require
    [isaac.llm.api.messages :as sut]
    [speclj.core :refer :all]))

(def sample-transcript
  [{:type "session" :id "sess-1" :timestamp 1000}
   {:type "message" :id "m1" :parentId "sess-1" :timestamp 2000
    :message {:role "user" :content "Hello"}}
   {:type "message" :id "m2" :parentId "m1" :timestamp 3000
    :message {:role "assistant" :content "Hi there" :model "qwen3-coder:30b" :provider "ollama"}}
   {:type "message" :id "m3" :parentId "m2" :timestamp 4000
    :message {:role "user" :content "How are you?"}}])

(describe "Anthropic Prompt Builder"

  (context "build"

    (it "includes model"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})]
        (should= "claude-sonnet-4-6" (:model p))))

    (it "includes max_tokens"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})]
        (should= 16000 (:max_tokens p))))

    (it "puts soul in system as content block with cache_control"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})]
        (should= "text" (get-in p [:system 0 :type]))
        (should= "You are Isaac." (get-in p [:system 0 :text]))
        (should= "ephemeral" (get-in p [:system 0 :cache_control :type]))))

    (it "extracts messages without system role"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})]
        (should= 3 (count (:messages p)))
        (should (every? #(not= "system" (:role %)) (:messages p)))))

    (it "strips extra fields from messages"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})
            assistant-msg (second (:messages p))]
        (should= #{"role" "content"} (set (map name (keys assistant-msg))))))

    (it "applies cache breakpoint to penultimate user message"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})
            first-user (first (:messages p))]
        ;; First user message is the penultimate user message (2 user messages total)
        (should (vector? (:content first-user)))
        (should= "ephemeral" (get-in first-user [:content 0 :cache_control :type])))))

  (context "tools"

    (it "omits tools key when no tools provided"
      (let [p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript})]
        (should-not-contain :tools p)))

    (it "includes tools when provided"
      (let [tools [{:name "read_file" :description "Read a file" :parameters {:type "object"}}]
            p (sut/build {:model "claude-sonnet-4-6" :soul "You are Isaac." :transcript sample-transcript :tools tools})]
        (should= 1 (count (:tools p)))
        (should= "read_file" (:name (first (:tools p)))))))

  )
