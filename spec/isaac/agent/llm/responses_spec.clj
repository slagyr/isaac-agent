(ns isaac.agent.llm.responses-spec
  (:require
    [c3kit.apron.schema :as schema]
    [cheshire.core :as json]
    [isaac.agent.llm.auth.store :as auth-store]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.llm.api.responses :as sut]
    [isaac.agent.llm.api.openai.shared :as shared]
    [isaac.agent.llm.http :as llm-http]
    [isaac.foundation.logger :as log]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- jwt-with-account-id [account-id]
  (let [payload (json/generate-string {"https://api.openai.com/auth" {"chatgpt_account_id" account-id}})
        enc     (.withoutPadding (java.util.Base64/getUrlEncoder))]
    (str "x." (.encodeToString enc (.getBytes payload "UTF-8")) ".y")))

(def oauth-device-config {:base-url  "https://chatgpt.com/backend-api/codex"
                          :auth      "oauth-device"
                          :name      "chatgpt"
                          :root "/tmp/isaac-home/.isaac"
                          :oauth     {:issuer "https://auth.openai.com"
                                      :client-id "app_EMoamEEZ73f0CkXaXp7hrann"
                                      :device-path "/api/accounts/deviceauth/usercode"
                                      :poll-path "/api/accounts/deviceauth/token"
                                      :token-path "/oauth/token"
                                      :verification-url "https://auth.openai.com/codex/device"
                                      :originator "isaac"
                                      :chatgpt-account-id? true}})

