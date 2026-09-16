(ns isaac.llm.api-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.fs :as fs]
    [isaac.llm.api.messages :as messages]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.api.ollama :as ollama]
    [isaac.llm.api.chat-completions :as chat-completions]
    [isaac.llm.api.responses :as responses]
    [isaac.llm.api.protocol :as sut]
    [isaac.llm.provider :as llm-provider]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.llm.api.protocol"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (describe "error?"

    (it "returns true when :error is present"
      (should (sut/error? {:error :auth-missing :message "no key"})))

    (it "returns false on a normal Response"
      (should-not (sut/error? {:message {:role "assistant" :content "hi"}
                               :model   "x"})))

    (it "returns false on nil"
      (should-not (sut/error? nil))))

  (context "provider-neutral contracts"

    (it "validates a response without dropping adapter metadata"
      (let [response {:content     "hi"
                      :tool-calls  []
                      :stop-reason :end-turn
                      :model       "claude-sonnet-4-6"
                      :usage       {:prompt-tokens 10 :output-tokens 5}
                      :harbor-log  ["close-hauled"]}]
        (should-not (schema/error? (sut/validate-response response)))
        (should= ["close-hauled"] (:harbor-log response))))

    (it "reports every missing or invalid response field"
      (let [result (sut/validate-response {:content 42
                                           :tool-calls []
                                           :model "echo"
                                           :usage {:output-tokens 3}})]
        (should= {:content "must be a string"
                  :stop-reason "is invalid"
                  :usage {:prompt-tokens "is required"}}
                 (schema/message-map result))))

    (it "accepts malformed tool arguments as a normalized tool call"
      (let [tool-call {:id "tc1" :name "read" :arguments {} :arguments-error "Unexpected end of input"}]
        (should= tool-call (schema/validate! sut/tool-call tool-call))))

    (it "requires complete request usage"
      (should= {:prompt-tokens "is required"}
               (schema/validate-message-map sut/usage {:output-tokens 5})))

    (it "validates normalized errors with optional usage"
      (let [error {:error :rate-limited
                   :message "slow down"
                   :status 429
                   :retry-after-ms 60000
                   :usage {:prompt-tokens 10 :output-tokens 0}}]
        (should-not (schema/error? (sut/validate-error error)))))

    (it "rejects provider-specific error kinds"
      (should= {:error "is invalid"}
               (schema/validate-message-map sut/error {:error :overloaded :message "busy"})))

    (it "validates turn usage and loop results"
      (let [response {:content "done" :tool-calls [] :stop-reason :end-turn :model "echo"
                      :usage {:prompt-tokens 25 :output-tokens 12}}
            result   {:response response
                      :tool-calls []
                      :usage {:requests 1 :prompt-tokens 25 :output-tokens 12}
                      :cancelled? false
                      :loop-request? false}]
        (should= result (schema/validate! sut/loop-result result))))
    )

  (describe "registry"

    (after (sut/unregister! :spec-test))

    (it "register! adds a factory; factory-for retrieves it via keyword or string"
      (let [calls (atom [])
            f     (fn [name cfg] (swap! calls conj [name cfg]) ::an-api)]
        (sut/register! "spec-test" f)
        (let [retrieved (sut/factory-for :spec-test)]
          (should= ::an-api (retrieved "x" {:foo 1}))
          (should= f (sut/factory-for "spec-test"))
          (should= [["x" {:foo 1}]] @calls))))

    (it "registered-apis includes api keys that are currently registered"
      (sut/register! :spec-test (fn [_ _] ::p))
      (should-contain :spec-test (sut/registered-apis)))

    (it "unregister! removes the factory"
      (sut/register! :spec-test (fn [_ _] ::p))
      (should (sut/factory-for :spec-test))
      (sut/unregister! "spec-test")
      (should-be-nil (sut/factory-for :spec-test)))

    (it "register! overwrites a prior factory for the same api"
      (sut/register! :spec-test (fn [_ _] ::v1))
      (sut/register! "spec-test" (fn [_ _] ::v2))
      (should= ::v2 ((sut/factory-for :spec-test) "x" {}))))

  (describe "production built-ins"

    (it "does not register grover when the agent module activates outside test mode"
      (let [was-enabled? (grover/test-registration-enabled?)]
        (try
          (grover/disable-test-registration!)
          (sut/unregister! :grover)
          (module-loader/clear-activations!)
          (module-loader/activate! :isaac.agent (module-loader/builtin-index))
          (should-not (contains? (sut/registered-apis) :grover))
          (finally
            (if was-enabled?
              (grover/install-test-fixture!)
              (grover/disable-test-registration!)))))))

  (describe "resolve-api"

    (before (grover/install-test-fixture!))

    (it "returns keyword apis"
      (should= :ollama (sut/resolve-api "ollama" {}))
      (should= :messages (sut/resolve-api "anthropic" {}))
      (should= :grover (sut/resolve-api "grover" {}))))

  (describe "format-tools"

    (it "wrapped-function-tool nests :name/:description/:parameters under :function"
      (should= {:type "function"
                :function {:name "memory_get" :description "Read" :parameters {:type "object"}}}
               (sut/wrapped-function-tool {:name "memory_get" :description "Read" :parameters {:type "object"}})))

    (it "flat-function-tool keeps fields at the top level"
      (should= {:type        "function"
                :name        "memory_get"
                :description "Read"
                :parameters  {:type "object"}}
               (sut/flat-function-tool {:name "memory_get" :description "Read" :parameters {:type "object"}})))

    (it "ResponsesAPI emits flat shape"
      (let [api       (responses/make "chatgpt" {:api "responses"})
            tool-defs [{:name "memory_get" :description "Read" :parameters {:type "object"}}
                       {:name "memory_write" :description "Write" :parameters {:type "object"}}]]
        (should= [{:type "function" :name "memory_get"   :description "Read"  :parameters {:type "object"}}
                  {:type "function" :name "memory_write" :description "Write" :parameters {:type "object"}}]
                 (sut/format-tools api tool-defs))))

    (it "ChatCompletionsAPI emits wrapped shape"
      (let [api       (chat-completions/make "openai" {:api "chat-completions"})
            tool-defs [{:name "memory_get" :description "Read" :parameters {:type "object"}}]]
        (should= [{:type "function" :function {:name "memory_get" :description "Read" :parameters {:type "object"}}}]
                 (sut/format-tools api tool-defs))))

    (it "OllamaAPI emits wrapped shape"
      (let [api       (ollama/make "ollama" {:api "ollama"})
            tool-defs [{:name "memory_get" :description "Read" :parameters {:type "object"}}]]
        (should= [{:type "function" :function {:name "memory_get" :description "Read" :parameters {:type "object"}}}]
                 (sut/format-tools api tool-defs))))

    (it "MessagesAPI emits Anthropic shape with :input_schema"
      (let [api       (messages/make "anthropic" {:api "messages"})
            tool-defs [{:name "memory_get" :description "Read" :parameters {:type "object"}}]]
        (should= [{:name "memory_get" :description "Read" :input_schema {:type "object"}}]
                 (sut/format-tools api tool-defs))))

    (it "GroverAPI emits wrapped shape"
      (let [api       (grover/make "grover" {:api "grover"})
            tool-defs [{:name "memory_get" :description "Read" :parameters {:type "object"}}]]
        (should= [{:type "function" :function {:name "memory_get" :description "Read" :parameters {:type "object"}}}]
                 (sut/format-tools api tool-defs))))

    (it "returns nil for empty/nil tools"
      (let [api (responses/make "chatgpt" {:api "responses"})]
        (should-be-nil (sut/format-tools api nil))
        (should-be-nil (sut/format-tools api [])))))

  (describe "normalize-pair"

    (it "does not materialize catalog defaults for manifest-only providers"
      (let [[_ cfg] (llm-provider/normalize-pair "openai" {:model "gpt-5"})]
        (should-be-nil (:api cfg))
        (should-be-nil (:base-url cfg))
        (should= "gpt-5" (:model cfg))))

    (it "user config overrides catalog defaults"
      (let [[_ cfg] (llm-provider/normalize-pair "ollama" {:base-url "http://custom:11434"})]
        (should= "http://custom:11434" (:base-url cfg))))

    (it "returns provider unchanged when not in catalog and no grover prefix"
      (let [[name cfg] (llm-provider/normalize-pair "my-custom" {:api "some-api"})]
        (should= "my-custom" name)
        (should= "some-api" (:api cfg))))

    (it "resolves grover:<target> to target with grover simulation fields"
      (let [[name cfg] (llm-provider/normalize-pair "grover:openai" {})]
        (should= "openai" name)
        (should= "chat-completions" (:api cfg))
        (should= "grover" (:api-key cfg))
        (should= "openai" (:simulate-provider cfg))))))
