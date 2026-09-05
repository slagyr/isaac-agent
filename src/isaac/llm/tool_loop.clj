(ns isaac.llm.tool-loop
  "Provider-agnostic tool-call loop. Drives the LLM-call → execute-tools →
   followup-LLM-call cycle once for all providers. Template Method shape:
   `run` is the algorithm; `chat-fn` and `followup-fn` are the hooks."
  (:require
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.logger :as log])
  (:import (clojure.lang ExceptionInfo)))

(def default-max-loops 500)
(def default-max-parallel-tools 4)

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

(defn- prepare-tool-execution [tc tool-fn prepare-tool-call]
  (let [prepared (if prepare-tool-call
                   (prepare-tool-call tc)
                   {:run #(tool-fn (:name tc) (:arguments tc))})]
    (cond
      (fn? prepared) {:run prepared :cancel-queued (fn [] nil)}
      (map? prepared) (merge {:cancel-queued (fn [] nil)} prepared)
      :else (throw (ex-info "invalid prepared tool call" {:tool-call tc :prepared prepared})))))

(defn- cancel-pending! [executions]
  (run! (fn [{:keys [cancel-queued]}]
          (when cancel-queued
            (cancel-queued)))
        executions))

(defn- execute-tool-batch [tool-calls tool-fn {:keys [cancelled? max-parallel-tools prepare-tool-call]}]
  (let [parallelism (max 1 (long (or max-parallel-tools default-max-parallel-tools)))
        executions  (mapv #(prepare-tool-execution % tool-fn prepare-tool-call) tool-calls)
        next-index   (atom -1)
        results      (atom (vec (repeat (count executions) nil)))
        cancelled*   (atom false)
        cancel-all!  #(when (compare-and-set! cancelled* false true)
                        (cancel-pending! executions))
        worker       (bound-fn []
                       (loop []
                         (when (or @cancelled* (cancelled?))
                           (cancel-all!))
                         (when-not @cancelled*
                           (let [idx (swap! next-index inc)]
                             (when (< idx (count executions))
                               (let [{:keys [run]} (nth executions idx)]
                                 (try
                                   (swap! results assoc idx (run))
                                   (catch ExceptionInfo e
                                     (if (= :cancelled (:type (ex-data e)))
                                       (cancel-all!)
                                       (throw e))))
                                 (recur)))))))
        workers      (mapv (fn [_] (future (worker)))
                           (range (min parallelism (count executions))))]
    (run! deref workers)
    {:results    (->> @results (remove nil?) vec)
     :cancelled? @cancelled*}))

(defonce ^:private provider-driver* (atom nil))

(defn install-provider-driver!
  "Install a provider-driven loop implementation. Test support (and later
   claude-cli) registers a driver that receives the same hooks as `run`."
  [driver]
  (reset! provider-driver* driver))

(defn clear-provider-driver! []
  (reset! provider-driver* nil))

(defn- drives-tool-loop? [api-impl]
  (boolean (and api-impl (:drives-tool-loop? (api/config api-impl)))))

(defn -run-default
  "Isaac's built-in tool-call loop. Byte-for-byte the historical `run` body."
  [chat-fn followup-fn request tool-fn {:keys [max-loops cancelled? after-tools max-parallel-tools on-cycle prepare-tool-call]
                                        :or   {max-loops          default-max-loops
                                               cancelled?         (constantly false)
                                               after-tools        identity
                                               max-parallel-tools default-max-parallel-tools
                                               on-cycle           nil
                                               prepare-tool-call  nil}}]
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
      (let [cycle-n  (inc loops)
            call-req (with-chain req chain-id)
            _        (when on-cycle (on-cycle :start cycle-n call-req))
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
                (do
                  (when on-cycle (on-cycle :end cycle-n response))
                  (let [{:keys [results cancelled?]}
                        (execute-tool-batch tool-calls tool-fn {:cancelled?         cancelled?
                                                                :max-parallel-tools max-parallel-tools
                                                                :prepare-tool-call  prepare-tool-call})]
                    (if cancelled?
                      {:response     nil
                       :tool-calls   (into all-tools tool-calls)
                       :token-counts new-tokens
                       :cancelled?   true}
                      (let [new-messages (followup-fn req response tool-calls results)
                            next-req     (after-tools (assoc req :messages new-messages))]
                        (if (or (:error next-req) (:unavailable? next-req))
                          next-req
                          (recur next-req
                                 (into all-tools tool-calls)
                                 new-tokens
                                 (inc loops)
                                 next-chain
                                 (assoc full-context :messages (:messages next-req))))))))
                (do
                  (when on-cycle (on-cycle :end cycle-n response))
                  {:response      response
                   :tool-calls    all-tools
                   :token-counts  new-tokens
                   :loop-request? (boolean (and (seq tool-calls) (not budget-left?)))})))))))))

(defn run
  "Drive one tool-call loop using the supplied hooks.

    chat-fn          (fn [request] -> response) — one LLM call per cycle.
                     Caller chooses streaming vs non-streaming when wiring this.
    followup-fn      (fn [request response tool-calls tool-results] -> messages)
                     — provider-specific format for the next cycle's :messages.
    request          initial chat request (with :messages, :tools, etc.)
    tool-fn          (fn [tool-name arguments] -> result-string) — runs one tool.

    Options:
      :max-loops          budget for tool cycles (default 500)
      :max-parallel-tools max concurrent tool calls within one response batch (default 4)
      :after-tools        optional (fn [request] -> request-or-unavailable)
                          after tools + followup, before the next chat-fn.
                          A returned :unavailable? / :error map stops the loop.
      :api                optional Api instance; when its config has
                          :drives-tool-loop? true, `run` dispatches to the
                          installed provider driver instead of the default loop.

    Returns on success:
      {:response       last LLM response
       :tool-calls     [executed-tool-call-maps]
       :token-counts   accumulated usage
       :loop-request?  true when the budget was exhausted with tools still pending}

    Returns on error: the error response from chat-fn."
  [chat-fn followup-fn request tool-fn & [opts]]
  (let [opts      (or opts {})
        api-impl  (:api opts)
        driven?   (drives-tool-loop? api-impl)
        driver-kw (if driven? :provider :default)]
    (log/info :turn/loop-driver
              :provider (if api-impl (api/display-name api-impl) (:provider request))
              :driver driver-kw)
    (if (and driven? @provider-driver*)
      (@provider-driver* chat-fn followup-fn request tool-fn opts)
      (-run-default chat-fn followup-fn request tool-fn opts))))
