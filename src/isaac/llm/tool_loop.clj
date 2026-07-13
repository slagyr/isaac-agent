(ns isaac.llm.tool-loop
  "Provider-agnostic tool-call loop. Drives the LLM-call → execute-tools →
   followup-LLM-call cycle once for all providers. Template Method shape:
   `run` is the algorithm; `chat-fn` and `followup-fn` are the hooks."
  (:require
    [clojure.string :as str]
    [isaac.logger :as log]))

(def default-max-loops 100)

(defn- response-tool-calls [response]
  (or (:tool-calls response)
      (when-let [raw (get-in response [:message :tool_calls])]
        (mapv (fn [tc]
                {:id        (or (:id tc) (str (java.util.UUID/randomUUID)))
                 :name      (or (:name tc) (get-in tc [:function :name]))
                 :arguments (or (:arguments tc) (get-in tc [:function :arguments]))
                 :raw       tc})
              raw))))

(defn- response-tokens [response]
  (let [usage (merge (or (get-in response [:response :usage]) {})
                     (or (:usage response) {}))]
    {:input-tokens  (or (:input-tokens usage) (:input_tokens usage) (:prompt_eval_count response) 0)
     :output-tokens (or (:output-tokens usage) (:output_tokens usage) (:eval_count response) 0)
     :cache-read    (or (:cache-read usage)
                        (:cached-tokens usage)
                        (get-in usage [:input_tokens_details :cached_tokens])
                        0)
     :cache-write   (or (:cache-write usage)
                        (:cache_creation_input_tokens usage)
                        0)}))

(defn- response-id [response]
  (or (:response-id response)
      (get-in response [:response :id])
      (:id response)))

(defn- previous-response-not-found? [response]
  (and (:error response)
       (let [msg (str (or (:message response) ""))]
         (or (str/includes? msg "previous_response_id")
             (re-find #"(?i)response with id .* not found" msg)
             (and (= 404 (:status response))
                  (str/includes? (str/lower-case msg) "not found"))))))

(defn- with-chain [req previous-id]
  (if previous-id
    (assoc req :previous_response_id previous-id)
    (dissoc req :previous_response_id)))

(defn run
  "Drive one tool-call loop using the supplied hooks.

   chat-fn          (fn [request] -> response) — one LLM call per cycle.
                    Caller chooses streaming vs non-streaming when wiring this.
   followup-fn      (fn [request response tool-calls tool-results] -> messages)
                    — provider-specific format for the next cycle's :messages.
   request          initial chat request (with :messages, :tools, etc.)
   tool-fn          (fn [tool-name arguments] -> result-string) — runs one tool.

   Options:
     :max-loops     budget for tool cycles (default 100)

   Returns on success:
     {:response       last LLM response
      :tool-calls     [executed-tool-call-maps]
      :token-counts   accumulated usage
      :loop-request?  true when the budget was exhausted with tools still pending}

   Returns on error: the error response from chat-fn."
  [chat-fn followup-fn request tool-fn & [{:keys [max-loops cancelled?]
                                            :or   {max-loops  default-max-loops
                                                   cancelled? (constantly false)}}]]
  (loop [req          (dissoc request :previous_response_id)
         all-tools    []
         token-counts {:input-tokens 0 :output-tokens 0 :cache-read 0 :cache-write 0}
         loops        0
         chain-id     nil
         full-context request]
    (if (cancelled?)
      {:response     nil
       :tool-calls   all-tools
       :token-counts token-counts
       :cancelled?   true}
      (let [call-req (with-chain req chain-id)
            response (chat-fn call-req)]
        (if (previous-response-not-found? response)
          (do
            (log/info :chat/state-reset
                      :provider (or (:provider response)
                                    (:provider call-req)
                                    (get-in call-req [:provider])
                                    "chatgpt")
                      :previous_response_id chain-id
                      :status (:status response))
            ;; Retry with the full conversation context (including tool results
            ;; already on full-context :messages) and start a fresh chain.
            (recur (dissoc full-context :previous_response_id)
                   all-tools
                   token-counts
                   loops
                   nil
                   full-context))
          (if (or (:error response) (:unavailable? response))
            response
            (let [tool-calls   (response-tool-calls response)
                  new-tokens   (merge-with + token-counts (response-tokens response))
                  budget-left? (< loops max-loops)
                  next-chain   (or (response-id response) chain-id)]
              (if (and (seq tool-calls) budget-left?)
                (let [tool-results (mapv (fn [tc] (tool-fn (:name tc) (:arguments tc)))
                                         tool-calls)
                      new-messages (followup-fn req response tool-calls tool-results)
                      next-req     (assoc req :messages new-messages)
                      next-full    (assoc full-context :messages new-messages)]
                  (recur next-req
                         (into all-tools tool-calls)
                         new-tokens
                         (inc loops)
                         next-chain
                         next-full))
                {:response      response
                 :tool-calls    all-tools
                 :token-counts  new-tokens
                 :loop-request? (boolean (and (seq tool-calls) (not budget-left?)))}))))))))
