(ns isaac.llm.tool-loop-spec
  (:require
    [isaac.llm.tool-loop :as sut]
    [speclj.core :refer :all]))

(defn- queue-chat
  "Build a chat-fn that returns successive responses from the given queue.
   After the queue is drained, throws to fail loud."
  [responses]
  (let [remaining (atom responses)]
    (fn [_request]
      (let [resp (first @remaining)]
        (when-not resp
          (throw (ex-info "queue exhausted" {})))
        (swap! remaining rest)
        resp))))

(defn- recording-followup
  "Followup-fn that records each call into the given atom and returns a
   new :messages vector by appending a marker."
  [calls]
  (fn [request response tool-calls tool-results]
    (swap! calls conj {:request       request
                       :response      response
                       :tool-calls    tool-calls
                       :tool-results  tool-results})
    (conj (vec (:messages request))
          {:role "assistant" :marker (count @calls)})))

(describe "tool-loop/run"

  (it "returns immediately when the first response has no tool-calls"
    (let [request      {:messages []}
          response     {:message {:role "assistant" :content "done"}
                        :usage   {:input-tokens 5 :output-tokens 2}}
          chat-calls   (atom [])
          followups    (atom [])
          tool-runs    (atom [])
          chat-fn      (fn [req]
                         (swap! chat-calls conj req)
                         response)
          followup-fn  (recording-followup followups)
          tool-fn      (fn [name args]
                         (swap! tool-runs conj [name args])
                         "should-not-run")
          result       (sut/run chat-fn followup-fn request tool-fn)]
      (should= [request] @chat-calls)
      (should= [] @followups)
      (should= [] @tool-runs)
      (should= response (:response result))
      (should= [] (:tool-calls result))
      (should= {:input-tokens 5 :output-tokens 2 :cache-read 0 :cache-write 0}
               (:token-counts result))
      (should= false (:loop-request? result))))

  (it "executes tools and recurs when the response has tool-calls"
    (let [chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "read" :arguments {:path "x"}}]
                          :usage      {:input-tokens 10 :output-tokens 5}}
                         {:message {:role "assistant" :content "done"}
                          :usage   {:input-tokens 7 :output-tokens 3}}])
          tool-runs   (atom [])
          tool-fn     (fn [name args]
                        (swap! tool-runs conj {:name name :args args})
                        (str "ran " name))
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn)]
      (should= 1 (count (:tool-calls result)))
      (should= "read" (:name (first (:tool-calls result))))
      (should= [{:name "read" :args {:path "x"}}] @tool-runs)
      (should= 17 (:input-tokens (:token-counts result)))
      (should= 8 (:output-tokens (:token-counts result)))
      (should= false (:loop-request? result))))

  (it "passes the followup-fn the prior request, response, tool-calls, and results"
    (let [calls       (atom [])
          chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "read" :arguments {:p "x"}}]
                          :usage      {:input-tokens 1 :output-tokens 1}}
                         {:message {:content "done"} :usage {}}])
          tool-fn     (fn [_ _] "result")
          followup-fn (recording-followup calls)]
      (sut/run chat-fn followup-fn {:messages [{:role "user" :content "go"}]} tool-fn)
      (should= 1 (count @calls))
      (let [call (first @calls)]
        (should= [{:role "user" :content "go"}] (:messages (:request call)))
        (should= [{:id "tc1" :name "read" :arguments {:p "x"}}] (:tool-calls call))
        (should= ["result"] (:tool-results call)))))

  (it "stops at max-loops with loop-request? true and unrun tail tools"
    (let [chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "a" :arguments {}}]
                          :usage      {:input-tokens 1 :output-tokens 1}}
                         ;; Second response also has tool-calls — but max-loops=1 stops us
                         {:tool-calls [{:id "tc2" :name "b" :arguments {}}]
                          :usage      {:input-tokens 1 :output-tokens 1}}])
          tool-runs   (atom [])
          tool-fn     (fn [name _]
                        (swap! tool-runs conj name)
                        "ok")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn {:max-loops 1})]
      ;; Only the first iteration's tool ran; the second response's tools were not executed
      (should= ["a"] @tool-runs)
      (should= 1 (count (:tool-calls result)))
      (should= true (:loop-request? result))))

  (it "stops at max-loops zero before invoking any tools"
    (let [chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "a" :arguments {}}]
                          :usage      {:input-tokens 5 :output-tokens 2}}])
          tool-runs   (atom [])
          tool-fn     (fn [_ _]
                        (swap! tool-runs conj :ran)
                        "ok")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn {:max-loops 0})]
      (should= [] @tool-runs)
      (should= [] (:tool-calls result))
      (should= true (:loop-request? result))))

  (it "returns the error response immediately"
    (let [chat-fn     (queue-chat [{:error :connection-refused}])
          tool-fn     (fn [_ _] "should-not-run")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn)]
      (should= :connection-refused (:error result))))

  (it "extracts tool-calls from [:message :tool_calls] when not at top-level"
    ;; Some providers (ollama, grover) put tool_calls inside :message rather than
    ;; at the top level. The loop should find them either way.
    (let [chat-fn     (queue-chat
                        [{:message {:role "assistant" :content ""
                                    :tool_calls [{:function {:name "read" :arguments {:p "x"}}}]}}
                         {:message {:role "assistant" :content "done"}}])
          tool-runs   (atom [])
          tool-fn     (fn [name args]
                        (swap! tool-runs conj {:name name :args args})
                        "ok")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn)]
      (should= 1 (count @tool-runs))
      (should= "read" (:name (first @tool-runs)))
      (should= {:p "x"} (:args (first @tool-runs)))
      (should= 1 (count (:tool-calls result)))))

  (it "stops before the next chat call when cancelled? returns true after a tool run"
    (let [cancelled?* (atom false)
          chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "a" :arguments {}}]
                          :usage      {:input-tokens 5 :output-tokens 2}}
                         {:message {:content "Should not appear"}
                          :usage   {:input-tokens 3 :output-tokens 1}}])
          tool-runs   (atom [])
          tool-fn     (fn [_ _]
                        (swap! tool-runs conj :ran)
                        (reset! cancelled?* true)
                        "done")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn
                               {:cancelled? #(deref cancelled?*)})]
      (should= [:ran] @tool-runs)
      (should= true (:cancelled? result))
      (should= 1 (count (:tool-calls result)))))

  (it "skips the very first chat call when cancelled? is true from the start"
    (let [chat-calls (atom [])
          chat-fn    (fn [req] (swap! chat-calls conj req) {:message {:content "nope"}})
          result     (sut/run chat-fn (recording-followup (atom [])) {:messages []} (fn [_ _] "x")
                             {:cancelled? (constantly true)})]
      (should= [] @chat-calls)
      (should= true (:cancelled? result))))

  (it "accumulates token counts across iterations"
    (let [chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "a" :arguments {}}]
                          :usage      {:input-tokens 10 :output-tokens 5}}
                         {:tool-calls [{:id "tc2" :name "b" :arguments {}}]
                          :usage      {:input-tokens 7 :output-tokens 3}}
                         {:message {:content "done"}
                          :usage   {:input-tokens 4 :output-tokens 1}}])
          tool-fn     (fn [_ _] "ok")
          followup-fn (recording-followup (atom []))
           result      (sut/run chat-fn followup-fn {:messages []} tool-fn)]
       (should= 21 (:input-tokens (:token-counts result)))
       (should= 9 (:output-tokens (:token-counts result)))))

  (it "accumulates cache counts from raw provider usage aliases"
    (let [chat-fn     (queue-chat
                        [{:tool-calls [{:id "tc1" :name "a" :arguments {}}]
                          :usage      {:input_tokens                 10
                                       :output_tokens                5
                                       :cache_creation_input_tokens 11
                                       :input_tokens_details         {:cached_tokens 7}}}
                         {:message {:content "done"}
                          :usage   {:input_tokens         4
                                    :output_tokens        1
                                    :input_tokens_details {:cached_tokens 2}}}])
          tool-fn     (fn [_ _] "ok")
          followup-fn (recording-followup (atom []))
          result      (sut/run chat-fn followup-fn {:messages []} tool-fn)]
      (should= {:input-tokens  14
                :output-tokens 6
                :cache-read    9
                :cache-write   11}
               (:token-counts result)))))
