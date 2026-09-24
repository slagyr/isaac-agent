(ns isaac.drive.weather
  "Provider-weather suspend: stamp a durable turn marker and release the thread."
  (:require
    [isaac.attention :as attention]
    [isaac.charge :as charge]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Instant)))

(def default-backoff-ms 30000)
(def max-backoff-ms 1800000)

(defn weather-reason [result]
  (cond
    ;; Silence is weather (isaac-f3hq). An expired provider login looks exactly
    ;; like a model that answers nothing, even after the one continuation nudge
    ;; (isaac-k4mf): park it for the sweep instead of recording a failure the
    ;; operator has to read as "the model said nothing".
    (= :empty-terminal-response (:error result))
    :silence

    (:unavailable? result)
    (case (:reason result)
      :wall :wall
      :auth :auth
      :stream-stalled :stall
      nil)))

(defn backoff-ms
  ([result]
   (backoff-ms result 1))
  ([result suspend-count]
   (let [n (max 1 (or suspend-count 1))
         exponential (long (* default-backoff-ms (Math/pow 2 (dec n))))]
     (min max-backoff-ms
          (max exponential (or (:retry-after-ms result) 0))))))

(defn- iso [instant]
  (str instant))

(defn- plus-ms [instant ms]
  (.plusMillis ^Instant instant (long ms)))

(defn- clock [now]
  (or now memory/*now* (memory/now) (Instant/now)))

(defn- notify-auth-park!
  "An :auth park posts attention the moment it parks (isaac-f3hq). A wall clears
   itself and a stall retries; only a human can re-login, so waiting out
   :suspended-attention-ms before saying so wastes the whole park. Throttled in
   isaac.attention, so a park that keeps re-parking notifies at most hourly."
  [cfg session-key reason provider now]
  (when (= :auth reason)
    (attention/maybe-notify-turn-parked! cfg session-key
                                         {:reason reason :provider provider}
                                         (.toEpochMilli ^Instant now))))

(defn stamp-weather!
  "Merge weather fields onto the session's turn marker and return a suspended result."
  [store session-key result {:keys [cfg provider model now model-override]}]
  (let [reason        (or (weather-reason result) :wall)
        existing      (store/get-turn-marker store session-key)
        suspend-count (inc (or (:suspend-count existing) 0))
        now*          (clock now)
        wait-ms       (backoff-ms result suspend-count)
        retry-at      (plus-ms now* wait-ms)
        notified?     (notify-auth-park! cfg session-key reason
                                         (or provider (get-in existing [:suspended-on :provider]))
                                         now*)
        marker        (cond-> (assoc (or existing {})
                                :session-id     session-key
                                :suspended      true
                                :reason         reason
                                :suspended-on   {:provider (or provider (get-in existing [:suspended-on :provider]))
                                                 :model    (or model (get-in existing [:suspended-on :model]))}
                                :suspended-at   (or (:suspended-at existing) (iso now*))
                                :retry-at       (iso retry-at)
                                :suspend-count  suspend-count)
                        (or model-override (:model-override existing))
                        (assoc :model-override (or model-override (:model-override existing)))
                        (or notified? (:attention-posted existing))
                        (assoc :attention-posted true))]
    (store/record-turn-marker! store session-key marker)
    (log/warn :turn/suspended
              :session session-key
              :reason reason
              :retry-at (iso retry-at)
              :suspend-count suspend-count)
    (cond-> {:stopReason    "suspended"
             :ended-by      :suspended
             :reason        reason
             :retry-at      (iso retry-at)
             :unavailable?  true}
      (:retry-after-ms result) (assoc :retry-after-ms (:retry-after-ms result))
      (seq (:message result)) (assoc :message (:message result)))))

(def default-attention-ms (* 6 60 60 1000))

(defn- retry-due? [marker now]
  (let [retry (:retry-at marker)]
    (or (nil? retry)
        (not (.isBefore now (Instant/parse retry))))))

(defn- attention-due? [marker now attention-ms]
  (when-let [at (:suspended-at marker)]
    (and (not (:attention-posted marker))
         (>= (- (.toEpochMilli ^Instant now) (.toEpochMilli (Instant/parse at)))
             attention-ms))))

(defn- post-attention! [store session-id marker cfg now]
  (attention/maybe-notify-turn-parked!
    cfg session-id
    {:reason   (:reason marker)
     :provider (get-in marker [:suspended-on :provider])}
    (.toEpochMilli ^Instant now))
  (store/record-turn-marker! store session-id (assoc marker :attention-posted true)))

(defn- resume-suspended! [store session-id marker now trigger]
  (let [suspended-ms (when-let [at (:suspended-at marker)]
                       (- (.toEpochMilli ^Instant now) (.toEpochMilli (Instant/parse at))))]
    (log/info :turn/resumed
              :session session-id
              :trigger trigger
              :suspended-ms suspended-ms)
    (binding [memory/*now* now]
      (let [result ((requiring-resolve 'isaac.drive.turn/run-turn!)
                    (charge/build {:config         (or (loader/snapshot "weather resume") {})
                                   :session-key    session-id
                                   :input          "interrupted by provider weather; continue from the transcript."
                                   :comm           null-comm/channel
                                   :from-queue?    true
                                   :model-override (:model-override marker)}))]
        (when-not (= "suspended" (:stopReason result))
          (store/clear-turn-marker! store session-id))
        result))))

(defn sweep-weather!
  "Re-drive suspended turns whose :retry-at has passed. Posts one attention
   notice when a park exceeds :turn :suspended-attention-ms."
  [{:keys [session-store cfg now trigger]
    :or   {trigger :sweep}}]
  (when session-store
    (let [now*         (clock now)
          attention-ms (or (get-in cfg [:turn :suspended-attention-ms]) default-attention-ms)]
      ;; Orphaned markers only: a session whose turn is in flight is already
      ;; being driven — by the turn queue after boot resume handed it over, or
      ;; by a previous tick that is still running — and must not be driven a
      ;; second time (isaac-f3hq).
      (doseq [marker (store/orphaned-turn-markers session-store)
              :when (true? (:suspended marker))
              :let [session-id (or (:session-id marker) (:session marker))]]
        (when (attention-due? marker now* attention-ms)
          (post-attention! session-store session-id marker cfg now*))
        (when (retry-due? marker now*)
          (resume-suspended! session-store session-id
                             (or (store/get-turn-marker session-store session-id) marker)
                             now* trigger))))))