(describe "OpenAI Responses Provider"

  (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

  (it "sends a tool image as an input_image item in function_call_output"
    (let [image {:type "image" :media-type "image/png" :bytes 3 :path "/tmp/pixel.png" :data "YWJj"}
          messages (api/followup-messages (sut/make "chatgpt" {}) {:messages []} {:content ""}
                                          [{:id "tc1" :name "read" :arguments {}}] [image])
          input (#'sut/->responses-request {:model "test" :messages messages})]
      (should= [{:type "input_image" :image_url "data:image/png;base64,YWJj"}]
               (get-in input [:input 1 :output]))))

  (it "preserves images from preceding turns while replacing only the current function output"
    (let [image {:type "image" :media-type "image/png" :bytes 3 :path "/tmp/pixel.png" :data "YWJj"}
          previous {:role "user" :content [{:type "image_url" :image_url {:url "data:image/png;base64,old"}}]}
          messages (api/followup-messages (sut/make "chatgpt" {}) {:messages [previous]} {:content ""}
                                          [{:id "tc1" :name "read" :arguments {}}] [image])]
      (should= previous (first messages))
      (should= 3 (count messages))
      (should= [{:type "input_image" :image_url "data:image/png;base64,YWJj"}]
               (get-in messages [2 :content]))))

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
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                       auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= 1 (count (:tool-calls result)))
            (should= {:path "README"} (:arguments (first (:tool-calls result))))
            (should (map? (get-in result [:tool-calls 0 :arguments])))))))

    (it "uses OAuth access token when auth is oauth-device"
      (let [captured-headers (atom nil)
            token            (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ headers _ _ process-event initial & _]
                                                   (reset! captured-headers headers)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                                                   (->> initial
                                                        (process-event {:type "response.output_text.delta" :delta "Hello from Codex"})
                                                        (process-event {:type "response.completed" :response {}})))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model   "gpt-5.4"
                                  :system  "You are Codex."
                                  :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= "https://chatgpt.com/backend-api/codex/responses" @captured-url)
            (should= true (:stream @captured-body))
            (should= "You are Codex." (:instructions @captured-body))
            (should= "Hello from Codex" (:content result))))))

    (it "sanitizes responses input messages to supported keys"
      (let [captured-body (atom nil)
            token         (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ body _ process-event initial & _]
                                                   (reset! captured-body body)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 10 :output_tokens 5}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens   (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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

    (it "refreshes expired oauth tokens before a codex request"
      (let [token "refreshed-token"
            root  "/tmp/isaac-home/.isaac"
            captured-headers (atom nil)]
        (with-redefs [llm-http/post-sse!         (fn [_ headers _ _ process-event initial & _]
                                                   (reset! captured-headers headers)
                                                   (process-event {:type "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 1 :output_tokens 1}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _]
                                                  {:type "oauth" :access "stale" :refresh "rt-ok"
                                                   :expires (- (System/currentTimeMillis) 1000)})
                      auth-store/token-needs-refresh? auth-store/token-needs-refresh?
                      auth-store/refresh-oauth-tokens! (fn [_ _ _ descriptor]
                                                         (should= (:oauth oauth-device-config) descriptor)
                                                         {:tokens {:type "oauth" :access token
                                                                   :expires (+ (System/currentTimeMillis) (* 30 60 1000))}})]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt"
                                 (assoc oauth-device-config :root root))]
            (should-not (:error result))
            (should= (str "Bearer " token) (get @captured-headers "Authorization"))))))

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
                                                  {:type "oauth" :access "token" :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
            (should-be-nil (get-in body [:input 2 :role]))))))

    (it "an unchained request reports the prompt it just sent (isaac-dgod)"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 100 :output_tokens 50}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= :request (get-in result [:usage :prompt-scope]))
            (should= 100 (get-in result [:usage :prompt-tokens]))))))

    (it "a chained request declares no prompt size — the chain's usage is a running sum (isaac-dgod)"
      ;; orchestration-verify stamped 12,031,158 of a 278,528 window with
      ;; cache-read 11,174,912. The Responses API bills a :response-id chain
      ;; cumulatively, so this adapter cannot say how big the prompt it just
      ;; sent was, and says so rather than letting the gauge invent a number.
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse!         (fn [_ _ _ _ process-event initial & _]
                                                   (process-event {:type     "response.completed"
                                                                   :response {:model "gpt-5.4"
                                                                              :usage {:input_tokens 12031158 :output_tokens 50}}}
                                                                  initial))
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model                "gpt-5.4"
                                  :stateful             true
                                  :previous-response-id "resp-1"
                                  :messages             [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
            (should= :unknown (get-in result [:usage :prompt-scope]))))))
    )

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
               (@#'sut/responses-request-base "gpt-5.4" [{:role "user" :content "hi"}] false)))

    (it "builds a responses request base with store enabled when stateful"
      (should= {:model "gpt-5.4"
                :input [{:role "user" :content "hi"}]
                :store true}
               (@#'sut/responses-request-base "gpt-5.4" [{:role "user" :content "hi"}] true)))

    (it "when stateful, cycle 2+ request chains previous_response_id and sends only tool outputs"
      (let [result (@#'sut/->responses-request
                     {:model                 "snuffy-codex"
                      :stateful              true
                      :previous-response-id  "resp-1"
                      :messages              [{:role "user" :content "count the cans"}
                                              {:role       "assistant"
                                               :content    ""
                                               :tool_calls [{:id       "fc_1"
                                                             :type     "function"
                                                             :function {:name      "exec"
                                                                        :arguments "{\"command\":\"true\"}"}}]}
                                              {:role "tool" :tool_call_id "fc_1" :content "ok"}]})]
        (should= true (:store result))
        (should= "resp-1" (:previous_response_id result))
        (should= 1 (count (:input result)))
        (should= "function_call_output" (:type (first (:input result))))))

    (it "a chained codex request does not send instructions"
      (let [result (@#'sut/->codex-responses-request
                     {:model                "grok-4.6"
                      :stateful             true
                      :previous-response-id "resp-1"
                      :messages             [{:role "user" :content "count the cans"}
                                             {:role       "assistant"
                                              :content    ""
                                              :tool_calls [{:id       "fc_1"
                                                            :type     "function"
                                                            :function {:name      "exec"
                                                                       :arguments "{\"command\":\"true\"}"}}]}
                                             {:role "tool" :tool_call_id "fc_1" :content "ok"}]}
                     nil)]
        (should= "resp-1" (:previous_response_id result))
        (should-not (contains? result :instructions))))

    (it "an unchained codex request with no system prompt still sends empty instructions"
      (let [result (@#'sut/->codex-responses-request
                     {:model    "gpt-5.4"
                      :messages [{:role "user" :content "hi"}]}
                     nil)]
        (should= "" (:instructions result))
        (should-not (contains? result :previous_response_id))))

    (it "without stateful, full context is resent and previous_response_id is omitted"
      (let [result (@#'sut/->responses-request
                     {:model                "snuffy-codex"
                      :previous-response-id "resp-1"
                      :messages             [{:role "user" :content "count the cans"}
                                             {:role "tool" :tool_call_id "fc_1" :content "ok"}]})]
        (should= false (:store result))
        (should-not (contains? result :previous_response_id))
        (should= "user" (:role (first (:input result))))))

    (it "when chained, omits historical tool results and keeps only the batch after the last assistant"
      (let [result (@#'sut/->responses-request
                     {:model                "snuffy-codex"
                      :stateful             true
                      :previous-response-id "resp-2"
                      :messages             [{:role "user" :content "old"}
                                             {:role       "assistant"
                                              :content    ""
                                              :tool_calls [{:id       "fc_old"
                                                            :type     "function"
                                                            :function {:name "exec" :arguments "{}"}}]}
                                             {:role "tool" :tool_call_id "fc_old" :content "old-result"}
                                             {:role       "assistant"
                                              :content    ""
                                              :tool_calls [{:id       "fc_new"
                                                            :type     "function"
                                                            :function {:name "exec" :arguments "{}"}}]}
                                             {:role "tool" :tool_call_id "fc_new" :content "new-result"}]})]
        (should= "resp-2" (:previous_response_id result))
        (should= [{:type "function_call_output" :call_id "fc_new" :output "new-result"}]
                 (:input result))))

    (it "when chained, a multi-call batch includes every output of that batch in call order"
      (let [result (@#'sut/->responses-request
                     {:model                "snuffy-codex"
                      :stateful             true
                      :previous-response-id "resp-1"
                      :messages             [{:role "user" :content "open both lids"}
                                             {:role "tool" :tool_call_id "fc_hist" :content "stale"}
                                             {:role       "assistant"
                                              :content    ""
                                              :tool_calls [{:id "fc_a" :type "function"
                                                            :function {:name "exec" :arguments "{\"command\":\"a\"}"}}
                                                           {:id "fc_b" :type "function"
                                                            :function {:name "exec" :arguments "{\"command\":\"b\"}"}}]}
                                             {:role "tool" :tool_call_id "fc_a" :content "alpha"}
                                             {:role "tool" :tool_call_id "fc_b" :content "bravo"}]})]
        (should= [{:type "function_call_output" :call_id "fc_a" :output "alpha"}
                  {:type "function_call_output" :call_id "fc_b" :output "bravo"}]
                 (:input result))))

    (it "when chained, cycle 3 carries only cycle 2's outputs"
      (let [result (@#'sut/->responses-request
                     {:model                "snuffy-codex"
                      :stateful             true
                      :previous-response-id "resp-2"
                      :messages             [{:role "user" :content "count"}
                                             {:role "tool" :tool_call_id "fc_1" :content "cycle-1"}
                                             {:role       "assistant"
                                              :content    ""
                                              :tool_calls [{:id "fc_2" :type "function"
                                                            :function {:name "exec" :arguments "{}"}}]}
                                             {:role "tool" :tool_call_id "fc_2" :content "cycle-2"}]})]
        (should= [{:type "function_call_output" :call_id "fc_2" :output "cycle-2"}]
                 (:input result))))
    )

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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                 "chatgpt" oauth-device-config)]
          (should= 32 (get-in result [:provider-data :usage :output_tokens_details :reasoning_tokens]))
          (should= "high" (get-in result [:provider-data :reasoning :effort]))
          (should= "Step by step." (get-in result [:provider-data :reasoning :summary]))))))

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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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

    (it "reads two parallel calls without argument deltas"
      (let [events [{:type "response.output_item.added" :item {:type "function_call" :id "fc-1" :name "exec__run"}}
                    {:type "response.function_call_arguments.done" :item_id "fc-1" :arguments "{\"command\":\"main\"}"}
                    {:type "response.output_item.done" :item {:type "function_call" :id "fc-1" :name "exec__run"
                                                               :arguments "{\"command\":\"main\"}"}}
                    {:type "response.output_item.added" :item {:type "function_call" :id "fc-2" :name "exec__run"}}
                    {:type "response.function_call_arguments.done" :item_id "fc-2" :arguments "{\"command\":\"jib\"}"}
                    {:type "response.output_item.done" :item {:type "function_call" :id "fc-2" :name "exec__run"
                                                               :arguments "{\"command\":\"jib\"}"}}]
            result (reduce (fn [acc event] (@#'sut/process-responses-sse-event event acc))
                           {:tool-calls []} events)]
        (should= [{:id "fc-1" :name "exec__run" :arguments {:command "main"}}
                  {:id "fc-2" :name "exec__run" :arguments {:command "jib"}}]
                 (:tool-calls result))))

    (it "prefers the finished item over progress deltas and arguments.done"
      (let [events [{:type "response.output_item.added" :item {:type "function_call" :id "fc-1" :name "old"}}
                    {:type "response.function_call_arguments.delta" :item_id "fc-1" :delta "{\"command\":\"delta\"}"}
                    {:type "response.function_call_arguments.done" :item_id "fc-1" :arguments "{\"command\":\"done\"}"}
                    {:type "response.output_item.done" :item {:type "function_call" :id "fc-1" :name "exec__run"
                                                               :arguments "{\"command\":\"finished\"}"}}]
            result (reduce (fn [acc event] (@#'sut/process-responses-sse-event event acc))
                           {:tool-calls []} events)]
        (should= [{:id "fc-1" :name "exec__run" :arguments {:command "finished"}}]
                 (:tool-calls result))))

    (it "uses delta arguments when a stream has no done events"
      (let [events [{:type "response.output_item.added" :item {:type "function_call" :id "fc-1" :name "exec__run"}}
                    {:type "response.function_call_arguments.delta" :item_id "fc-1" :delta "{\"command\":\"main\"}"}
                    {:type "response.completed" :response {:model "snuffy-codex"}}]
            result (reduce (fn [acc event] (@#'sut/process-responses-sse-event event acc))
                           {:tool-calls []} events)]
        (should= {:command "main"} (get-in result [:tool-calls 0 :arguments]))))

    (it "stores usage and model from response.completed"
      (let [acc    {:content "" :model nil :usage {} :response nil}
            result (@#'sut/process-responses-sse-event {:type "response.completed"
                                                        :response {:model "gpt-5.4"
                                                                   :usage {:input_tokens 10 :output_tokens 5}}}
                                                       acc)]
        (should= "gpt-5.4" (:model result))
        (should= {:input_tokens 10 :output_tokens 5} (:usage result)))))

    (it "marks only response.completed as a complete stream"
      (let [initial {:content "partial" :tool-calls [{:name "read"}]}
            completed (@#'sut/process-responses-sse-event
                        {:type "response.completed" :response {}} initial)]
        (should-not (:completed? initial))
        (should= true (:completed? completed))))

  (describe "chat-stream"

    (it "rejects a partial response without response.completed"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse! (fn [_ _ _ _ process-event initial & _]
                                           (process-event {:type "response.output_text.delta"
                                                           :delta "unfinished"} initial))
                      auth-store/load-tokens (fn [_ _ _] {:access token :expires (+ (System/currentTimeMillis) 1800000)})
                      auth-store/token-expired? (fn [_] false)]
          (should= :stream-ended-early
                   (:error (sut/chat-stream {:model "test" :messages []}
                                            identity "chatgpt" oauth-device-config))))))


    (it "keeps a mid-stream HTTP wall status and retry-after instead of a partial reply"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse! (fn [_ _ _ on-chunk process-event initial & _]
                                           (on-chunk {:type "response.output_text.delta" :delta "partial"})
                                           (process-event {:type "response.output_text.delta" :delta "partial"} initial)
                                           {:error :api-error :status 429 :retry-after 60 :body {:error "rate limited"}})
                      auth-store/load-tokens (fn [_ _ _] {:access token :expires (+ (System/currentTimeMillis) 1800000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat-stream {:model "test" :messages []} identity "chatgpt" oauth-device-config)]
            (should= :rate-limited (:error result))
            (should= 429 (:status result))
            (should= 60000 (:retry-after-ms result))
            (should-not (:content result))))))

    (it "classifies an error event following a text delta as provider weather"
      (let [token (jwt-with-account-id "acct-123")]
        (with-redefs [llm-http/post-sse! (fn [_ _ _ on-chunk process-event initial & _]
                                           (reduce (fn [acc event] (on-chunk event) (process-event event acc))
                                                   initial
                                                   [{:type "response.output_text.delta" :delta "unfinished"}
                                                    {:type "response.failed"
                                                     :response {:error {:status 401 :message "expired token"}}}]))
                      auth-store/load-tokens (fn [_ _ _] {:access token :expires (+ (System/currentTimeMillis) 1800000)})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat-stream {:model "test" :messages []} identity "chatgpt" oauth-device-config)]
            (should= 401 (:status result))
            (should= :auth-failed (:error result))
            (should-not (:content result))))))

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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
                      auth-store/token-expired? (fn [_] false)]
          (let [result (sut/chat-stream {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}
                                        (fn [c] (swap! chunks conj c))
                                        "chatgpt" oauth-device-config)]
            (should= "https://chatgpt.com/backend-api/codex/responses" @captured-url)
            (should= true (:stream @captured-body))
            (should= "Hello world" (:content result))
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
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                      auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
                       auth-store/load-tokens    (fn [_ _ _] {:type "oauth" :access token :expires (+ (System/currentTimeMillis) (* 30 60 1000))})
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
