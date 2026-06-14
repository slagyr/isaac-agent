(ns isaac.llm.messages-spec
  (:require
    [babashka.http-client :as http]
    [c3kit.apron.schema :as schema]
    [cheshire.core :as json]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.api.messages :as sut]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.http :as llm-http]
    [isaac.llm.api.protocol :as api]
    [speclj.core :refer :all]))

(defn- mock-response [body]
  {:status 200 :body (json/generate-string body)})

(defn- api-key-config []
  {:auth "api-key" :api-key "sk-test" :base-url "https://api.anthropic.com"})

(describe "Anthropic Client"

  (describe "build"

    (it "adds a trusted framing block ahead of the current user text"
      (let [transcript [{:type "session" :id "sess-1" :timestamp 1000}
                        {:type "message" :id "m1" :parentId "sess-1" :timestamp 2000
                         :message {:role "user" :content "Seal the leak."}}]
            request    (sut/build {:guidance   "Autonomous hail; the user may not see your reply."
                                   :model      "claude-sonnet-4-6"
                                   :nonce      "N0NCE-abc123"
                                   :origin     {:kind :hail :hail-id "hail-1"}
                                   :soul       "You are Isaac."
                                   :transcript transcript})]
        (should= "user" (get-in request [:messages 0 :role]))
        (should= "Seal the leak." (get-in request [:messages 0 :content 1 :text]))
        (should-contain "Autonomous hail; the user may not see your reply." (get-in request [:messages 0 :content 0 :text]))
        (should-contain "hail-1" (get-in request [:messages 0 :content 0 :text]))
        (should-contain "N0NCE-abc123" (get-in request [:messages 0 :content 0 :text]))
        (should-be-nil (get-in request [:messages 0 :cache_control]))))

    (it "keeps the framing block on the current turn and leaves the historical breakpoint origin-free"
      (let [transcript [{:type "session" :id "sess-1" :timestamp 1000}
                        {:type "message" :id "m1" :parentId "sess-1" :timestamp 2000
                         :message {:role "user" :content "First request."}}
                        {:type "message" :id "m2" :parentId "m1" :timestamp 3000
                         :message {:role "assistant" :content "First reply."}}
                        {:type "message" :id "m3" :parentId "m2" :timestamp 4000
                         :message {:role "user" :content "Seal the leak."}}]
            request    (sut/build {:guidance   "Autonomous hail; the user may not see your reply."
                                   :model      "claude-sonnet-4-6"
                                   :nonce      "N0NCE-abc123"
                                   :origin     {:kind :hail :hail-id "hail-2"}
                                   :soul       "You are Isaac."
                                   :transcript transcript})]
        (should= "First request." (get-in request [:messages 0 :content 0 :text]))
        (should= {:type "ephemeral"} (get-in request [:messages 0 :content 0 :cache_control]))
        (should-contain "Autonomous hail; the user may not see your reply." (get-in request [:messages 2 :content 0 :text]))
        (should-contain "hail-2" (get-in request [:messages 2 :content 0 :text]))
        (should= "Seal the leak." (get-in request [:messages 2 :content 1 :text]))
        (should-be-nil (get-in request [:messages 2 :content 1 :cache_control])))))

  (describe "chat"

    (it "parses a text response"
      (with-redefs [http/post (fn [_ _] (mock-response {:content    [{:type "text" :text "Hello!"}]
                                                         :model      "claude-sonnet-4-6"
                                                         :stop_reason "end_turn"
                                                         :usage      {:input_tokens 10 :output_tokens 5}}))]
        (let [result (sut/chat {:model "claude-sonnet-4-6" :messages []} "anthropic" (api-key-config))]
          (should= "Hello!" (get-in result [:message :content]))
          (should= "claude-sonnet-4-6" (:model result))
          (should= 10 (:input-tokens (:usage result)))
          (should= 5 (:output-tokens (:usage result))))))

    (it "extracts tool_use blocks as tool-calls"
      (with-redefs [http/post (fn [_ _] (mock-response {:content    [{:type "text" :text ""}
                                                                       {:type "tool_use" :id "tc1" :name "read_file" :input {:path "README"}}]
                                                         :model      "claude-sonnet-4-6"
                                                         :stop_reason "tool_use"
                                                         :usage      {:input_tokens 10 :output_tokens 5}}))]
        (let [result (sut/chat {:model "claude-sonnet-4-6" :messages []} "anthropic" (api-key-config))]
          (should= 1 (count (:tool-calls result)))
          (should= "read_file" (:name (first (:tool-calls result))))
          (should= {:path "README"} (:arguments (first (:tool-calls result)))))))

    (it "parses tool_use blocks from a grover-simulated anthropic provider"
      (grover/reset-queue!)
      (grover/enqueue! [{:model     "claude-sonnet-4-6"
                         :type      "tool_call"
                         :tool_call "load_skill"
                         :arguments {:name "greenhouse-protocol"}}])
      (let [result (sut/chat {:model "claude-sonnet-4-6" :messages []}
                             "anthropic"
                             (assoc (api-key-config) :simulate-provider "anthropic"))]
        (should= 1 (count (:tool-calls result)))
        (should= "load_skill" (:name (first (:tool-calls result))))
        (should= {:name "greenhouse-protocol"} (:arguments (first (:tool-calls result))))))

    (it "sets x-api-key header for api-key auth"
      (let [captured-headers (atom nil)]
        (with-redefs [http/post (fn [_ opts] (reset! captured-headers (:headers opts)) (mock-response {:content [] :usage {}}))]
          (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))
          (should= "sk-test" (get @captured-headers "x-api-key"))
          (should= "2023-06-01" (get @captured-headers "anthropic-version")))))

    (it "tracks cache tokens"
      (with-redefs [http/post (fn [_ _] (mock-response {:content [{:type "text" :text "Hi"}]
                                                         :model   "claude-sonnet-4-6"
                                                         :usage   {:input_tokens 10 :output_tokens 5
                                                                   :cache_read_input_tokens 3
                                                                   :cache_creation_input_tokens 2}}))]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should= 3 (:cache-read (:usage result)))
          (should= 2 (:cache-write (:usage result))))))

    (it "returns auth-failed on 401"
      (with-redefs [http/post (fn [_ _] {:status 401 :body (json/generate-string {:error {:message "invalid"}})})]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should= :auth-failed (:error result)))))

    (it "returns auth-missing when api key is blank and ANTHROPIC_API_KEY is unset"
      (with-redefs [shared/resolve-api-key (fn [_ _] nil)]
        (let [result (sut/chat {:model "test" :messages []}
                               "anthropic"
                               {:auth "api-key" :api-key "" :base-url "https://api.anthropic.com"})]
          (should= :auth-missing (:error result))
          (should-contain "ANTHROPIC_API_KEY" (:message result)))))

    (it "returns connection-refused on ConnectException"
      (with-redefs [http/post (fn [_ _] (throw (java.net.ConnectException.)))]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should= :connection-refused (:error result))))))

  (describe "followup-messages"

    (it "wraps tool-calls in tool_use blocks and tool-results in tool_result blocks"
      (let [tool-calls   [{:id "tc1" :name "read" :arguments {:path "x"}}
                          {:id "tc2" :name "write" :arguments {:path "y" :content "z"}}]
            tool-results ["file contents" "ok"]
            request      {:messages [{:role "user" :content "do stuff"}]}
            messages     (sut/followup-messages request nil tool-calls tool-results)
            assistant    (nth messages 1)
            user-result  (nth messages 2)]
        (should= 3 (count messages))
        (should= "assistant" (:role assistant))
        (should= [{:type "tool_use" :id "tc1" :name "read" :input {:path "x"}}
                  {:type "tool_use" :id "tc2" :name "write" :input {:path "y" :content "z"}}]
                 (:content assistant))
        (should= "user" (:role user-result))
        (should= [{:type "tool_result" :tool_use_id "tc1" :content "file contents"}
                  {:type "tool_result" :tool_use_id "tc2" :content "ok"}]
                 (:content user-result))))

    (it "preserves the original messages and appends the new turn"
      (let [request  {:messages [{:role "user" :content "go"}
                                 {:role "assistant" :content "ok"}]}
            messages (sut/followup-messages request nil
                                            [{:id "tc1" :name "x" :arguments {}}]
                                            ["result"])]
        (should= 4 (count messages))
        (should= [{:role "user" :content "go"}
                  {:role "assistant" :content "ok"}]
                 (subvec messages 0 2)))))

  (describe "process-sse-event"

    (it "accumulates content_block_delta"
      (let [acc {:role "assistant" :content "Hello" :usage {}}
            result (sut/process-sse-event {:type "content_block_delta" :delta {:text " world"}} acc)]
        (should= "Hello world" (:content result))))

    (it "merges message_delta usage"
      (let [acc {:role "assistant" :content "" :usage {:output_tokens 5}}
            result (sut/process-sse-event {:type "message_delta" :usage {:output_tokens 10}} acc)]
        (should= 10 (:output_tokens (:usage result)))))

    (it "sets model and usage on message_start"
      (let [acc {:role "assistant" :content "" :usage {}}
            result (sut/process-sse-event {:type "message_start"
                                           :message {:model "claude-sonnet-4-6"
                                                     :usage {:input_tokens 42}}} acc)]
        (should= "claude-sonnet-4-6" (:model result))
        (should= 42 (:input_tokens (:usage result)))))

    (it "passes through unknown event types"
      (let [acc {:role "assistant" :content "x" :usage {}}
            result (sut/process-sse-event {:type "ping"} acc)]
        (should= acc result))))

  (describe "chat-stream"

    (it "streams and accumulates response"
      (let [chunks (atom [])]
        (with-redefs [llm-http/post-sse! (fn [_ _ _ on-chunk process-event initial & _]
                                           (let [events [{:type "message_start" :message {:model "claude-sonnet-4-6" :usage {:input_tokens 10}}}
                                                         {:type "content_block_delta" :delta {:text "Hello"}}
                                                         {:type "content_block_delta" :delta {:text " world"}}
                                                         {:type "message_delta" :usage {:output_tokens 8}}]]
                                             (reduce (fn [acc evt] (on-chunk evt) (process-event evt acc))
                                                     initial events)))]
          (let [result (sut/chat-stream {:model "claude-sonnet-4-6" :messages []}
                         (fn [c] (swap! chunks conj c))
                         "anthropic" (api-key-config))]
            (should= "Hello world" (get-in result [:message :content]))
            (should= "claude-sonnet-4-6" (:model result))
            (should= 4 (count @chunks))))))

    (it "returns error on auth failure"
      (with-redefs [llm-http/post-sse! (fn [_ _ _ _ _ _ & _] {:error :auth-failed :status 401})]
        (let [result (sut/chat-stream {:model "test" :messages []} identity "anthropic" (api-key-config))]
          (should= :auth-failed (:error result)))))

    (it "returns auth-missing when streaming without api key and ANTHROPIC_API_KEY is unset"
      (with-redefs [shared/resolve-api-key (fn [_ _] nil)]
        (let [result (sut/chat-stream {:model "test" :messages []}
                                      identity
                                      "anthropic"
                                      {:auth "api-key" :api-key "" :base-url "https://api.anthropic.com"})]
          (should= :auth-missing (:error result))
          (should-contain "ANTHROPIC_API_KEY" (:message result))))))

  (describe "schema conformance"

    (it "chat returns a value conforming to provider/response"
      (with-redefs [http/post (fn [_ _] (mock-response {:content    [{:type "text" :text "Hi!"}]
                                                         :model      "claude-sonnet-4-6"
                                                         :stop_reason "end_turn"
                                                         :usage      {:input_tokens 10 :output_tokens 5}}))]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should-not (api/error? result))
          (should-not-throw (api/validate-response result)))))

    (it "chat with tool_use blocks returns a value conforming to provider/response"
      (with-redefs [http/post (fn [_ _] (mock-response {:content    [{:type "tool_use" :id "tc1" :name "read" :input {:path "x"}}]
                                                         :model      "claude-sonnet-4-6"
                                                         :stop_reason "tool_use"
                                                         :usage      {:input_tokens 10 :output_tokens 5}}))]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should-not (api/error? result))
          (should-not-throw (api/validate-response result)))))

    (it "chat-stream returns a value conforming to provider/response"
      (with-redefs [llm-http/post-sse! (fn [_ _ _ _ process-event initial & _]
                                         (reduce (fn [acc evt] (process-event evt acc))
                                                 initial
                                                 [{:type "message_start" :message {:model "claude-sonnet-4-6" :usage {:input_tokens 10}}}
                                                  {:type "content_block_delta" :delta {:text "Hi!"}}
                                                  {:type "message_delta" :usage {:output_tokens 5}}]))]
        (let [result (sut/chat-stream {:model "test" :messages []} identity "anthropic" (api-key-config))]
          (should-not (api/error? result))
          (should-not-throw (api/validate-response result)))))

    (it "auth errors conform to provider/error-response"
      (with-redefs [shared/resolve-api-key (fn [_ _] nil)]
        (let [result (sut/chat {:model "test" :messages []}
                               "anthropic"
                               {:auth "api-key" :api-key "" :base-url "https://api.anthropic.com"})]
          (should (api/error? result))
          (should-not-throw (schema/conform! api/error-response result)))))

    (it "401 responses conform to provider/error-response"
      (with-redefs [http/post (fn [_ _] {:status 401 :body (json/generate-string {:error {:message "invalid"}})})]
        (let [result (sut/chat {:model "test" :messages []} "anthropic" (api-key-config))]
          (should (api/error? result))
          (should-not-throw (schema/conform! api/error-response result))))))

  (describe "effort->thinking"

    (it "maps effort 10 to 100% of default budget-max (32000)"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 10 :messages []} "anthropic" (api-key-config)))
        (should= {:type "enabled" :budget_tokens 32000} (:thinking @captured))))

    (it "maps effort 5 to 50% of default budget-max (16000)"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 5 :messages []} "anthropic" (api-key-config)))
        (should= {:type "enabled" :budget_tokens 16000} (:thinking @captured))))

    (it "maps effort 1 to 10% of default budget-max (3200)"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 1 :messages []} "anthropic" (api-key-config)))
        (should= {:type "enabled" :budget_tokens 3200} (:thinking @captured))))

    (it "maps effort 0 to nil (omits thinking block)"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 0 :messages []} "anthropic" (api-key-config)))
        (should-be-nil (:thinking @captured))))

    (it "omits thinking block when :effort absent"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :messages []} "anthropic" (api-key-config)))
        (should-be-nil (:thinking @captured))))

    (it "scales budget with thinking-budget-max from config"
      (let [captured (atom nil)
            config   (assoc (api-key-config) :thinking-budget-max 64000)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 5 :messages []} "anthropic" config))
        (should= {:type "enabled" :budget_tokens 32000} (:thinking @captured))))

    (it "strips :effort from the outbound request body"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "claude" :effort 7 :messages []} "anthropic" (api-key-config)))
        (should-not (contains? @captured :effort))))))
