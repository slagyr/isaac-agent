(ns isaac.llm.grover-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.bridge.cancellation :as bridge]
    [isaac.llm.api.grover :as sut]
    [isaac.llm.api.protocol :as api]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(describe "Grover"

  (before (sut/reset-queue!))

  ;; region ----- Echo Mode -----

  (describe "echo mode"

    (it "echoes the last user message"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "Hello Grover"}]}
                           "grover" {})]
        (should= "Hello Grover" (get-in resp [:message :content]))))

    (it "echoes the last user message when multiple"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "First"}
                                       {:role "assistant" :content "Reply"}
                                       {:role "user" :content "Second"}]}
                           "grover" {})]
        (should= "Second" (get-in resp [:message :content]))))

    (it "returns '...' when no user messages"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "system" :content "You are helpful"}]}
                           "grover" {})]
        (should= "..." (get-in resp [:message :content]))))

    (it "returns the model from the request"
      (let [resp (sut/chat {:model    "test-model"
                            :messages [{:role "user" :content "Hi"}]}
                           "grover" {})]
        (should= "test-model" (:model resp))))

    (it "returns a context length error when the prompt exceeds the configured context window"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content (apply str (repeat 240 "x"))}]}
                           "grover"
                           {:context-window 20 :enforce-context-window true})]
        (should= :llm-error (:error resp))
        (should= "context length exceeded" (:message resp))))

    (it "returns cancelled when a delayed response is interrupted"
      (let [turn   (bridge/begin-turn! "grover-cancel")
             _      (sut/enable-delay!)
             result (future (sut/chat {:model    "echo"
                                      :messages [{:role "user" :content "Hi"}]}
                                     "grover"
                                     {:session-key "grover-cancel"}))]
        (sut/await-delay-start)
        (bridge/cancel! "grover-cancel")
        (sut/release-delay!)
        (should= :cancelled (:error @result))
        (bridge/end-turn! "grover-cancel" turn)))

    (it "includes token counts"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "Hi"}]}
                           "grover" {})]
        (should= 25 (:prompt_eval_count resp))
        (should= 12 (:eval_count resp))))

    (it "marks response as done"
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "Hi"}]}
                           "grover" {})]
        (should (:done resp))
        (should= "stop" (:done_reason resp)))))

  ;; endregion ^^^^^ Echo Mode ^^^^^

  ;; region ----- Scripted Mode -----

  (describe "scripted mode"

    (it "returns queued content response"
      (sut/enqueue! [{:content "Scripted answer"}])
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "Ignored"}]}
                           "grover" {})]
        (should= "Scripted answer" (get-in resp [:message :content]))))

    (it "consumes queue in order"
      (sut/enqueue! [{:content "First"} {:content "Second"}])
      (should= "First" (get-in (sut/chat {:model "echo" :messages []} "grover" {}) [:message :content]))
      (should= "Second" (get-in (sut/chat {:model "echo" :messages []} "grover" {}) [:message :content])))

    (it "falls back to echo when queue is empty"
      (sut/enqueue! [{:content "Only one"}])
      (sut/chat {:model "echo" :messages []} "grover" {})
      (let [resp (sut/chat {:model    "echo"
                            :messages [{:role "user" :content "Echo me"}]}
                           "grover" {})]
        (should= "Echo me" (get-in resp [:message :content]))))

    (it "throws exception for exception type"
      (sut/enqueue! [{:type "exception" :content "something broke"}])
      (should-throw Exception "something broke"
        (sut/chat {:model "echo" :messages [{:role "user" :content "boom"}]} "grover" {})))

    (it "returns scripted tool call"
      (sut/enqueue! [{:tool_call "read_file" :arguments {:path "README"}}])
      (let [resp (sut/chat {:model "echo" :messages [{:role "user" :content "Read it"}]} "grover" {})]
        (should= "read_file" (get-in resp [:message :tool_calls 0 :function :name]))
        (should= {:path "README"} (get-in resp [:message :tool_calls 0 :function :arguments]))
        (should= "" (get-in resp [:message :content]))))

    (it "waits for release when a scripted response is marked wait"
      (sut/enqueue! [{:type "text" :content "Scripted answer" :wait true}])
      (let [response (future (sut/chat {:model "echo" :messages [{:role "user" :content "Ignored"}]}
                                       "grover"
                                       {:session-key "wait-session"}))]
        (helper/await-condition #(sut/waiting? "wait-session"))
        (sut/release-wait! "wait-session")
        (should= "Scripted answer" (get-in @response [:message :content])))))

  ;; endregion ^^^^^ Scripted Mode ^^^^^

  ;; region ----- Streaming -----

  (describe "chat-stream"

    (it "calls on-chunk for each word"
      (let [chunks (atom [])
            resp   (sut/chat-stream
                     {:model "echo" :messages [{:role "user" :content "Hello world"}]}
                     (fn [c] (swap! chunks conj c))
                     "grover" {})]
        (should (> (count @chunks) 1))
        (should= "Hello world" (get-in resp [:message :content]))))

    (it "final chunk has done true"
      (let [chunks (atom [])
            _      (sut/chat-stream
                     {:model "echo" :messages [{:role "user" :content "Hi"}]}
                     (fn [c] (swap! chunks conj c))
                     "grover" {})]
        (should (:done (last @chunks)))))

    (it "streams scripted chunk vectors and returns concatenated final content"
      (sut/enqueue! [{:content ["Once " "upon " "a " "time..."]}])
      (let [chunks      (atom [])
            resp        (sut/chat-stream
                         {:model "echo" :messages [{:role "user" :content "Ignored"}]}
                         (fn [c] (swap! chunks conj c))
                         "grover" {})
            chunk-texts (mapv #(get-in % [:message :content]) (butlast @chunks))]
        (should= ["Once " "upon " "a " "time..."] chunk-texts)
        (should= "Once upon a time..." (get-in resp [:message :content]))
        (should (:done (last @chunks)))))

    (it "strips tool calls from streamed final chunk when disabled"
      (sut/enqueue! [{:tool_call "exec" :arguments {:command "echo hi"}}])
      (let [chunks (atom [])
            resp   (sut/chat-stream
                    {:model "echo" :messages [{:role "user" :content "Run echo hi"}]}
                    (fn [c] (swap! chunks conj c))
                    "grover"
                    {:stream-supports-tool-calls false})]
        (should-be-nil (get-in resp [:message :tool_calls]))
        (should-be-nil (get-in (last @chunks) [:message :tool_calls]))))

    (it "accepts string false for stream-supports-tool-calls"
      (sut/enqueue! [{:tool_call "exec" :arguments {:command "echo hi"}}])
      (let [resp (sut/chat-stream
                   {:model "echo" :messages [{:role "user" :content "Run echo hi"}]}
                   (fn [_] nil)
                   "grover"
                   {:stream-supports-tool-calls "false"})]
        (should-be-nil (get-in resp [:message :tool_calls]))))

  ;; endregion ^^^^^ Streaming ^^^^^

  ;; region ----- Tool Call Loop -----

  (describe "followup-messages"

    (it "appends assistant tool_calls and role=tool replies"
      (let [response     {:message {:content    ""
                                    :tool_calls [{:function {:name "read" :arguments {}}}]}}
            request      {:messages [{:role "user" :content "Go"}]}
            tool-calls   [{:id "tc1" :name "read" :arguments {}}]
            tool-results ["file contents"]
            messages     (sut/followup-messages request response tool-calls tool-results)]
        (should= 3 (count messages))
        (should= "assistant" (:role (nth messages 1)))
        (should= [{:function {:name "read" :arguments {}}}]
                 (:tool_calls (nth messages 1)))
        (should= {:role "tool" :content "file contents"} (nth messages 2)))))

  ;; endregion ^^^^^ Tool Call Loop ^^^^^

  (describe "schema conformance"

    (it "echo chat returns a value conforming to provider/response"
      (let [result (sut/chat {:model "echo" :messages [{:role "user" :content "Hello"}]} "grover" {})]
        (should-not (api/error? result))
        (should-not-throw (api/validate-response result))))

    (it "scripted chat returns a value conforming to provider/response"
      (sut/enqueue! [{:type "text" :content "Done" :model "echo"}])
      (let [result (sut/chat {:model "echo" :messages [{:role "user" :content "Hi"}]} "grover" {})]
        (should-not (api/error? result))
        (should-not-throw (api/validate-response result))))

    (it "scripted error chat returns a value conforming to provider/error-response"
      (sut/enqueue! [{:type "error" :content "boom"}])
      (let [result (sut/chat {:model "echo" :messages [{:role "user" :content "Hi"}]} "grover" {})]
        (should (api/error? result))
        (should-not-throw (schema/conform! api/error-response result))))

    (it "chat-stream returns a value conforming to provider/response"
      (sut/enqueue! [{:type "text" :content "Hello world" :model "echo"}])
      (let [result (sut/chat-stream {:model "echo" :messages []} identity "grover" {})]
        (should-not (api/error? result))
        (should-not-throw (api/validate-response result)))))

  ))
