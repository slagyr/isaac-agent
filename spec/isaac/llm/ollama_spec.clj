(ns isaac.llm.ollama-spec
  (:require
    [babashka.http-client :as http]
    [c3kit.apron.schema :as schema]
    [cheshire.core :as json]
    [isaac.llm.http :as llm-http]
    [isaac.llm.api.ollama :as sut]
    [isaac.llm.api.protocol :as api]
    [speclj.core :refer :all]))

(defn- mock-response [body]
  {:status 200 :body (json/generate-string body)})

(describe "Ollama Client"

  (describe "chat"

    (it "sends request and parses response"
      (with-redefs [http/post (fn [_ _] (mock-response {:model   "qwen3-coder:30b"
                                                         :message {:role "assistant" :content "Hello!"}
                                                         :done    true
                                                         :prompt_eval_count 10
                                                         :eval_count 5}))]
        (let [result (sut/chat {:model "qwen3-coder:30b" :messages [{:role "user" :content "Hi"}]} "ollama" {})]
          (should= "Hello!" (:content result))
          (should= "qwen3-coder:30b" (:model result)))))

    (it "returns connection-refused on ConnectException"
      (with-redefs [http/post (fn [_ _] (throw (java.net.ConnectException.)))]
        (let [result (sut/chat {:model "test" :messages []} "ollama" {})]
          (should= :connection-refused (:error result)))))

    (it "constructs correct URL with base-url"
      (let [captured-url (atom nil)]
        (with-redefs [http/post (fn [url _] (reset! captured-url url) (mock-response {:message {:role "assistant" :content ""}}))]
          (sut/chat {:model "test" :messages []} "ollama" {:base-url "http://myhost:1234"})
          (should= "http://myhost:1234/api/chat" @captured-url))))

    (it "sets stream to false"
      (let [captured-body (atom nil)]
        (with-redefs [http/post (fn [_ opts] (reset! captured-body (json/parse-string (:body opts) true)) (mock-response {:message {:role "assistant" :content ""}}))]
          (sut/chat {:model "test" :messages []} "ollama" {})
          (should= false (:stream @captured-body))))))

  (describe "followup-messages"

    (it "appends an assistant message with raw tool_calls and one role=tool reply per result"
      (let [tool-calls   [{:id "tc1" :name "read" :arguments {:path "x"}
                           :raw  {:function {:name "read" :arguments {:path "x"}}}}
                          {:id "tc2" :name "write" :arguments {:path "y"}
                           :raw  {:function {:name "write" :arguments {:path "y"}}}}]
            tool-results ["file contents" "wrote ok"]
            response     {:content ""}
            request      {:messages [{:role "user" :content "go"}]}
            messages     (sut/followup-messages request response tool-calls tool-results)]
        (should= 4 (count messages))
        (should= {:role "user" :content "go"} (first messages))
        (should= "assistant" (:role (nth messages 1)))
        (should= [{:id "tc1" :type "function" :function {:name "read" :arguments {:path "x"}}}
                  {:id "tc2" :type "function" :function {:name "write" :arguments {:path "y"}}}]
                 (:tool_calls (nth messages 1)))
        (should= {:role "tool" :content "file contents"} (nth messages 2))
        (should= {:role "tool" :content "wrote ok"} (nth messages 3))))

    (it "uses empty string when response has no assistant content"
      (let [response {:content ""}
            request  {:messages []}
            messages (sut/followup-messages request response
                                            [{:id "tc1" :name "x" :arguments {}}]
                                            ["r"])]
        (should= "" (:content (nth messages 0))))))

  (describe "chat-stream"

    (it "folds every chunk's text, not just the last one"
      ;; This spec used to assert (:content result) was "!" — the last chunk
      ;; alone — which pinned the bug it should have caught (isaac-ncrz).
      (let [chunks (atom [])]
        (with-redefs [llm-http/post-ndjson-stream! (fn [_ _ _ on-chunk & _]
                                                     (let [events [{:message {:content "Hi"} :done false}
                                                                   {:message {:content "!"} :done true
                                                                    :prompt_eval_count 10 :eval_count 5}]]
                                                       (doseq [e events] (on-chunk e))
                                                       (last events)))]
          (let [result (sut/chat-stream {:model "test" :messages []}
                         (fn [c] (swap! chunks conj c))
                         "ollama" {})]
            (should= "Hi!" (:content result))
            (should= 2 (count @chunks))
            (should= 10 (get-in result [:usage :prompt-tokens]))
            (should= 5 (get-in result [:usage :output-tokens]))))))

    (it "keeps a tool call that arrives before the closing chunk"
      ;; Ollama puts the whole tool call in a chunk of its own with done=false;
      ;; the closing chunk carries no tool_calls at all. Keeping only the last
      ;; chunk lost every tool call and the turn ended empty (isaac-ncrz).
      (with-redefs [llm-http/post-ndjson-stream!
                    (fn [_ _ _ on-chunk & _]
                      (let [events [{:message {:role "assistant" :content ""
                                               :tool_calls [{:id "call_1"
                                                             :function {:index 0
                                                                        :name "count_lines"
                                                                        :arguments {:path "/etc/hosts"}}}]}
                                     :done false}
                                    {:message {:role "assistant" :content ""}
                                     :done true :done_reason "stop"
                                     :prompt_eval_count 293 :eval_count 23}]]
                        (doseq [e events] (on-chunk e))
                        (last events)))]
        (let [result (sut/chat-stream {:model "test" :messages []} identity "ollama" {})]
          (should= 1 (count (:tool-calls result)))
          (should= "count_lines" (:name (first (:tool-calls result))))
          (should= {:path "/etc/hosts"} (:arguments (first (:tool-calls result))))
          (should= "call_1" (:id (first (:tool-calls result))))
          (should= :tool-use (:stop-reason result))
          (should= 293 (get-in result [:usage :prompt-tokens])))))

    (it "keeps several tool calls spread across chunks"
      (with-redefs [llm-http/post-ndjson-stream!
                    (fn [_ _ _ on-chunk & _]
                      (let [events [{:message {:tool_calls [{:id "a" :function {:name "read" :arguments {}}}]} :done false}
                                    {:message {:tool_calls [{:id "b" :function {:name "write" :arguments {}}}]} :done false}
                                    {:message {:content ""} :done true :done_reason "stop"
                                     :prompt_eval_count 1 :eval_count 1}]]
                        (doseq [e events] (on-chunk e))
                        (last events)))]
        (let [result (sut/chat-stream {:model "test" :messages []} identity "ollama" {})]
          (should= ["read" "write"] (mapv :name (:tool-calls result))))))

    (it "returns error on connection failure"
      (with-redefs [llm-http/post-ndjson-stream! (fn [_ _ _ _ & _] {:error :connection-refused})]
        (let [result (sut/chat-stream {:model "test" :messages []} identity "ollama" {})]
          (should= :connection-refused (:error result))))))

  (describe "schema conformance"

    (it "chat returns a value conforming to provider/response"
      (with-redefs [http/post (fn [_ _] (mock-response {:model   "test"
                                                         :message {:role "assistant" :content "Done"}
                                                         :done    true
                                                         :prompt_eval_count 10
                                                         :eval_count 5}))]
        (let [result (sut/chat {:model "test" :messages []} "ollama" {})]
          (should-not (api/error? result))
          (should-not-throw (api/validate-response result)))))

    (it "chat with tool_calls returns a value conforming to provider/response"
      (with-redefs [http/post (fn [_ _] (mock-response {:model   "test"
                                                         :message {:role       "assistant"
                                                                   :content    ""
                                                                   :tool_calls [{:function {:name "read" :arguments {:path "x"}}}]}
                                                         :prompt_eval_count 10
                                                         :eval_count 5}))]
        (let [result (sut/chat {:model "test" :messages []} "ollama" {})]
          (should-not (api/error? result))
          (should-not-throw (api/validate-response result)))))

    (it "connection-refused errors conform to provider/error-response"
      (with-redefs [http/post (fn [_ _] (throw (java.net.ConnectException.)))]
        (let [result (sut/chat {:model "test" :messages []} "ollama" {})]
          (should (api/error? result))
          (should-not-throw (schema/conform! api/error-response result))))))

  (describe "effort->think"

    (describe ":bool mode (default)"

      (it "effort 0 sends think:false"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 0 :messages []} "ollama" {}))
          (should= false (:think @captured))))

      (it "effort 1 sends think:true"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 1 :messages []} "ollama" {}))
          (should= true (:think @captured))))

      (it "effort 7 sends think:true"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 7 :messages []} "ollama" {}))
          (should= true (:think @captured)))))

    (describe ":levels mode"

      (it "effort 0 omits think field"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 0 :messages []} "ollama" {:think-mode :levels}))
          (should-not (contains? @captured :think))))

      (it "effort 2 sends think:low"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 2 :messages []} "ollama" {:think-mode :levels}))
          (should= "low" (:think @captured))))

      (it "effort 5 sends think:medium"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 5 :messages []} "ollama" {:think-mode :levels}))
          (should= "medium" (:think @captured))))

      (it "effort 9 sends think:high"
        (let [captured (atom nil)]
          (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
            (sut/chat {:model "test" :effort 9 :messages []} "ollama" {:think-mode :levels}))
          (should= "high" (:think @captured)))))

    (it "strips :effort from the outbound body"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "test" :effort 5 :messages []} "ollama" {}))
        (should-not (contains? @captured :effort))))

    (it "omits think field when :effort absent"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_ _ body & _] (reset! captured body) {})]
          (sut/chat {:model "test" :messages []} "ollama" {}))
        (should-not (contains? @captured :think))))))
