(ns isaac.drive.turn
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]
    [isaac.attention :as attention]
    [isaac.bridge.cancellation :as bridge]
    [isaac.bridge.suspend :as suspend]
    [isaac.comm.null :as null-comm]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.drive.dispatch :as dispatch]
    [isaac.drive.observer :as observer]
    [isaac.drive.provider-wall :as provider-wall]
    [isaac.drive.weather :as weather]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.compaction :as compaction]
    [isaac.session.context :as session-ctx]
    [isaac.session.policy :as policy]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory]
    [isaac.tool.names :as names]
    [isaac.tool.registry :as tool-registry]
    [isaac.turnstile :as turnstile])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.charset StandardCharsets)
           (java.time Instant)))

;; region ----- Error Formatting -----

(defn- body-error-message [result]
  (let [body       (:body result)
        body-error (:error body)]
    (cond
      (map? body-error) (str (or (:type body-error) (name (:error result)))
                             ": "
                             (or (:message body-error) body-error))
      (string? body-error) body-error
      (map? body) (pr-str body))))

(defn error-message [result]
  (or (when (:unavailable? result)
        (str "provider unavailable"
             (when-let [r (:reason result)] (str " (" (name r) ")"))
             (when-let [m (:message result)] (str ": " m))))
      (:message result)
      (body-error-message result)
      (when (:status result)
        (str "HTTP " (:status result) " " (name (:error result))
             (when-let [body (:body result)]
               (str " - " (pr-str body)))))
      (let [error (:error result)]
        (if (keyword? error) (name error) (str error)))))

;; endregion ^^^^^ Error Formatting ^^^^^

;; region ----- Token Accounting -----

(defn- request-usage [result]
  (or (get-in result [:response :usage]) (:usage result)))

(defn- turn-usage [result]
  (or (:usage result) {:requests 0 :prompt-tokens 0 :output-tokens 0}))

(defn extract-tokens [result]
  (let [usage (turn-usage result)]
    {:input-tokens  (:prompt-tokens usage 0)
     :output-tokens (:output-tokens usage 0)
     :cache-read    (:cache-read-tokens usage)
     :cache-write   (:cache-write-tokens usage)}))

(defn normalize-usage [result]
  (let [usage (turn-usage result)]
    (cond-> {:prompt-tokens (:prompt-tokens usage 0)
             :output-tokens (:output-tokens usage 0)
             :total-tokens (+ (:prompt-tokens usage 0) (:output-tokens usage 0))}
      (some? (:reasoning-tokens usage)) (assoc :reasoning-tokens (:reasoning-tokens usage))
      (some? (:cache-read-tokens usage)) (assoc :cache-read-tokens (:cache-read-tokens usage))
      (some? (:cache-write-tokens usage)) (assoc :cache-write-tokens (:cache-write-tokens usage)))))

;; endregion ^^^^^ Token Accounting ^^^^^

;; region ----- Response Persistence -----

(defonce in-flight-compactions (atom {}))

(defn- normalize-ctx [ctx-or-root]
  (merge (nexus/necho)
         (if (map? ctx-or-root) ctx-or-root {:root ctx-or-root})))

(defn clear-async-compactions! []
  (reset! in-flight-compactions {}))

(defn- active-compaction-state [session-key]
  (get @in-flight-compactions session-key))

(defn async-compaction-in-flight? [session-key]
  (boolean (active-compaction-state session-key)))

(defn await-async-compaction! [session-key]
  (when-let [state (get @in-flight-compactions session-key)]
    (when-let [splice-ready (:splice-ready state)]
      (deliver splice-ready true))
    (let [future* (:future state)
          result  (deref future* 30000 ::timeout)]
      (when (= ::timeout result)
        (throw (ex-info "async compaction did not complete within 30 seconds" {:session session-key})))
      (swap! in-flight-compactions dissoc session-key)
      result)))

(defn- with-transcript-lock [session-key f]
  (if-let [lock (:lock (active-compaction-state session-key))]
    (locking lock (f))
    (f)))

(defn- session-policy [ctx]
  (or (when-let [charge (:charge ctx)]
        (or (:session-policy charge)
            (policy/for-request charge)))
      (when-let [ss (or (:session-store ctx) (nexus/get-in [:sessions :store]))]
        (policy/wrap ss))))

