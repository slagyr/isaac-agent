(ns isaac.llm.responses-spec
  (:require
    [c3kit.apron.schema :as schema]
    [cheshire.core :as json]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.responses :as sut]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.http :as llm-http]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(defn- jwt-with-account-id [account-id]
  (let [payload (json/generate-string {"https://api.openai.com/auth" {"chatgpt_account_id" account-id}})
        enc     (.withoutPadding (java.util.Base64/getUrlEncoder))]
    (str "x." (.encodeToString enc (.getBytes payload "UTF-8")) ".y")))

(def oauth-device-config {:base-url  "https://chatgpt.com/backend-api/codex"
                          :auth      "oauth-device"
                          :name      "chatgpt"
                          :root "/tmp/isaac-home/.isaac"})

(describe "OpenAI Responses Provider"

  (describe "chat"

    (it "returns tool call arguments as a Clojure map on the /responses path"
      (let [token "fake-token"]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (reduce
                                                     (fn [acc event] (process-event event acc))
                                                     initial
                                                     [{:type "response.output_item.added"
                                                       :item {:type "function_call"
                                                              :id   "fc_abc"
                                                              :name "read_file"}}
                                                      {:type    "response.function_call_arguments.delta"
                                                       :item_id "fc_abc"
                                                       :delta   "{\"path\":\"README\"}"}
                                                      {:type    "response.function_call_arguments.done"
                                                       :item_id "fc_abc"}
                                                      {:type     "response.completed"
                                                       :response {:model "gpt-5.4"
                                                                  :usage {:input_tokens 10 :output_tokens 5}}}]))
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                       auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= 1 (count (:tool-calls result)))
            (should= {:path "README"} (:arguments (first (:tool-calls result))))
            (should (map? (get-in result [:message :tool_calls 0 :function :arguments])))))))

    (it "uses OAuth access token when auth is oauth-device"
      (let [captured-headers (atom nil)
            token            (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ headers _ _ process-event initial & _]
                                                   (reset! captured-headers headers)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                       auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "test" :messages [{:role "user" :content "hi"}]} "chatgpt" oauth-device-config)
          (should= (str "Bearer " token)
                   (get @captured-headers "Authorization")))))

    (it "uses chatgpt codex responses endpoint for oauth-device"
      (let [captured-url  (atom nil)
            captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [url _ body _ process-event initial & _]
                                                   (reset! captured-url url)
                                                   (reset! captured-body body)
                                                   (process-event {:type "response.output_text.delta" :delta "Hello from Codex"}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model   "gpt-5.4"
                                  :system  "You are Codex."
                                  :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= "https://chatgpt.com/backend-api/codex/responses" @captured-url)
            (should= true (:stream @captured-body))
            (should= "You are Codex." (:instructions @captured-body))
            (should= "Hello from Codex" (get-in result [:message :content]))))))

    (it "sanitizes responses input messages to supported keys"
      (let [captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured-body body)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model    "gpt-5.4"
                     :system   "You are Codex."
                     :messages [{:role "user" :content "hi" :model "gpt-5.4" :provider "chatgpt"}
                                {:role "assistant" :content "hello" :model "gpt-5.4"}]}
                     "chatgpt" oauth-device-config)
          (should= [{:role "user" :content "hi"}
                    {:role "assistant" :content "hello"}]
                   (:input @captured-body))
          (should= "You are Codex." (:instructions @captured-body)))))

    (it "adds codex headers for oauth-device tokens"
      (let [captured-headers (atom nil)
             token            (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ headers _ _ process-event initial & _]
                                                   (reset! captured-headers headers)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens   (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]} "chatgpt" oauth-device-config)
          (should= "acct-123" (get @captured-headers "ChatGPT-Account-Id"))
          (should= "isaac" (get @captured-headers "originator")))))

    (it "returns auth-missing when oauth-device login is unavailable"
      (with-redefs [auth-store/load-tokens (fn [_ _ _] nil)]
        (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                               "chatgpt"
                               {:name      "chatgpt"
                                :auth      "oauth-device"
                                :base-url  "https://api.openai.com/v1"
                                :root "/tmp/isaac-home/.isaac"})]
          (should= :auth-missing (:error result))
          (should-contain "isaac auth login --provider chatgpt" (:message result)))))

    (it "treats keyword :oauth-device auth like the string form"
      (with-redefs [auth-store/load-tokens (fn [_ _ _] nil)]
        (let [result (shared/missing-auth-error "chatgpt"
                                                {:auth :oauth-device
                                                 :root "/tmp/isaac-home/.isaac"})]
          (should= :auth-missing (:error result))
          (should-contain "isaac auth login --provider chatgpt" (:message result)))))

    (it "treats schema-coerced :oauth-device string auth like oauth-device"
      (with-redefs [auth-store/load-tokens (fn [_ _ _] nil)]
        (let [result (shared/missing-auth-error "chatgpt"
                                                {:auth ":oauth-device"
                                                 :root "/tmp/isaac-home/.isaac"})]
          (should= :auth-missing (:error result))
          (should-contain "isaac auth login --provider chatgpt" (:message result)))))

    (it "does not fall back to user.home when oauth-device root is missing"
      (let [captured-auth-dir (atom ::unset)]
        (with-redefs [auth-store/load-tokens (fn [auth-dir _ _]
                                               (reset! captured-auth-dir auth-dir)
                                               nil)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt"
                                 {:name     "chatgpt"
                                  :auth     "oauth-device"
                                  :base-url "https://api.openai.com/v1"})]
            (should= :auth-missing (:error result))
            (should= ::unset @captured-auth-dir)))))

    (it "uses oauth tokens from the configured state directory"
      (let [captured-auth-dir (atom nil)]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [auth-dir _ _]
                                                  (reset! captured-auth-dir auth-dir)
                                                  {:type "oauth" :access "token" :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                    "chatgpt"
                    {:name      "chatgpt"
                     :auth      "oauth-device"
                     :base-url  "https://api.openai.com/v1"
                     :root "/tmp/isaac-home/.isaac"})
          (should= "/tmp/isaac-home/.isaac" @captured-auth-dir))))

    (it "encodes role=tool messages as function_call_output in the responses-API wire body"
      (let [captured (atom nil)
            token        (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured body)
                                                   (reduce (fn [acc evt] (process-event evt acc))
                                                           initial
                                                           [{:type "response.output_text.delta" :delta "ok"}
                                                            {:type "response.completed"
                                                             :response {:model "gpt-5.4"
                                                                        :usage {:input_tokens 1 :output_tokens 1}}}]))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model    "gpt-5.4"
                     :messages [{:role "user" :content "what's under the lid?"}
                                {:role "assistant" :content "" :tool_calls [{:id "fc_123" :type "function"
                                                                               :function {:name "read"
                                                                                          :arguments (json/generate-string {:filePath "trash-lid.txt"})}}]}
                                {:role "tool" :tool_call_id "fc_123" :content "Old newspaper and a banana peel."}]}
                    "chatgpt" oauth-device-config)
          (let [body @captured]
            (should= "function_call_output" (get-in body [:input 2 :type]))
            (should= "fc_123" (get-in body [:input 2 :call_id]))
            (should= "Old newspaper and a banana peel." (get-in body [:input 2 :output]))
            (should-be-nil (get-in body [:input 2 :role])))))))

  (describe "shared helpers"

    (it "returns the configured base-url"
      (should= "https://chatgpt.com/backend-api/codex"
               (shared/provider-base-url {:base-url "https://chatgpt.com/backend-api/codex"})))

    (it "falls back to local ollama when base-url is missing"
      (should= "http://localhost:11434/v1"
               (shared/provider-base-url {}))))

  (describe "private helpers"

    (it "builds responses requests without instructions when system is blank"
      (let [result (@#'sut/->responses-request {:model    "gpt-5.4"
                                                 :system   ""
                                                 :messages [{:role "user" :content "hi"}]})]
        (should= {:model "gpt-5.4"
                  :input [{:role "user" :content "hi"}]
                  :store false}
                 result)))

    (it "preserves top-level tools in responses requests"
      (let [tools  [{:type "function" :name "read" :parameters {:type "object"}}]
            result (@#'sut/->responses-request {:model    "gpt-5.4"
                                                :messages [{:role "user" :content "hi"}]
                                                :tools    tools})]
        (should= tools (:tools result))))

    (it "converts assistant tool_calls to function_call items with call_id"
      (let [result (@#'sut/->responses-request {:model    "gpt-5.4"
                                                :messages [{:role "user" :content "what's under the lid?"}
                                                           {:role       "assistant"
                                                            :content    ""
                                                            :tool_calls [{:id       "fc_123"
                                                                          :type     "function"
                                                                          :function {:name      "read"
                                                                                     :arguments "{\"filePath\":\"trash-lid.txt\"}"}}]}
                                                           {:role "tool" :tool_call_id "fc_123" :content "banana peel"}]})]
        (should= {:type "function_call" :call_id "fc_123" :name "read" :arguments "{\"filePath\":\"trash-lid.txt\"}"}
                 (second (:input result)))
        (should= {:type "function_call_output" :call_id "fc_123" :output "banana peel"}
                 (nth (:input result) 2))))

    (it "converts tool role messages to function_call_output items"
      (let [result (@#'sut/->responses-request {:model    "gpt-5.4"
                                                :messages [{:role "user" :content "hi"}
                                                           {:role "tool" :tool_call_id "fc_123" :content {:result "done"}}]})]
        (should= [{:role "user" :content "hi"}
                  {:type "function_call_output" :call_id "fc_123" :output "{\"result\":\"done\"}"}]
                 (:input result))))

    (it "preserves store false on responses requests"
      (let [result (@#'sut/->responses-request {:model    "gpt-5.4"
                                                 :messages [{:role "user" :content "hi"}]})]
        (should= false (:store result))
        (should-not (contains? result :instructions))))

    (it "builds a responses request base with store disabled"
      (should= {:model "gpt-5.4"
                :input [{:role "user" :content "hi"}]
                :store false}
               (@#'sut/responses-request-base "gpt-5.4" [{:role "user" :content "hi"}]))))

  (describe "effort wire translation"

    (it "maps :effort 7 to reasoning high with summary auto"
      (let [captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured-body body)
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "gpt-5.4" :effort 7 :messages [{:role "user" :content "hi"}]}
                    "chatgpt" oauth-device-config)
          (should= {:effort "high" :summary "auto"} (:reasoning @captured-body)))))

    (it "maps :effort 3 to reasoning low with summary auto"
      (let [captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured-body body)
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model "snuffy-codex"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "snuffy-codex" :effort 3 :messages [{:role "user" :content "hi"}]}
                    "chatgpt" oauth-device-config)
          (should= {:effort "low" :summary "auto"} (:reasoning @captured-body)))))

    (it "omits the reasoning block when :effort is absent"
      (let [captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured-body body)
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                    "chatgpt" oauth-device-config)
          (should= nil (:reasoning @captured-body)))))

    (it "includes response reasoning and raw usage in the result"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model     "gpt-5.4"
                                                                              :usage     {:input_tokens  100
                                                                                          :output_tokens 50
                                                                                          :output_tokens_details {:reasoning_tokens 32}}
                                                                              :reasoning {:effort "high" :summary "Step by step."}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
          (should= 32 (get-in result [:response :usage :output_tokens_details :reasoning_tokens]))
          (should= "high" (get-in result [:response :reasoning :effort]))
          (should= "Step by step." (get-in result [:response :reasoning :summary]))))))

    (it "logs responses reasoning diagnostics including summary"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model     "gpt-5.4"
                                                                              :usage     {:input_tokens  100
                                                                                          :output_tokens 50
                                                                                          :output_tokens_details {:reasoning_tokens 32}
                                                                                          :input_tokens_details  {:cached_tokens 7}}
                                                                              :reasoning {:effort "high" :summary "Step by step."}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (log/capture-logs
            (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                      "chatgpt" oauth-device-config))
          (let [entry (first (filter #(= :responses/reasoning (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= :debug (:level entry))
            (should= "gpt-5.4" (:model entry))
            (should= "high" (:effort entry))
            (should= "Step by step." (:summary entry))
            (should= 32 (:reasoning-tokens entry))
            (should= 7 (:cached-tokens entry)))))))

  (describe "process-responses-sse-event"

    (it "accumulates output text deltas"
      (let [acc    {:content "" :model nil :usage {} :response nil}
            result (@#'sut/process-responses-sse-event {:type "response.output_text.delta" :delta "Hello"} acc)]
        (should= "Hello" (:content result))))

    (it "reconstructs tool calls from codex responses events"
      (let [acc     {:content "" :model nil :usage {} :response nil :tool-calls []}
            added   (@#'sut/process-responses-sse-event {:type "response.output_item.added"
                                                         :item {:id "fc_123"
                                                                :type "function_call"
                                                                :name "read"}}
                                                        acc)
            delta   (@#'sut/process-responses-sse-event {:type "response.function_call_arguments.delta"
                                                         :item_id "fc_123"
                                                         :delta "{\"filePath\":\"trash-lid.txt\"}"}
                                                        added)
            done    (@#'sut/process-responses-sse-event {:type "response.function_call_arguments.done"
                                                         :item_id "fc_123"}
                                                        delta)]
        (should= [{:id "fc_123" :name "read" :arguments {:filePath "trash-lid.txt"}}]
                 (:tool-calls done))))

    (it "stores usage and model from response.completed"
      (let [acc    {:content "" :model nil :usage {} :response nil}
            result (@#'sut/process-responses-sse-event {:type "response.completed"
                                                        :response {:model "gpt-5.4"
                                                                   :usage {:input_tokens 10 :output_tokens 5}}}
                                                       acc)]
        (should= "gpt-5.4" (:model result))
        (should= {:input_tokens 10 :output_tokens 5} (:usage result)))))

  (describe "chat-stream"

    (it "streams codex responses output for oauth-device"
      (let [chunks       (atom [])
            captured-url (atom nil)
            captured-body (atom nil)
            token        (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [url _ body on-chunk process-event initial & _]
                                                    (reset! captured-url url)
                                                    (reset! captured-body body)
                                                    (let [events [{:type "response.output_text.delta" :delta "Hello"}
                                                                  {:type "response.output_text.delta" :delta " world"}
                                                                  {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}]]
                                                    (reduce (fn [acc evt] (on-chunk evt) (process-event evt acc))
                                                            initial events)))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat-stream {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                        (fn [c] (swap! chunks conj c))
                                        "chatgpt" oauth-device-config)]
            (should= "https://chatgpt.com/backend-api/codex/responses" @captured-url)
            (should= true (:stream @captured-body))
            (should= "Hello world" (get-in result [:message :content]))
            (should= 2 (count @chunks))))))

    (it "returns codex tool calls parsed from responses SSE events"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (reduce (fn [acc evt] (process-event evt acc))
                                                           initial
                                                           [{:type "response.output_item.added"
                                                             :item {:id "fc_123" :type "function_call" :name "read"}}
                                                            {:type "response.function_call_arguments.delta"
                                                             :item_id "fc_123"
                                                             :delta "{\"filePath\":\"trash-lid.txt\"}"}
                                                            {:type "response.function_call_arguments.done"
                                                             :item_id "fc_123"}
                                                            {:type "response.completed"
                                                             :response {:model "gpt-5.4"
                                                                        :usage {:input_tokens 10 :output_tokens 5}}}]))
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                       auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model    "gpt-5.4"
                                  :messages [{:role "user" :content "what's under the lid?"}]
                                  :tools    [{:type "function" :name "read" :parameters {:type "object"}}]}
                                  "chatgpt" oauth-device-config)]
            (should= [{:id "fc_123" :name "read" :arguments {:filePath "trash-lid.txt"}}]
                     (:tool-calls result))))))

    (it "returns streaming errors for oauth-device"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [& _] {:error :api-error})
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat-stream {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                        identity
                                        "chatgpt" oauth-device-config)]
            (should= :api-error (:error result)))))))

  (describe "schema conformance"

    (it "chat (Responses API / codex) returns a value conforming to api/response"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (reduce (fn [acc evt] (process-event evt acc))
                                                           initial
                                                           [{:type "response.output_text.delta" :delta "ok"}
                                                            {:type "response.completed"
                                                             :response {:model "gpt-5.4"
                                                                        :usage {:input_tokens 1 :output_tokens 1}}}]))
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) 60000)})
                       auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should-not (api/error? result))
            (should-not-throw (api/validate-response result))))))

    (it "auth-missing errors conform to api/error-response"
      (with-redefs [auth-store/load-tokens (fn [_ _ _] nil)]
        (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                               "chatgpt"
                               {:name      "chatgpt"
                                :auth      "oauth-device"
                                :root "/tmp/.isaac"})]
          (should (api/error? result))
          (should-not-throw (schema/conform! api/error-response result)))))))
