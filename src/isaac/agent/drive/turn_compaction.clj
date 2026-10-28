(ns isaac.agent.drive.turn-compaction
  (:require
    [isaac.agent.attention :as attention]
    [isaac.agent.comm.null :as null-comm]
    [isaac.agent.comm.protocol :as comm]
    [isaac.agent.drive.dispatch :as dispatch]
    [isaac.agent.drive.provider-wall :as provider-wall]
    [isaac.agent.drive.request :as turn-request]
    [isaac.agent.drive.tools-config :as tools-config]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.session.compaction :as compaction]
    [isaac.agent.session.context-mode :as context-mode]
    [isaac.agent.session.session-observer :as session-observer]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.registry :as tool-registry]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

;; turn and this namespace call each other. Resolve turn's helpers on first
;; use so loading either namespace does not cycle.
(defn- turn-var [name]
  (requiring-resolve (symbol "isaac.agent.drive.turn" name)))

(def ^:private session-store* (delay (turn-var "session-store")))
(def ^:private elapsed-ms* (delay (turn-var "elapsed-ms")))
(def ^:private normalize-ctx* (delay (turn-var "normalize-ctx")))
(def ^:private prompt-too-long?* (delay (turn-var "prompt-too-long?")))
(def ^:private with-transcript-lock* (delay (turn-var "with-transcript-lock")))
(def ^:private in-flight-compactions* (delay @(turn-var "in-flight-compactions")))

(defn- session-store [ctx] (@session-store* ctx))
(defn- elapsed-ms [start-ns] (@elapsed-ms* start-ns))
(defn- normalize-ctx [ctx] (@normalize-ctx* ctx))
(defn- prompt-too-long? [result] (@prompt-too-long?* result))
(defn- with-transcript-lock [session-key f] (@with-transcript-lock* session-key f))
(defn- in-flight-compactions [] @in-flight-compactions*)

;; region ----- Context Compaction -----

(defn- session-entry
  ([ctx session-key]
   (when-let [sess (session-store ctx)]
     (store/get-session sess session-key))))

(def ^:private max-compaction-attempts 3)
(def ^:private context-window-guard-line 0.98)

(defn- consecutive-compaction-failures [entry]
  (or (get-in entry [:compaction :consecutive-failures]) 0))

(defn- reserve-async-compaction! [session-key]
  (let [lock     (Object.)
        claimed? (atom false)]
    (swap! (in-flight-compactions)
           (fn [state]
             (if (contains? state session-key)
               state
               (do
                 (reset! claimed? true)
                 (assoc state session-key {:lock lock})))))
    (when @claimed? lock)))

(declare run-compaction-check!)

(defn- log-compaction-stopped!
  [session-key provider-name model reason attempt total-tokens context-window]
  (log/warn :session/compaction-stopped
            :session session-key
            :provider provider-name
            :model model
            :reason reason
            :attempt attempt
            :total-tokens total-tokens
            :context-window context-window))

(defn- announce-compaction-start!
  [ch session-key provider-name model prompt-tokens context-window]
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
                                      :context-window  context-window})))

(defn- record-compaction-failure!
  [session-key attempt prompt-tokens provider-name model context-window opts ch result]
            (let [failures (inc (consecutive-compaction-failures (session-entry opts session-key)))]
              (store/update-session! (session-store opts) session-key {:compaction {:consecutive-failures failures}})
              (when ch
                (comm/on-bulletin ch session-key {:kind                  :compaction/failure
                                                  :consecutive-failures  failures
                                                  :error                 (:error result)
                                                  :message               (:message result)}))
              (when (>= failures max-compaction-attempts)
                (store/update-session! (session-store opts)
                                       session-key
                                       {:block {:reason :compaction-failed
                                                :at     (str (Instant/now))}})
                (attention/maybe-notify-conversation-blocked!
                  (loader/snapshot "conversation-blocked attention")
                  session-key
                  {:reason         :compaction-failed
                   :total-tokens   prompt-tokens
                   :context-window context-window})
                (log-compaction-stopped! session-key provider-name model :compaction-failed
                                         attempt prompt-tokens context-window))
              (log/error :session/compaction-failed
                         :session session-key
                         :provider provider-name
                         :model model
                         :error (:error result)
                         :message (:message result))
              result))

(defn- finish-successful-compaction!
  [session-key attempt prompt-tokens provider-name model context-window opts ch result started-at transcript-lock]
  (store/update-session! (session-store opts) session-key {:compaction {:consecutive-failures 0}})
              (session-observer/publish! session-key (:session-observers (:charge opts))
                                         {:event :compaction-spliced :config (get-in opts [:charge :config])})
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
                ;; The session store keeps the session id stable across compaction
                ;; (isaac-mmod); episodes chain a successor container in place.
                ;; That chain is progress even when the live estimate (summary
                ;; + pending input) stays at the original number (isaac-jom5).
                (cond
                  (and (>= updated-total prompt-tokens)
                       (nil? (:successor-container result)))
                  (log-compaction-stopped! session-key provider-name model :no-progress
                                           attempt updated-total context-window)

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
                                             false))))))