(defn- append-message! [ctx session-key message]
  (with-transcript-lock session-key #(policy/append-message! (session-policy ctx) session-key message)))

(defn- append-error! [ctx session-key error-entry]
  (with-transcript-lock session-key #(policy/append-error! (session-policy ctx) session-key error-entry)))

(defn- append-reckoning! [ctx session-key text]
  (when (and ctx session-key (seq (str text)))
    (with-transcript-lock session-key
      #(policy/append-reckoning! (session-policy ctx) session-key {:text text}))))

(defn- append-checkpoint! [ctx session-key cycle]
  (when (and ctx session-key cycle)
    (with-transcript-lock session-key
      #(policy/append-checkpoint! (session-policy ctx) session-key {:cycle cycle}))))

(defn- elapsed-ms [start-ns]
  (/ (- (System/nanoTime) start-ns) 1000000.0))

(defn- tool-call-content [tc]
  {:type      "toolCall"
   :id        (:id tc)
   :name      (or (:name tc) (get-in tc [:function :name]))
   :arguments (or (:arguments tc) (get-in tc [:function :arguments]))})

(defn- persist-tool-batch!
  "Write one assistant entry whose content is every toolCall in model order.
   Crash mid-batch leaves this entry and no results until the batch flush."
  [ctx session-key tcs]
  (when (and ctx session-key (seq tcs))
    (let [start-ns (System/nanoTime)]
      (append-message! ctx session-key
                       {:role "assistant" :content (mapv tool-call-content tcs)})
      (log/debug :tool/call-persisted :elapsed-ms (elapsed-ms start-ns) :count (count tcs)))))

(defn- persist-tool-call!
  "Legacy single-call write. Prefer persist-tool-batch! for a response batch."
  [ctx session-key tc]
  (persist-tool-batch! ctx session-key (when tc [tc])))

(defn- persist-tool-result!
  "Write the toolResult entry as soon as the tool returns."
  [ctx session-key tc result]
  (when (and ctx session-key tc)
    (let [start-ns (System/nanoTime)
          error?   (boolean (or (and (string? result) (str/starts-with? result "Error:"))
                                (and (map? result) (:isError result))))]
      (append-message! ctx session-key
                       (cond-> {:role "toolResult" :id (:id tc) :content result}
                               error? (assoc :isError true)))
      (log/debug :tool/result-persisted :elapsed-ms (elapsed-ms start-ns)))))

(defn run-tool-calls!
  "Legacy dump of [tool-call result] pairs. Mid-loop persist (isaac-l7lv)
   writes each pair as it happens; this remains for callers that still
   accumulate pairs and flush once. Prefer persist-tool-call! /
   persist-tool-result!."
  ([session-key tool-results]
   (run-tool-calls! {} session-key tool-results))
  ([ctx-or-root session-key tool-results]
   (let [ctx (normalize-ctx ctx-or-root)]
     (doseq [[tc result] tool-results]
       (persist-tool-call! ctx session-key tc)
       (persist-tool-result! ctx session-key tc result)))))

(defn- normalized-error [err]
  (if (string? err) (keyword err) err))

(defn- persisted-error [err]
  (let [normalized (normalized-error err)]
    (if (keyword? normalized) (str normalized) normalized)))

(defn- store-error! [ctx session-key result {:keys [model provider]}]
  (try
    (append-error! ctx session-key
                   {:content  (error-message result)
                    :error    (persisted-error (:error result))
                    :model    model
                    :provider provider})
    (catch Exception e
      (log/warn :chat/error-not-stored
                :session session-key
                :provider provider
                :error (.getMessage e)))))

(defn- log-response-failed! [session-key provider result]
  (log/error :chat/response-failed
             :session session-key
             :provider provider
             :error (:error result)
             :message (error-message result)))

(defn- report-error! [ctx session-key provider result opts]
  (log-response-failed! session-key provider result)
  (store-error! ctx session-key result opts)
  result)

(defn- response-model [result model]
  (or (get-in result [:response :model]) model))

(defn- normalized-provider-prompt-tokens [ctx session-key result]
  (let [context-window (get-in ctx [:charge :context-window])
        raw-prompt     (or (:prompt-tokens (request-usage result)) 0)]
    (if (and (pos? (or context-window 0)) (> raw-prompt context-window))
      (do
        (log/warn :session/stamp-implausible
                  :session session-key
                  :prompt-tokens raw-prompt
                  :context-window context-window)
        context-window)
      raw-prompt)))

(defn- provider-stateful? [ctx]
  (boolean (get (some-> (or (:provider ctx) (:provider (:charge ctx)))
                        api/config)
                :stateful)))

(defn- replayable-output-tokens [ctx result]
  (let [usage     (request-usage result)
        output    (or (:output-tokens usage) 0)
        reasoning (:reasoning-tokens usage)]
    (if (and reasoning (not (provider-stateful? ctx)))
      (max 0 (- output reasoning))
      output)))

(defn- last-transcript-id [ctx session-key]
  (when-let [sess (session-policy ctx)]
    (:id (last (or (policy/get-transcript sess session-key) [])))))

(defn- stamp-provider-prompt! [ctx session-key result]
  (let [sess          (session-policy ctx)
        prompt-tokens (normalized-provider-prompt-tokens ctx session-key result)
        output-tokens (replayable-output-tokens ctx result)
        cursor        (last-transcript-id ctx session-key)]
    (when (pos? prompt-tokens)
      (policy/update-session! sess session-key
                              (cond-> {:last-input-tokens  prompt-tokens
                                       :last-output-tokens output-tokens}
                                cursor (assoc :tally-after-id cursor))))
    prompt-tokens))

(defn- store-response! [ctx session-key result {:keys [model provider]}]
  (let [sess              (session-policy ctx)
        turn-tokens       (extract-tokens result)
        usage             (normalize-usage result)
        resolved-model    (response-model result model)
        reasoning         (some-> (get-in result [:response :reasoning])
                                  (assoc :effort (get-in ctx [:charge :effort])))
        stop-reason       (get-in result [:response :stop-reason])
        session-entry     (or (policy/get-session sess session-key) {})
        turn-input-tokens (:input-tokens turn-tokens 0)
        prompt-tokens     (normalized-provider-prompt-tokens ctx session-key result)
        output-tokens     (:output-tokens turn-tokens 0)
        cache-read        (:cache-read turn-tokens)
        cache-write       (:cache-write turn-tokens)]
    (log/debug :session/message-stored
               :session session-key
               :model resolved-model
               :tokens (select-keys turn-tokens [:input-tokens :output-tokens]))
    (append-message! ctx session-key
                     (cond-> {:role     "assistant"
                              :content  (get-in result [:response :content])
                              :model    resolved-model
                              :provider provider}
                             usage (assoc :usage usage)
                             stop-reason (assoc :stopReason stop-reason)
                             reasoning (assoc :reasoning reasoning)))
    (policy/update-session! sess session-key
                           (cond-> {:input-tokens       (+ (or (:input-tokens session-entry) 0) turn-input-tokens)
                                    :turn-input-tokens  turn-input-tokens
                                    ;; A response that reports no prompt tokens — a refusal, or a
                                    ;; provider that does not count — must not overwrite the tally
                                    ;; with zero: that is how a full session comes to read as empty
                                    ;; (isaac-166j).
                                    :last-input-tokens  (if (pos? prompt-tokens)
                                                          prompt-tokens
                                                          (or (:last-input-tokens session-entry) 0))
                                    :last-output-tokens (replayable-output-tokens ctx result)
                                    :output-tokens      (+ (or (:output-tokens session-entry) 0) output-tokens)
                                    :total-tokens       (+ (+ (or (:input-tokens session-entry) 0) turn-input-tokens)
                                                           (+ (or (:output-tokens session-entry) 0) output-tokens))}
                                   cache-read (assoc :cache-read (+ (or (:cache-read session-entry) 0) cache-read))
                                   cache-write (assoc :cache-write (+ (or (:cache-write session-entry) 0) cache-write))))
    nil))

(defn- process-response* [ctx session-key result {:keys [model provider]}]
  (if (:error result)
    (report-error! ctx session-key provider result {:model model :provider provider})
    (store-response! ctx session-key result {:model model :provider provider})))

(defn process-response!
  ([session-key result {:keys [model provider]}]
   (process-response* (nexus/necho) session-key result {:model model :provider provider}))
  ([ctx-or-root session-key result opts]
   (process-response* (normalize-ctx ctx-or-root)
                      session-key result opts)))

;; endregion ^^^^^ Response Persistence ^^^^^

;; region ----- Streaming -----

(defn- chunk-content [chunk]
  (:text-delta chunk))

(defn- prompt-too-long? [result]
  (provider-wall/prompt-too-long? result))

(defn- chunk-reasoning [chunk]
  (:reasoning-delta chunk))

(defn stream-response! [p request on-chunk]
  (let [full-content (atom "")
        result       (dispatch/dispatch-chat-stream p request
                                                    (fn [chunk]
                                                      (when-let [reasoning (chunk-reasoning chunk)]
                                                        (on-chunk {:reasoning reasoning}))
                                                      (when-let [piece (chunk-content chunk)]
                                                        (when (seq piece)
                                                          (swap! full-content str piece)
                                                          (on-chunk piece)))))]
    (cond
      (:error result)
      result

      (prompt-too-long? result)
      result

      :else
      (let [content (or (not-empty @full-content) (:content result) "")]
        {:content content :response (assoc result :content content)}))))


(defn- emit-response-content! [channel-impl session-key cycle response]
  (let [content (:content response)
        chunks  (cond
                  (vector? content) (mapv str content)
                  (string? content) [content]
                  (nil? content) []
                  :else [(str content)])]
    (doseq [chunk chunks]
      (comm/on-chatter channel-impl session-key cycle chunk))
    (apply str chunks)))

(defn- stream-supports-tool-calls? [provider-config]
  (let [raw (or (get provider-config :streamSupportsToolCalls)
                (get provider-config :stream-supports-tool-calls))]
    (cond
      (nil? raw) true
      (boolean? raw) raw
      (string? raw) (not (#{"false" "0" "no" "off"} (str/lower-case raw)))
      :else (boolean raw))))

(defn- stream-non-tool-turns? [provider-config]
  (boolean (or (get provider-config :stream-non-tool-turns)
               (get provider-config :streamNonToolTurns))))

(defn- unwrap-stream-result [result]
  (if (and (not (:error result)) (:response result))
    (:response result)
    result))

(defn- chat-fn-for
  "Pick the LLM-call hook the tool-loop should use this turn.

   - Tools requested, streaming supports tools: stream deltas via Comm callbacks.
   - Otherwise (no tools, or tools but streaming not supported): one-shot chat,
     emit content as a single Comm chunk.
   `cycle*` is an atom of {:n n :model ...} updated by the tool-loop on-cycle hook."
  [channel-impl session-key p request cycle*]
  (let [cycle-now #(or (when cycle* @cycle*) {:n 1 :model (:model request)})]
    (cond
      (and (:tools request) (stream-supports-tool-calls? (api/config p)))
      (fn [req] (unwrap-stream-result
                  (stream-response! p req
                                    (fn [chunk]
                                      (if (and (map? chunk) (:reasoning chunk))
                                        (comm/on-reckoning channel-impl session-key (cycle-now) (:reasoning chunk))
                                        (comm/on-chatter channel-impl session-key (cycle-now) chunk))))))

      (and (not (seq (:tools request))) (stream-non-tool-turns? (api/config p)))
      (fn [req] (unwrap-stream-result
                  (stream-response! p req
                                    (fn [chunk]
                                      (if (and (map? chunk) (:reasoning chunk))
                                        (comm/on-reckoning channel-impl session-key (cycle-now) (:reasoning chunk))
                                        (comm/on-chatter channel-impl session-key (cycle-now) chunk))))))

      :else
      (fn [req] (let [result (dispatch/dispatch-chat p req)]
                  (if (or (:error result) (prompt-too-long? result))
                    result
                    (do
                      (when-let [summary (get-in result [:reasoning :summary])]
                        (comm/on-reckoning channel-impl session-key (cycle-now) summary))
                      (let [joined (emit-response-content! channel-impl session-key (cycle-now) result)]
                        (assoc result :content joined)))))))))

(defn- parse-long-or-raw [raw]
  (cond
    (number? raw) (long raw)
    (string? raw) (parse-long raw)
    :else raw))

(defn- crew-cycle [config crew crew-cfg]
  (or (:cycle crew-cfg)
      (get-in config [:crew (keyword crew) :cycle])
      (get-in config [:crew crew :cycle])
      {}))

(defn- resolve-cycle [{:keys [cycle config crew crew-cfg]}]
  (let [defaults (or (get-in config [:defaults :cycle]) {})
        layered  (merge defaults (crew-cycle config crew crew-cfg) (or cycle {}))
        limit    (or (:limit layered)
                     tool-loop/default-max-loops)]
    (assoc layered :limit (parse-long-or-raw limit))))

(defn- resolve-cycle-limit [opts]
  (:limit (resolve-cycle opts)))

(defn- resolve-max-parallel-tools [{:keys [config crew crew-cfg]}]
  (let [raw (or (get-in crew-cfg [:tools :max-parallel])
                (get-in config [:crew (keyword crew) :tools :max-parallel])
                (get-in config [:crew crew :tools :max-parallel])
                (get-in config [:tools :max-parallel])
                tool-loop/default-max-parallel-tools)]
    (parse-long-or-raw raw)))

(def ended-by-values #{:reply :cycle-limit :cancelled :error :context-exhausted :provider-unavailable :suspended})

(defn- classify-ended-by [result]
  (cond
    (and (:unavailable? result)
         (or (= :suspended (:ended-by result))
             (= "suspended" (:stopReason result)))
         (not= :context-exhausted (:reason result)))
    :provider-unavailable

    (contains? ended-by-values (:ended-by result)) (:ended-by result)
    (or (= :cancelled (:error result))
        (:cancelled? result)
        (bridge/cancelled-response? result)
        (= "cancelled" (:stopReason result))) :cancelled
    (= "suspended" (:stopReason result)) :suspended
    (= :empty-terminal-response (:error result)) :error
    (:error result) :error
    (= :context-exhausted (:reason result)) :context-exhausted
    (:unavailable? result) :provider-unavailable
    (:loop-request? result) :cycle-limit
    :else :reply))

(defn- classify-exhaustion [result]
  (or (:exhaustion result)
      (when (:loop-request? result) :stopped)))

(defn- finalize-turn-result [result]
  (let [ended-by    (classify-ended-by result)
        exhaustion  (when (= :cycle-limit ended-by) (classify-exhaustion result))
        error       (when (= :error ended-by) (:error result))
        cycle-limit (:cycle-limit result)]
    (cond-> (assoc result :ended-by ended-by)
            (some? exhaustion) (assoc :exhaustion exhaustion)
            (some? error) (assoc :error error)
            (some? cycle-limit) (assoc :cycle-limit cycle-limit))))

(defn- log-turn-ended! [session-key result]
  (let [ended-by (or (:ended-by result) (classify-ended-by result))]
    (log/info :turn/ended
              (cond-> {:session session-key :ended-by ended-by}
                      (some? (:exhaustion result)) (assoc :exhaustion (:exhaustion result))
                      (some? (:error result)) (assoc :error (:error result))
                      (some? (:cycle-limit result)) (assoc :cycle-limit (:cycle-limit result))))))

(def ^:private loop-exhausted-summary-instruction
  "You have hit the cycle limit. Do not call any more tools. Write a concise assistant reply for the user using what you learned so far. If you still cannot fully answer, summarize the useful findings and what remains unresolved.")

(def default-wrap-up-prompt
  "Your cycle budget for this turn is exhausted. Do not start new work. First save any work in progress the way your instructions say to, then reply with a short note: what is done, what is next, and the exact place to resume from.")

(def default-checkpoint-prompt
  "Checkpoint: save work in progress the way your instructions say to, then continue. Do not stop.")

(def ^:private wrap-up-nudge default-wrap-up-prompt)

(defn- loop-summary-request [request response]
  (let [assistant-msg {:role "assistant" :content (or (:content response) "")}]
    (-> request
        (assoc :messages (conj (vec (:messages request))
                               assistant-msg
                               {:role "user" :content loop-exhausted-summary-instruction}))
        (assoc :tools []))))

(defn- merge-response-usage [usage response]
  (-> (merge-with + usage (:usage response))
      (update :requests inc)))

(defn- user-message-echo? [content messages]
  (let [trimmed (str/trim (or content ""))]
    (and (seq trimmed)
         (some #(= trimmed (str/trim (or (:content %) "")))
               (filter #(= "user" (:role %)) messages)))))

(defn- loop-limit-user-echo? [content user-input request]
  (or (and user-input (= (str/trim (or content ""))
                         (str/trim user-input)))
      (user-message-echo? content (:messages request))))

(defn- loop-limit-instruction-echo? [content]
  (let [trimmed (str/trim (or content ""))]
    (and (seq trimmed)
         (or (= trimmed (str/trim loop-exhausted-summary-instruction))
             (str/includes? trimmed "You have hit the cycle limit")))))

(defn- final-loop-summary [result chat-fn current-request]
  (let [content (or (:content result) (get-in result [:response :content]))]
    (if (or (not (:loop-request? result))
            (not (str/blank? content)))
      result
      (let [summary-response (chat-fn (loop-summary-request current-request (:response result)))
            summary-content  (:content summary-response)]
        (if (or (:error summary-response)
                (str/blank? summary-content)
                (loop-limit-user-echo? summary-content nil current-request)
                (loop-limit-instruction-echo? summary-content))
          result
          (-> result
              (assoc :content summary-content)
              (assoc :response summary-response)
              (assoc :usage (merge-response-usage (:usage result) summary-response))))))))

(def ^:private empty-terminal-continuation-nudge
  "Your previous response was empty. Please continue and provide your reply.")

(defn- terminal-response-content [result]
  (or (:content result) (get-in result [:response :content])))

(defn- canned-loop-exhausted-message
  ([result] (canned-loop-exhausted-message result nil nil))
  ([result user-input] (canned-loop-exhausted-message result user-input nil))
  ([result user-input request]
   (let [content (terminal-response-content result)]
     (if (and (:loop-request? result)
              (or (str/blank? content)
                  (loop-limit-user-echo? content user-input request)
                   (loop-limit-instruction-echo? content)))
       (let [message "I ran several tools but did not reach a conclusion before hitting the tool loop limit. Ask me to continue if you want me to keep digging."]
         (-> result
             (assoc :content message)
             (assoc-in [:response :content] message)))
       result))))

(defn- wrap-up-request
  ([request] (wrap-up-request request wrap-up-nudge))
  ([request prompt]
   (assoc request :messages (conj (vec (:messages request))
                                  {:role "user" :content prompt}))))

(defn- checkpoint-due? [every n]
  (and (some? every)
       (pos? (long every))
       (pos? (long n))
       (zero? (mod (long n) (long every)))))

(defn- with-checkpoint-nudge [request prompt]
  (assoc request :messages (conj (vec (:messages request))
                                 {:role "user" :content prompt})))

(defn- normalize-tool-calls [raw]
  (vec (or raw [])))

(defn- pending-tool-calls
  "Tool calls on the last LLM response that have not been executed.
   Do not use the loop's accumulated :tool-calls — those already ran."
  [result]
  (normalize-tool-calls (get-in result [:response :tool-calls])))

(defn- response-tool-calls* [response]
  (or (seq (:tool-calls response)) []))

(defn- with-assistant-message [request response]
  (let [assistant {:role "assistant" :content (or (:content response) "")}]
    (assoc request :messages (conj (vec (:messages request)) assistant))))

(defn- empty-wrap-up-failure []
  {:error    :empty-terminal-response
   :message  "empty-terminal-response: wrap-up note was empty"
   :ended-by :error})

(defn- normalize-exhaustion-answer [answer]
  (let [kw (cond
             (keyword? answer) answer
             (string? answer) (keyword (str/replace answer #"^:" ""))
             :else nil)]
    (if (#{:stop :wrap-up} kw) kw :stop)))

(defn- exhaustion-policy [ch session-key info]
  (let [answer (try (comm/on-exhausted ch session-key info)
                    (catch Exception _ :stop))]
    (normalize-exhaustion-answer answer)))

(declare prepare-tool-call! guard-empty-terminal-response)

(defn- persist-tool-results-in-order!
  "Write toolResult entries in model call order. Nil slots (cancelled) are skipped.
   Mid-batch crash: assistant batch entry is on disk, results written so far are not
   flushed until this call."
  [ctx session-key tool-calls results]
  (doseq [[tc result] (map vector tool-calls results)]
    (when (and tc result)
      (persist-tool-result! ctx session-key tc result))))

(defn- execute-pending-tools! [tool-ctx tool-calls]
  (let [ctx (:ctx tool-ctx)
        session-key (:session-key tool-ctx)]
    (persist-tool-batch! ctx session-key tool-calls)
    (let [results (mapv (fn [tc]
                          (let [{:keys [run]} (prepare-tool-call! tool-ctx tc)]
                            (run)))
                        tool-calls)]
      (persist-tool-results-in-order! ctx session-key tool-calls results)
      results)))

(defn- apply-stop-exhaustion [result chat-fn current-request input]
  (-> result
      (final-loop-summary chat-fn @current-request)
      (#(canned-loop-exhausted-message % input @current-request))
      (guard-empty-terminal-response chat-fn @current-request)
      (assoc :exhaustion :stopped)))

(defn- apply-wrap-up-exhaustion [result chat-fn followup-fn current-request tool-ctx wrap-up-prompt]
  (let [pending (or (seq (pending-tool-calls result))
                    (seq (response-tool-calls* (:response result))))]
    (when (seq pending)
      (let [results  (execute-pending-tools! tool-ctx pending)
            messages (followup-fn @current-request (or (:response result) result) pending results)]
        (reset! current-request (assoc @current-request :messages messages))))
    (let [wrap-req     (wrap-up-request @current-request (or wrap-up-prompt wrap-up-nudge))
          wrap-resp    (chat-fn wrap-req)
          wrap-calls   (or (seq (pending-tool-calls wrap-resp))
                           (seq (response-tool-calls* wrap-resp)))
          wrap-content (:content wrap-resp)]
      (reset! current-request wrap-req)
      (if (seq wrap-calls)
        (let [results  (execute-pending-tools! tool-ctx wrap-calls)
              messages (followup-fn wrap-req wrap-resp wrap-calls results)
              note-req (assoc (assoc wrap-req :messages messages) :tools [])
              note-resp (chat-fn note-req)
              content   (:content note-resp)]
          (reset! current-request note-req)
          (if (str/blank? content)
            (empty-wrap-up-failure)
            (-> result
                (assoc :content content
                       :response note-resp
                       :exhaustion :wrapped-up
                       :loop-request? true)
                (assoc :usage (merge-response-usage (:usage result) note-resp)))))
        (if (str/blank? wrap-content)
          (empty-wrap-up-failure)
          (do
            (reset! current-request (assoc wrap-req :tools []))
            (-> result
                (assoc :content wrap-content
                       :response wrap-resp
                       :exhaustion :wrapped-up
                       :loop-request? true)
                (assoc :usage (merge-response-usage (:usage result) wrap-resp)))))))))

(defn- empty-terminal-response? [result]
  (and (not (:error result))
       (str/blank? (terminal-response-content result))))

(defn- continuation-nudge-request [request response]
  (let [assistant-msg {:role "assistant" :content (or (:content response) "")}]
    (-> request
        (assoc :messages (conj (vec (:messages request))
                               assistant-msg
                               {:role "user" :content empty-terminal-continuation-nudge})))))

(defn- guard-empty-terminal-response
  ([result chat-fn current-request]
   (guard-empty-terminal-response result chat-fn current-request false))
  ([result chat-fn current-request retried?]
   (cond
     (:error result)
     result

     (:unavailable? result)
     result

     (not (empty-terminal-response? result))
     result

     retried?
     {:error   :empty-terminal-response
      :message "empty-terminal-response: model returned no content after continuation retry"}

     :else
     (let [response   (:response result)
           nudge-req  (continuation-nudge-request current-request response)
           retry-resp (chat-fn nudge-req)]
       (if (:error retry-resp)
         retry-resp
         (guard-empty-terminal-response
           (-> result
               (assoc :response retry-resp)
               (assoc :content (:content retry-resp))
               (assoc :usage (merge-response-usage (:usage result) retry-resp)))
           chat-fn
           nudge-req
           true))))))

;; endregion ^^^^^ Streaming ^^^^^

;; region ----- Context Compaction -----

(defn- session-entry
  ([ctx session-key]
   (when-let [sess (session-policy ctx)]
     (policy/get-session sess session-key))))

(def ^:private max-compaction-attempts 3)
(def ^:private context-window-guard-line 0.98)

(defn- consecutive-compaction-failures [entry]
  (or (get-in entry [:compaction :consecutive-failures]) 0))

(defn- reserve-async-compaction! [session-key]
  (let [lock     (Object.)
        claimed? (atom false)]
    (swap! in-flight-compactions
           (fn [state]
             (if (contains? state session-key)
               state
               (do
                 (reset! claimed? true)
                 (assoc state session-key {:lock lock})))))
    (when @claimed? lock)))

(declare run-compaction-check! active-tools build-chat-request)

(defn- perform-compaction! [session-key attempt prompt-tokens {:keys [compaction-llm-done context-window model provider soul splice-ready transcript-lock] ch :comm :as opts}]
  (let [provider-name (api/display-name provider)]
    (cond
      (> attempt max-compaction-attempts)
      (log/warn :session/compaction-stopped
                :session session-key
                :provider provider-name
                :model model
                :reason :max-attempts
                :attempt attempt
                :total-tokens prompt-tokens
                :context-window context-window)

      :else
      (let [started-at (System/currentTimeMillis)]
        (log/info :session/compaction-started
                  :session session-key
                  :provider provider-name
                  :model model
                  :total-tokens prompt-tokens
                  :context-window context-window)
        (when ch
          (comm/on-bulletin ch session-key {:kind            :compaction/start
                                            :provider        provider-name
                                            :model           model
                                            :total-tokens    prompt-tokens
                                            :context-window  context-window}))
        (let [result (compaction/compact! session-key
                                          {:model               model
                                           :api                 provider
                                           :soul                soul
                                           :root                (:root opts)
                                           :session-store       (:session-store opts)
                                           :session-policy      (session-policy opts)
                                           :charge              (:charge opts)
                                           :context-window      context-window
                                           :transcript-lock     transcript-lock
                                           :compaction-llm-done compaction-llm-done
                                           :splice-ready        splice-ready
                                           :chat-fn             (partial dispatch/dispatch-chat-with-tools provider)})]
          (if (:error result)
            (let [failures (inc (consecutive-compaction-failures (session-entry opts session-key)))]
              (policy/update-session! (session-policy opts) session-key {:compaction {:consecutive-failures failures}})
              (when ch
                (comm/on-bulletin ch session-key {:kind                  :compaction/failure
                                                  :consecutive-failures  failures
                                                  :error                 (:error result)
                                                  :message               (:message result)}))
              (when (>= failures max-compaction-attempts)
                (policy/update-session! (session-policy opts)
                                       session-key
                                       {:block {:reason :compaction-failed
                                                :at     (str (Instant/now))}})
                (attention/maybe-notify-conversation-blocked!
                  (loader/snapshot "conversation-blocked attention")
                  session-key
                  {:reason         :compaction-failed
                   :total-tokens   prompt-tokens
                   :context-window context-window})
                (log/warn :session/compaction-stopped
                          :session session-key
                          :provider provider-name
                          :model model
                          :reason :compaction-failed
                          :attempt attempt
                          :total-tokens prompt-tokens
                          :context-window context-window))
              (log/error :session/compaction-failed
                         :session session-key
                         :provider provider-name
                         :model model
                         :error (:error result)
                         :message (:message result))
              result)
            (do
              (policy/update-session! (session-policy opts) session-key {:compaction {:consecutive-failures 0}})
              (let [updated-total (compaction/estimate-prompt-tokens session-key opts)]
                (when ch
                  (comm/on-bulletin ch session-key {:kind         :compaction/success
                                                    :summary      (:summary result)
                                                    :tokens-saved (max 0 (- prompt-tokens updated-total))
                                                    :duration-ms  (- (System/currentTimeMillis) started-at)}))
                ;; Recheck iff compactable material remains after the splice
                ;; (isaac-5cr6). Chunked splices and true oversized-single
                ;; splices (a compactable body > window) are partial. A
                ;; complete non-chunked splice — including template-floor
                ;; :oversized-single — must not consume the next grover/chat
                ;; turn even when soul + tools keep the estimate over the line.
                ;; SessionPolicy keeps the session id stable across compaction
                ;; (isaac-mmod); episodes chain a successor container in place.
                ;; That chain is progress even when the live estimate (summary
                ;; + pending input) stays at the original number (isaac-jom5).
                (cond
                  (and (>= updated-total prompt-tokens)
                       (nil? (:successor-container result)))
                  (log/warn :session/compaction-stopped
                            :session session-key
                            :provider provider-name
                            :model model
                            :reason :no-progress
                            :attempt attempt
                            :total-tokens updated-total
                            :context-window context-window)

                  :else
                  (do
                    (log/info :session/compaction-completed
                              :session session-key
                              :successor (:successor-container result)
                              :provider provider-name
                              :model model
                              :attempt attempt
                              :total-tokens updated-total
                              :context-window context-window)
                    (when (and (compaction/partial-splice? result)
                               (compaction/should-compact? updated-total
                                                           (assoc (session-entry opts session-key)
                                                                  :compaction (:compaction opts))
                                                           context-window))
                      (run-compaction-check! session-key
                                             (assoc opts :comm ch :transcript-lock transcript-lock)
                                             (inc attempt)
                                             false))))))))))))

(defn- start-async-compaction! [session-key opts]
  (when-let [lock (reserve-async-compaction! session-key)]
    (let [compaction-llm-done (promise)
          splice-ready        (promise)
          task                (bound-fn []
                                (run-compaction-check! session-key
                                                       (assoc opts
                                                         :transcript-lock lock
                                                         :compaction-llm-done compaction-llm-done
                                                         :splice-ready splice-ready)
                                                       1 false))
          future*             (future (task))]
      (swap! in-flight-compactions assoc session-key {:future              future*
                                                      :lock                lock
                                                      :compaction-llm-done compaction-llm-done
                                                      :splice-ready        splice-ready})
      future*)))

(defn- compaction-tools-opts [{:keys [provider allowed-tools module-index] :as opts}]
  (if (contains? opts :tools)
    opts
    (assoc opts :tools (when provider (active-tools provider allowed-tools module-index)))))

(defn- session-transcript [session-key opts]
  (when-let [sess (session-policy opts)]
    (or (policy/get-transcript sess session-key) [])))

(defn- session-gauge [session-key opts]
  (let [entry (or (session-entry opts session-key) {})
        tx    (session-transcript session-key opts)]
    (compaction/context-gauge entry tx (:input opts))))

(defn- transcript-bytes [tx]
  (binding [*print-namespace-maps* false]
    (reduce + (map #(+ (alength (.getBytes (pr-str %) StandardCharsets/UTF_8)) 1) tx))))

(defn- run-compaction-check! [session-key {:keys [context-window model provider] :as opts} attempt allow-async?]
  (let [check-ns       (System/nanoTime)
        entry-start-ns (System/nanoTime)
        entry          (session-entry opts session-key)
        entry-ms       (elapsed-ms entry-start-ns)
        tx-start-ns    (System/nanoTime)
        tx             (session-transcript session-key opts)
        transcript-ms  (elapsed-ms tx-start-ns)
        gauge-start-ns (System/nanoTime)
        gauge          (compaction/context-gauge entry tx (:input opts))
        gauge-ms       (elapsed-ms gauge-start-ns)
        plan-start-ns  (System/nanoTime)
        plan           (compaction/plan-compaction tx entry context-window)
        plan-ms        (elapsed-ms plan-start-ns)
        config-start-ns (System/nanoTime)
        config          (or (:compaction opts)
                            (compaction/resolve-config entry context-window))
        config-ms       (elapsed-ms config-start-ns)
        provider-start-ns (System/nanoTime)
        prov-name        (when provider (api/display-name provider))
        provider-ms      (elapsed-ms provider-start-ns)
        size-start-ns    (System/nanoTime)
        transcript-bytes (transcript-bytes tx)
        size-ms          (elapsed-ms size-start-ns)]
    (log/debug :session/compaction-analysis
               :session session-key
               :provider prov-name
               :model model
               :tokens-before (:tokens-before plan)
               :compact-count (:compact-count plan)
               :strategy (:strategy plan)
               :context-window context-window)
    (log/debug :session/compaction-check
               :session session-key
               :provider prov-name
               :model model
               :entry-count (count tx)
               :transcript-bytes transcript-bytes
               :size-ms size-ms
               :entry-ms entry-ms
               :transcript-ms transcript-ms
               :gauge-ms gauge-ms
               :plan-ms plan-ms
               :config-ms config-ms
               :provider-ms provider-ms
               :total-tokens gauge
               :gauge gauge
               :context-window context-window
               :elapsed-ms (elapsed-ms check-ns))
    (cond
      (= :reset (:context-mode opts))
      (log/info :session/compaction-skipped
                :session session-key
                :provider prov-name
                :model model
                :total-tokens gauge
                :context-window context-window
                :reason :context-reset)

      (compaction/should-compact? gauge (assoc entry :compaction config) context-window)
      (if (and allow-async? (:async? config))
        (start-async-compaction! session-key opts)
        (perform-compaction! session-key attempt gauge (compaction-tools-opts opts))))))


(defn- context-window-guard-line-tokens [context-window]
  (long (* context-window-guard-line (or context-window 0))))

(defn- conversation-blocked? [session-key ctx]
  (boolean (:block (session-entry ctx session-key))))

(defn- blocked-result [cfg session-key]
  (let [retry-ms (provider-wall/provider-auth-retry-after-ms cfg)]
    (log/warn :drive/conversation-blocked
              :session session-key
              :retry-after-ms retry-ms)
    {:unavailable?   true
     :reason         :blocked
     :retry-after-ms retry-ms
     :session        session-key}))

(defn- context-exhausted-result [cfg session-key total-tokens context-window]
  (let [retry-ms (provider-wall/provider-auth-retry-after-ms cfg)]
    (log/warn :drive/context-exhausted
              :session session-key
              :total-tokens total-tokens
              :context-window context-window
              :guard-line (context-window-guard-line-tokens context-window)
              :retry-after-ms retry-ms)
    {:unavailable?   true
     :reason         :context-exhausted
     :retry-after-ms retry-ms
     :session        session-key}))

(defn- compact-failure-turn-result [session-key ctx]
  (let [cfg            (or (get-in ctx [:charge :config]) (nexus/get :config))
        context-window (get-in ctx [:charge :context-window])
        entry          (session-entry ctx session-key)
        total-tokens   (or (:last-input-tokens entry) 0)]
    (if (:block entry)
      (blocked-result cfg session-key)
      (context-exhausted-result cfg session-key total-tokens context-window))))

(defn- maybe-blocked-conversation! [session-key ctx]
  (when (conversation-blocked? session-key ctx)
    (blocked-result (or (get-in ctx [:charge :config]) (nexus/get :config)) session-key)))

(defn check-compaction!
  ([session-key opts]
   (run-compaction-check! session-key (merge (nexus/necho) opts) 1 true))
  ([ctx-or-root session-key opts]
   (run-compaction-check! session-key (merge opts (normalize-ctx ctx-or-root)) 1 true)))

(defn- mid-turn-compaction-opts [ctx]
  (let [{:keys [boot-files rules-text skill-menu-text allowed-tools provider]} ctx
        {:keys [compaction context-mode model soul context-window
                guidance nonce origin module-index comm config]} (:charge ctx)]
    (merge (normalize-ctx ctx)
           {:boot-files      boot-files
            :rules-text      rules-text
            :skill-menu-text skill-menu-text
            :compaction      compaction
            :context-mode    context-mode
            :model           model
            :soul            soul
            :context-window  context-window
            :provider        provider
            :comm            (or comm null-comm/channel)
            :guidance        guidance
            :nonce           nonce
            :origin          origin
            :module-index    module-index
            :allowed-tools   allowed-tools
            :config          config})))

(defn- rebuild-chat-request [session-key ctx]
  (let [{:keys [provider allowed-tools effort boot-files rules-text skill-menu-text]} ctx
        {:keys [crew guidance model module-index nonce origin soul context-mode]} (:charge ctx)
        sess       (session-policy ctx)
        transcript (with-transcript-lock session-key #(policy/active-transcript sess session-key))
        transcript (if (= :reset context-mode)
                      (if-let [current-user (last transcript)] [current-user] [])
                      transcript)
        tools       (active-tools provider allowed-tools module-index)]
    (build-chat-request provider {:boot-files      boot-files
                                  :crew            crew
                                  :effort          effort
                                  :guidance        guidance
                                  :model           model
                                  :nonce           nonce
                                  :origin          origin
                                  :rules-text      rules-text
                                  :session-name    session-key
                                  :skill-menu-text skill-menu-text
                                  :soul            soul
                                  :transcript      transcript
                                  :tools           tools})))

(defn- overflow-compact-retry!
  [session-key ctx current-request result]
  (when (prompt-too-long? result)
    (if (conversation-blocked? session-key ctx)
      (blocked-result (or (:config (:charge ctx)) (nexus/get :config)) session-key)
      (let [opts (mid-turn-compaction-opts ctx)
            total (session-gauge session-key opts)]
        (perform-compaction! session-key 1 total opts)
        (tool-registry/clear-window-cache! (:window-cache ctx))
        (let [rebuilt (rebuild-chat-request session-key ctx)]
          (reset! current-request rebuilt)
          rebuilt)))))

(defn- maybe-mid-turn-compact!
  "After tools persist, compact the disk transcript if needed and rebuild
   the next LLM request from the post-compaction active transcript.
   Returns the next request, or a :context-exhausted unavailable map.
   Provider-driven loops skip mid-turn compaction (option A, isaac-1sdl)
   and log :turn/compaction-deferred when after-tools would have fired."
  [session-key ctx request current-request]
  (let [provider (or (:provider ctx) (:provider (:charge ctx)))
        driven?  (boolean (and provider (:drives-tool-loop? (api/config provider))))
        opts     (mid-turn-compaction-opts ctx)
        context-window (:context-window (:charge ctx))
        config   (:config (:charge ctx))
        before   (session-gauge session-key opts)]
    (when driven?
      (log/info :turn/compaction-deferred
                :session session-key
                :reason :provider-driven))
    (when-not driven?
      (run-compaction-check! session-key opts 1 false))
    (let [after      (session-gauge session-key opts)
          guard-line (context-window-guard-line-tokens context-window)]
      (cond
        (and (pos? (or context-window 0))
             (>= after guard-line)
             (conversation-blocked? session-key ctx))
        (blocked-result (or config (nexus/get :config)) session-key)

        (and (pos? (or context-window 0))
             (>= after guard-line)
             (>= after before))
        (context-exhausted-result (or config (nexus/get :config)) session-key after context-window)

        (< after before)
        (let [rebuilt (rebuild-chat-request session-key ctx)]
          (tool-registry/clear-window-cache! (:window-cache ctx))
          (reset! current-request rebuilt)
          rebuilt)

        :else
        request))))

;; endregion ^^^^^ Context Compaction ^^^^^

;; region ----- Request Building -----

(defn- tool-name [tool]
  (or (:name tool)
      (get-in tool [:function :name])))

(defn- declared-wire-names [tools]
  (->> (concat (names/policy-list (:allow tools))
               (names/policy-list (:deny tools)))
       (remove #{names/POLICY_ALL})
       (remove names/glob-token?)
       (keep (fn [token]
               (or (names/wire-name token)
                   (cond
                     (keyword? token) (name token)
                     (string? token)  token
                     :else            (str token)))))))

(defn- allowed-tool-names
  "Wire names allowed for this turn via the four-step cascade (isaac-da0r).
   Empty/missing :allow is deny-all. Crew overlays global; omit crew :tools
   inherits the global result. Skill auto-tools are merged by the caller.
   Candidates are registered tools plus any exact tokens declared in policy
   (test fixtures / not-yet-activated modules)."
  [crew-members crew-id config]
  (let [global-tools (:tools config)
        crew-tools   (get-in crew-members [crew-id :tools])
        _            (tool-registry/ensure-policy-tools! (:module-index config)
                                                         (concat (names/policy-list (:allow global-tools))
                                                                 (names/policy-list (:allow crew-tools))))
        registered   (map :name (tool-registry/all-tools))
        declared     (concat (declared-wire-names global-tools)
                             (declared-wire-names crew-tools))
        candidates   (distinct (concat registered declared))]
    (->> candidates
         (filter #(names/cascade-allowed? global-tools crew-tools %))
         set)))

(defn- active-tools [_p allowed-tools module-index]
  (not-empty (if module-index
               (tool-registry/tool-definitions allowed-tools module-index)
               (tool-registry/tool-definitions allowed-tools))))

(defn- merge-allowed-tools [crew-tools auto-tools]
  (not-empty (into (set (or crew-tools [])) auto-tools)))

(defn build-chat-request [p {:keys [boot-files crew effort guidance model nonce origin rules-text session-name skill-menu-text soul transcript tools]}]
  (let [prompt-out (api/build-prompt p {:boot-files      boot-files
                                        :crew            crew
                                        :guidance        guidance
                                        :model           model
                                        :nonce           nonce
                                        :origin          origin
                                        :rules-text      rules-text
                                        :session-name    session-name
                                        :skill-menu-text skill-menu-text
                                        :soul            soul
                                        :transcript      transcript
                                        :tools           tools})
        stateful   (get (api/config p) :stateful)]
    (cond-> {:model (:model prompt-out) :messages (:messages prompt-out)}
            (:system prompt-out) (assoc :system (:system prompt-out))
            (:max-tokens prompt-out) (assoc :max-tokens (:max-tokens prompt-out))
            (:tools prompt-out) (assoc :tools (:tools prompt-out))
            (some? effort) (assoc :effort effort)
            (some? stateful) (assoc :stateful stateful)
            session-name (assoc :session-key session-name))))

;; endregion ^^^^^ Request Building ^^^^^

;; region ----- Public API -----

(defn- augment-provider
  "Wrap an upstream Api with per-turn runtime values (root,
   session-key, context-window, and model-cfg overrides) merged into
   its config. Returns a new Api instance — the upstream one is unchanged."
  [root p session-key context-window model-cfg-overrides]
  (when p
    (let [cfg (merge (or (api/config p) {})
                     model-cfg-overrides
                     {:root           root
                      :session-key    session-key
                      :context-window context-window})]
      (llm-provider/make-provider (api/display-name p) cfg))))

(def turn-schema
  {:name   :turn
   :type   :map
   :schema {:charge          {:type :ignore :description "Resolved charge — the comm-supplied inputs"}
            :session-store   {:type :ignore :description "Session store backing this turn (derived from charge or root)"}
            :root            {:type :string :description "Isaac state directory"}
            :effort          {:type :long :description "Per-turn effort budget, nil when model disallows effort"}
            :allowed-tools   {:type :ignore :description "Set of tool keywords allowed for this turn's crew"}
            :boot-files      {:type :ignore :description "Boot-file contents read from the discovered project root"}
            :rules-text      {:type :ignore :description "Always-on prepared rule bodies read from global/project roots"}
            :skill-menu-text {:type :ignore :description "Advertised skill descriptions injected into the cached system prompt"}
            :provider        {:type :ignore :description "Tools-augmented LLM provider for this turn"}}})

(defn- build-turn
  "Wraps a resolved charge with per-turn derived state. Charge already holds
   the resolved behavior (model, provider, soul, compaction, effort, etc.),
   so this only computes the genuinely per-turn fields and the
   tools-augmented provider that drive needs."
  [charge]
  (let [{:keys [session-key crew crew-members context-window
                model model-cfg provider]} charge
        root             (or (nexus/get :root) (get-in charge [:config :root]))
        session-store*   (or (:session-store charge) (nexus/get-in [:sessions :store]))
        sess             (or (:session-policy charge)
                             (policy/for-request charge)
                             (when session-store* (policy/wrap session-store*)))
        session          (when sess (policy/get-session sess session-key))
        skill-disclosure (or (session-ctx/read-skill-disclosure (:config charge) root (:cwd session))
                             {:menu-text nil :tool-names #{}})
        allowed-tools    (merge-allowed-tools (allowed-tool-names crew-members crew (:config charge))
                                              (:tool-names skill-disclosure))
        boot-files       (session-ctx/read-boot-files (:cwd session))
        rules-text       (session-ctx/read-rules-text (:config charge) root (:cwd session))
        augmented        (augment-provider root provider session-key context-window
                                           (select-keys (or model-cfg {})
                                                        [:thinking-budget-max :think-mode :stateful]))]
    (log/debug :turn/context-resolved
               :session session-key
               :crew crew
               :model model
               :provider (some-> provider api/display-name)
               :effort (:effort charge)
               :context-window context-window
               :crew-keys (vec (keys crew-members))
               :crew-cfg-keys (some-> (:crew-cfg charge) keys vec)
               :allowed-tools-count (count allowed-tools)
               :allowed-tools (some-> allowed-tools sort vec)
               :cwd (:cwd session))
    (schema/conform! turn-schema
                     {:charge          charge
                      ;; convenience accessors for storage helpers — same value, derived via session-store helper
                      :session-store   session-store*
                      :root            root
                      :effort          (when (get (or model-cfg {}) :allows-effort true)
                                         (:effort charge))
                      :allowed-tools   allowed-tools
                      :boot-files      boot-files
                      :rules-text      rules-text
                      :skill-menu-text (:menu-text skill-disclosure)
                      :provider        augmented})))

(defn- observer-ctx [session-key]
  {:session-key session-key})

(defn- notify-observers! [observers method ctx extra]
  (when (seq observers)
    (observer/notify! observers method ctx extra)))

(defn- finish-turn! [ch session-key result observers origin]
  (let [result (-> result
                   (cond-> origin (assoc :origin origin))
                   finalize-turn-result)]
    (log-turn-ended! session-key result)
    (comm/on-turn-end ch session-key result)
    (let [ctx (observer-ctx session-key)]
      (if (= :exception (:error result))
        (notify-observers! observers :on-turn-died ctx (or (:message result) "unknown"))
        (notify-observers! observers :on-turn-ended ctx (observer/outcome result))))
    result))

(defn- announce-tool-call!
  [{:keys [session-key] ch :comm :as tool-ctx} tc]
  (let [tool-state     (atom :announced)
        cancel-queued! #(when (compare-and-set! tool-state :announced :cancelled)
                          (comm/on-tool-cancel ch session-key tc))]
    (comm/on-tool-call ch session-key tc)
    (when-let [end-aside! (:end-aside! tool-ctx)]
      (end-aside!))
    (bridge/on-cancel! session-key cancel-queued!)
    {:tool-call      tc
     :cancel-queued cancel-queued!
     :run           (fn []
                      (when-not (compare-and-set! tool-state :announced :running)
                        (throw (ex-info "cancelled" {:type :cancelled})))
                      (let [{:keys [allowed-tools module-index tool-count caps ctx]} tool-ctx
                            progress! (fn [chunk] (comm/on-tool-progress ch session-key tc chunk))
                            args      (cond-> (or (:arguments tc) {})
                                         true (assoc "session_key" session-key)
                                         true (assoc :progress! progress!))
                            cache      (:window-cache tool-ctx)
                            cycle-n    (or (some-> tool-ctx :cycle* deref :n) 1)
                            raw-result (if-let [parse-error (:arguments-error tc)]
                                         {:isError true
                                          :error (str "Invalid arguments for " (:name tc) ": " parse-error)}
                                         (if cache
                                           (tool-registry/execute (:name tc) args allowed-tools module-index caps cache cycle-n)
                                           (tool-registry/execute (:name tc) args allowed-tools module-index caps)))]
                        (when (= :cancelled (:error raw-result))
                          (when (compare-and-set! tool-state :running :cancelled)
                            (comm/on-tool-cancel ch session-key tc))
                          (throw (ex-info "cancelled" {:type :cancelled})))
                        (let [result        (tool-registry/present-result raw-result)
                              after-result! (when (map? raw-result) (:after-result! raw-result))]
                          (when (compare-and-set! tool-state :running :completed)
                            (swap! tool-count inc)
                            (comm/on-tool-result ch session-key tc result)
                            (when after-result!
                              (try
                                (after-result!)
                                (catch Exception e
                                  (log/warn :tool/after-result-failed
                                            :tool (:name tc)
                                            :session session-key
                                            :error (.getMessage e))))))
                          result)))}))

(defn- prepare-tool-call! [tool-ctx tc]
  (announce-tool-call! tool-ctx tc))

(defn- record-tool-call!
  "Legacy single-call path: announce then execute one tool immediately."
  [tool-ctx name arguments]
  (let [tc {:id        (str (java.util.UUID/randomUUID))
            :name      name
            :arguments arguments
            :type      "toolCall"}]
    (persist-tool-batch! (:ctx tool-ctx) (:session-key tool-ctx) [tc])
    (let [{:keys [run]} (prepare-tool-call! tool-ctx tc)
          result (run)]
      (persist-tool-result! (:ctx tool-ctx) (:session-key tool-ctx) tc result)
      result)))

(defn- execute-llm-turn!
  "Build the chat request, drive the tool-loop, and persist the final
   assistant response. Tool pairs are written mid-loop by record-tool-call!.
   Returns the final result map."
  [session-key input ctx]
  (let [{:keys [provider allowed-tools effort boot-files rules-text skill-menu-text]} ctx
        charge        (:charge ctx)
        {:keys [crew guidance model module-index nonce origin soul context-mode comm config crew-cfg cycle]} charge
        cycle-cfg     (resolve-cycle {:cycle cycle :config config :crew crew :crew-cfg crew-cfg})
        cycle-budget  (:limit cycle-cfg)
        max-parallel  (resolve-max-parallel-tools {:config config :crew crew :crew-cfg crew-cfg})
        caps          {:max-lines (get-in config [:tools :defaults :max-lines])
                       :max-bytes (get-in config [:tools :defaults :max-bytes])}
        ch            (or comm null-comm/channel)
        p             provider]
    (when-not (:from-queue? charge)
      (append-message! ctx session-key {:role "user" :content input}))
    (let [transcript      (with-transcript-lock session-key #(policy/active-transcript (session-policy ctx) session-key))
          transcript      (if (= :reset context-mode)
                            (if-let [current-user (last transcript)] [current-user] [])
                            transcript)
          tools           (active-tools p allowed-tools module-index)
          tool-reason     (cond
                            (empty? allowed-tools) :no-allowed-tools
                            (empty? tools) :no-registered-tools
                            :else nil)
          build-start-ns  (System/nanoTime)
          request         (build-chat-request p {:boot-files      boot-files
                                                 :crew            crew
                                                 :effort          effort
                                                 :guidance        guidance
                                                 :model           model
                                                 :nonce           nonce
                                                 :origin          origin
                                                 :rules-text      rules-text
                                                 :session-name    session-key
                                                 :skill-menu-text skill-menu-text
                                                 :soul            soul
                                                 :transcript      transcript
                                                 :tools           tools})
          build-ms        (elapsed-ms build-start-ns)
          _               (log/debug :turn/request-built
                                     :session session-key
                                     :provider (api/display-name p)
                                     :model (:model request)
                                     :effort (:effort request)
                                     :build-ms build-ms
                                     :messages-count (count (:messages request))
                                     :allowed-tools-count (count allowed-tools)
                                     :selected-tools-count (count tools)
                                     :selected-tools (some->> tools (map tool-name) sort vec)
                                     :tool-selection-reason tool-reason
                                     :request-keys (-> request keys sort vec))
          current-request (atom request)
          tool-count      (atom 0)
          window-cache    (atom {})]
      (when-let [done (:compaction-llm-done (active-compaction-state session-key))]
        (deref done 5000 nil))
      (let [cycle*      (atom {:n 1 :model model :origin (:origin charge)})
            chat-fn     (chat-fn-for ch session-key p @current-request cycle*)
            followup-elapsed* (atom nil)
            followup-fn (fn [req response tool-calls tool-results]
                          (let [start-ns (System/nanoTime)
                                messages (api/followup-messages p req response tool-calls tool-results)]
                            (reset! followup-elapsed* (elapsed-ms start-ns))
                            (reset! current-request (assoc req :messages messages))
                            messages))
            pending-aside* (atom nil)
            on-cycle      (fn [phase n response-or-req]
                            (let [cycle {:n n :model model :origin (:origin charge)}]
                              (reset! cycle* cycle)
                              (if (= :start phase)
                                (do (reset! pending-aside* nil)
                                    (comm/on-cycle-start ch session-key cycle))
                                (when-not (or (:error response-or-req) (:unavailable? response-or-req))
                                  (stamp-provider-prompt! ctx session-key response-or-req)
                                  (let [text       (or (:content response-or-req) "")
                                        tool-calls (or (:tool-calls response-or-req) [])
                                        summary    (get-in response-or-req [:reasoning :summary])]
                                    (when (seq (str summary))
                                      (append-reckoning! ctx session-key summary))
                                    (if (seq tool-calls)
                                      (reset! pending-aside* {:cycle cycle :text text :tool-calls tool-calls})
                                      (do
                                        (comm/on-cycle-end ch session-key cycle {:outcome :reply :text text :tool-calls []})
                                        (comm/on-reply ch session-key text))))))))
            end-aside!    (fn []
                            (when-let [{:keys [cycle text tool-calls]} @pending-aside*]
                              (reset! pending-aside* nil)
                              (comm/on-cycle-end ch session-key cycle {:outcome :aside :text text :tool-calls tool-calls})
                              (comm/on-aside ch session-key cycle text)))
            tool-ctx      {:comm           ch
                            :session-key    session-key
                            :allowed-tools  allowed-tools
                            :module-index   module-index
                            :caps           caps
                            :tool-count     tool-count
                            :ctx            ctx
                            :end-aside!     end-aside!
                            :window-cache   window-cache
                            :cycle*         cycle*}
            tool-fn       (partial record-tool-call! tool-ctx)
            run-loop      (fn [req]
                            (let [provider-name (api/display-name p)
                                  request*      (assoc req :provider provider-name)
                                  chat-fn*      (chat-fn-for ch session-key p request* cycle*)]
                              (tool-loop/run chat-fn* followup-fn request* tool-fn
                                             {:max-loops          cycle-budget
                                              :max-parallel-tools max-parallel
                                              :prepare-tool-call  #(prepare-tool-call! tool-ctx %)
                                              :on-tool-batch      #(persist-tool-batch! ctx session-key %)
                                              :on-tool-batch-results #(persist-tool-results-in-order! ctx session-key %1 %2)
                                              :cancelled?         #(bridge/cancelled? session-key)
                                              :after-tools        (fn [req]
                                                                   (let [start-ns (System/nanoTime)
                                                                         next     (maybe-mid-turn-compact! session-key (assoc ctx :window-cache window-cache) req current-request)]
                                                                     (when-let [ms @followup-elapsed*]
                                                                       (log/debug :turn/followup-built :elapsed-ms ms)
                                                                       (reset! followup-elapsed* nil))
                                                                     (log/debug :turn/after-tools :elapsed-ms (elapsed-ms start-ns))
                                                                     (if (or (:error next) (:unavailable? next))
                                                                       next
                                                                       (let [n (:n @cycle*)]
                                                                         (if (checkpoint-due? (:checkpoint-every cycle-cfg) n)
                                                                           (let [prompt (or (:checkpoint-prompt cycle-cfg) default-checkpoint-prompt)
                                                                                 nudged (with-checkpoint-nudge next prompt)]
                                                                             (log/info :turn/checkpoint-nudged :session session-key :cycle n)
                                                                             (append-checkpoint! ctx session-key n)
                                                                             (reset! current-request nudged)
                                                                             nudged)
                                                                           next)))))
                                              :on-cycle           on-cycle
                                              :api                p})))
            first-result (run-loop request)
            retry        (overflow-compact-retry! session-key (assoc ctx :window-cache window-cache) current-request first-result)
            loop-result  (cond
                           (nil? retry) first-result
                           (:unavailable? retry) retry
                           :else (run-loop retry))
            loop-result  (if (and retry (prompt-too-long? loop-result))
                           (context-exhausted-result config session-key
                                                     (session-gauge session-key (mid-turn-compaction-opts ctx))
                                                     (:context-window charge))
                           loop-result)
            result       (if (:unavailable? loop-result)
                           loop-result
                           (let [normalized (provider-wall/normalize loop-result config (api/display-name p))
                                 exhausted? (:loop-request? normalized)
                                 policy     (when exhausted?
                                              (exhaustion-policy ch session-key {:cycle-limit cycle-budget}))
                                 handled    (cond
                                              (not exhausted?)
                                              (-> normalized
                                                  (guard-empty-terminal-response chat-fn @current-request))

                                              (= :wrap-up policy)
                                              (apply-wrap-up-exhaustion normalized chat-fn followup-fn current-request tool-ctx (:wrap-up-prompt cycle-cfg))

                                              :else
                                              (apply-stop-exhaustion normalized chat-fn current-request input))]
                             (-> handled
                                 (assoc :cycle-limit cycle-budget)
                                 finalize-turn-result)))]
        (log/debug :turn/model-response-summary
                   :session session-key
                   :provider (api/display-name p)
                   :error (:error result)
                   :assistant-content-chars (count (or (get-in result [:response :content]) ""))
                   :tool-calls-count (count (:tool-calls result))
                   :executed-tools-count @tool-count)
        (let [provider-name (api/display-name p)
              result        (provider-wall/normalize result config provider-name)]
          (cond
            (or (= :cancelled (:error result))
                (:cancelled? result)
                (bridge/cancelled-response? result)
                (bridge/cancelled? session-key))
            (suspend/interrupt-result session-key)

            (weather/weather-reason result)
            (let [ss (or (:session-store ctx) (nexus/get-in [:sessions :store]))]
              (if ss
                (weather/stamp-weather! ss session-key result
                                        {:provider provider-name
                                         :model    model
                                         :now      (or memory/*now* (memory/now))
                                         :model-override (:model-override charge)})
                result))

            :else
            (do
              (when-not (:error result)
                (log/debug :chat/stream-completed :session session-key))
              (or (process-response! ctx session-key result {:model model :provider provider-name})
                  result))))))))

(defn- run-turn-body!
  "The successful-path pipeline. Returns the result that finish-turn! should
   wrap. Each branch is a single call into a focused helper.

   Unresolved charges (unknown crew, no model) are rejected upstream in
   bridge/route-charge!, so we only see resolved charges here."
  [session-key input ctx]
  (let [{:keys [boot-files provider rules-text skill-menu-text allowed-tools]} ctx
        {:keys [crew comm compaction context-mode model soul context-window
                guidance nonce origin module-index]} (:charge ctx)]
    (cond
      (bridge/cancelled? session-key)
      (suspend/interrupt-result session-key)

      :else
      (do
        (if-let [blocked (maybe-blocked-conversation! session-key ctx)]
          blocked
          (do
            (log/info :drive/turn-accepted {:session session-key :crew crew})
            (let [compact-result (check-compaction! ctx session-key {:boot-files      boot-files
                                                                     :rules-text      rules-text
                                                                     :skill-menu-text skill-menu-text
                                                                     :compaction      compaction
                                                                     :context-mode    context-mode
                                                                     :model           model
                                                                     :soul            soul
                                                                     :context-window  context-window
                                                                     :provider        provider
                                                                     :comm            (or comm null-comm/channel)
                                                                     :input           input
                                                                     :guidance        guidance
                                                                     :nonce           nonce
                                                                     :origin          origin
                                                                     :module-index    module-index
                                                                     :allowed-tools   allowed-tools
                                                                     :config          (:config (:charge ctx))})]
              (cond
                (bridge/cancelled? session-key)
                (suspend/interrupt-result session-key)

                (:error compact-result)
                (compact-failure-turn-result session-key ctx)

                :else
                (execute-llm-turn! session-key input ctx)))))))))

(defn- record-exception! [session-key e ctx]
  (let [{:keys [provider]} ctx
        model (:model (:charge ctx))]
    (append-error! ctx session-key {:content  (.getMessage e)
                                    :error    "exception"
                                    :ex-class (.getName (class e))
                                    :model    model
                                    :provider (when provider (api/display-name provider))})
    (log/error :session/turn-failed :session session-key :error (.getMessage e) :ex-class (.getName (class e)))
    (attention/maybe-notify-turn-failed!
      (or (get-in ctx [:charge :config]) (loader/snapshot "turn-failed attention"))
      session-key
      {:message (.getMessage e)})
    {:error :exception :message (.getMessage e) :ex-class (.getName (class e))}))

(defn run-turn!
  "Drives a single turn from a resolved charge. The bridge rejects unresolved
   charges before they reach here."
  [charge]
  (let [session-key (:session-key charge)
        input       (:input charge)
        ctx         (build-turn charge)
        ch          (or (:comm charge) null-comm/channel)
        turn-id     (bridge/begin-turn! session-key)
        observers   (observer/for-turn (:observers charge))
        finish!     #(finish-turn! ch session-key % observers (:origin charge))]
    (try
      (comm/on-turn-start ch session-key input)
      (notify-observers! observers :on-turn-started (observer-ctx session-key) nil)
      (finish! (run-turn-body! session-key input ctx))
      (catch ExceptionInfo e
        (if (= :cancelled (:type (ex-data e)))
          (finish! (suspend/interrupt-result session-key))
          (finish! (record-exception! session-key e ctx))))
      (catch Exception e
        (if (bridge/cancelled? session-key)
          (finish! (suspend/interrupt-result session-key))
          (finish! (record-exception! session-key e ctx))))
      (catch Throwable t
        (if (bridge/cancelled? session-key)
          (finish! (suspend/interrupt-result session-key))
          (finish! (record-exception! session-key t ctx))))
      (finally
        (turnstile/release-all! (:turnstile-tokens charge))
        (bridge/end-turn! session-key turn-id)))))

;; endregion ^^^^^ Public API ^^^^^