(defn perform-compaction! [session-key attempt prompt-tokens {:keys [compaction-llm-done context-window model provider soul splice-ready transcript-lock] ch :comm :as opts}]
  (let [provider-name (api/display-name provider)]
    (cond
      (> attempt max-compaction-attempts)
      (log-compaction-stopped! session-key provider-name model :max-attempts attempt prompt-tokens context-window)

      :else
      (let [started-at (System/currentTimeMillis)]
        (announce-compaction-start! ch session-key provider-name model prompt-tokens context-window)
        (let [result (compaction/compact! session-key
                                          {:model               model
                                           :api                 provider
                                           :soul                soul
                                           :root                (:root opts)
                                           :session-store       (:session-store opts)
                                           :store-override     (session-store opts)
                                           :charge              (:charge opts)
                                           :context-window      context-window
                                           :transcript-lock     transcript-lock
                                           :compaction-llm-done compaction-llm-done
                                           :splice-ready        splice-ready
                                           :chat-fn             (partial dispatch/dispatch-chat-with-tools provider)})]

          (if (:error result)
            (record-compaction-failure! session-key attempt prompt-tokens provider-name model context-window opts ch result)
            (finish-successful-compaction! session-key attempt prompt-tokens provider-name model context-window opts ch result started-at transcript-lock)))))))


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
      (swap! (in-flight-compactions) assoc session-key {:future              future*
                                                      :lock                lock
                                                      :compaction-llm-done compaction-llm-done
                                                      :splice-ready        splice-ready})
      future*)))

(defn- compaction-tools-opts [{:keys [provider allowed-tools module-index] :as opts}]
  (if (contains? opts :tools)
    opts
    (assoc opts :tools (when provider (tools-config/active-tools provider allowed-tools module-index)))))

(defn- session-transcript [session-key opts]
  (when-let [sess (session-store opts)]
    (or (store/get-transcript sess session-key) [])))

(defn session-gauge [session-key opts]
  (let [entry (or (session-entry opts session-key) {})
        tx    (session-transcript session-key opts)]
    (compaction/context-gauge entry tx (:input opts))))

(defn- transcript-bytes [tx]
  (binding [*print-namespace-maps* false]
    (reduce + (map #(+ (alength (.getBytes (pr-str %) StandardCharsets/UTF_8)) 1) tx))))

(defn run-compaction-check! [session-key {:keys [context-window model provider] :as opts} attempt allow-async?]
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
      ;; :total-tokens sits beside :context-window on this line, so it has to be
      ;; the number that is comparable to the window — what the request will
      ;; carry. On a :reset session the running tally measures the stored
      ;; session, which the request never contains; it keeps its own name so
      ;; nobody reads it as "this session is 94% full" (isaac-5nx5).
      (log/info :session/compaction-skipped
                :session session-key
                :provider prov-name
                :model model
                :total-tokens (compaction/reset-gauge tx (:input opts))
                :stored-tokens gauge
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

(defn context-exhausted-result [cfg session-key total-tokens context-window]
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

(defn compact-failure-turn-result [session-key ctx]
  (let [cfg            (or (get-in ctx [:charge :config]) (nexus/get :config))
        context-window (get-in ctx [:charge :context-window])
        entry          (session-entry ctx session-key)
        total-tokens   (or (:last-input-tokens entry) 0)]
    (if (:block entry)
      (blocked-result cfg session-key)
      (context-exhausted-result cfg session-key total-tokens context-window))))

(defn maybe-blocked-conversation! [session-key ctx]
  (when (conversation-blocked? session-key ctx)
    (blocked-result (or (get-in ctx [:charge :config]) (nexus/get :config)) session-key)))

(defn check-compaction!
  ([session-key opts]
   (run-compaction-check! session-key (merge (nexus/necho) opts) 1 true))
  ([ctx-or-root session-key opts]
   (run-compaction-check! session-key (merge opts (normalize-ctx ctx-or-root)) 1 true)))

(defn mid-turn-compaction-opts [ctx]
  (let [{:keys [boot-files rules-text skill-menu-text allowed-tools provider]} ctx
        {:keys [compaction context-mode model soul context-window
                guidance nonce origin module-index comm config]} (:charge ctx)]
    (merge (normalize-ctx ctx)
           {:boot-files      boot-files
            :rules-text      rules-text
            :skill-menu-text skill-menu-text
            :sections        (:sections ctx)
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
        {:keys [crew guidance model module-index nonce origin preamble soul context-mode]} (:charge ctx)
        sess       (session-store ctx)
        transcript (with-transcript-lock session-key #(store/active-transcript sess session-key))
        transcript (context-mode/select-transcript context-mode transcript module-index)
        tools       (tools-config/active-tools provider allowed-tools module-index)]
    (turn-request/build-chat-request provider {:boot-files      boot-files
                                  :sections        (:sections ctx)
                                  :crew            crew
                                  :effort          effort
                                  :guidance        guidance
                                  :model           model
                                  :nonce           nonce
                                  :origin          origin
                                  :preamble        preamble
                                  :rules-text      rules-text
                                  :session-name    session-key
                                  :skill-menu-text skill-menu-text
                                  :soul            soul
                                  :transcript      transcript
                                  :tools           tools})))

(defn overflow-compact-retry!
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

(defn maybe-mid-turn-compact!
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
