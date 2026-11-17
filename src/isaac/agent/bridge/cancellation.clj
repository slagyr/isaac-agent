(ns isaac.agent.bridge.cancellation
  (:require
    [isaac.foundation.logger :as log]
    [isaac.agent.session.store.spi :as store]))

;; region ----- State -----

;; :turns    session-key -> the running turn, or a :pending? cancel no turn has
;;           adopted yet.
;; :expected session-key -> tokens for turns a surface has requested that the
;;           bridge may not have admitted yet (see expect-turn!).
(def ^:private state* (atom {:turns {} :expected {}}))

(defn- new-turn []
  {:cancelled? (atom false)
   :hooks      (atom [])})

(defn- turn-of [session-key]
  (get-in @state* [:turns session-key]))

(defn- awaited? [state session-key]
  (boolean (seq (get-in state [:expected session-key]))))

(defn- drop-pending [state session-key]
  (if (and (get-in state [:turns session-key :pending?])
           (not (awaited? state session-key)))
    (update state :turns dissoc session-key)
    state))

;; endregion ^^^^^ State ^^^^^

;; region ----- Public API -----

(defn cancelled-result []
  {:stopReason "cancelled"})

(defn cancelled-response? [result]
  (= "cancelled" (:stopReason result)))

(defn clear! []
  (reset! state* {:turns {} :expected {}})
  nil)

(defn expect-turn!
  "Registers a turn a surface has requested for session-key but the bridge may
   not have admitted yet, so a cancel that arrives first is kept for it.
   Returns a token for settle-turn!."
  [session-key]
  (let [token (Object.)]
    (swap! state* update-in [:expected session-key] (fnil conj #{}) token)
    token))

(defn settle-turn!
  "The requested turn is over. Once no requested turn remains, a cancel no turn
   adopted is dropped."
  [session-key token]
  (swap! state* (fn [state]
                  (let [tokens (disj (get-in state [:expected session-key] #{}) token)]
                    (-> (if (seq tokens)
                          (assoc-in state [:expected session-key] tokens)
                          (update state :expected dissoc session-key))
                        (drop-pending session-key)))))
  nil)

(defn begin-turn!
  "Starts the session's turn. Adopts a pending cancel left for it by `cancel!`,
   so a cancel that raced ahead of the turn still counts."
  [session-key]
  (-> (swap! state* update-in [:turns session-key] #(dissoc (or % (new-turn)) :pending?))
      (get-in [:turns session-key])))

(defn end-turn! [session-key turn]
  (swap! state* update :turns #(if (identical? turn (get % session-key))
                                 (dissoc % session-key)
                                 %))
  nil)

(defn discard-pending!
  "Drops a cancel no turn has adopted, unless a requested turn still awaits it.
   The bridge calls this when it releases the session, so a cancel that landed
   after the turn ended can't reach the next one."
  [session-key]
  (swap! state* drop-pending session-key)
  nil)

(defn cancelled? [session-key]
  (or (some-> (turn-of session-key) :cancelled? deref boolean)
      (true? (:cancelled (some-> (store/registered-store)
                                 (store/get-turn-marker session-key))))))

(defn on-cancel! [session-key f]
  (when (and session-key f)
    (if-let [turn (turn-of session-key)]
      (do
        (swap! (:hooks turn) conj f)
        (when @(:cancelled? turn)
          (f)))
      nil))
  nil)

(defn cancel!
  "Cancels the session's running turn. With no turn running, the cancel is kept
   only for a turn that is admitted (in flight) or requested (expect-turn!), and
   the next begin-turn! adopts it. Otherwise it is a logged no-op and leaves
   nothing behind for a later turn."
  [session-key]
  (when session-key
    (let [turn     (-> (swap! state* update-in [:turns session-key] #(or % (assoc (new-turn) :pending? true)))
                       (get-in [:turns session-key]))
          running? (not (:pending? turn))
          hooks    @(:hooks turn)]
      (reset! (:cancelled? turn) true)
      (doseq [hook hooks]
        (try
          (hook)
          (catch Exception _ nil)))
      (try
        (when-let [ss (store/registered-store)]
          (when (true? (:suspended (store/get-turn-marker ss session-key)))
            (store/clear-turn-marker! ss session-key)))
        (catch Exception _ nil))
      (cond
        running?
        (log/info :bridge/cancel-applied :session session-key :hooks (count hooks))

        (or (awaited? @state* session-key) (store/in-flight-anywhere? session-key))
        (log/info :bridge/cancel-pending :session session-key)

        :else
        (do (swap! state* (fn [state]
                            (if (and (identical? turn (get-in state [:turns session-key]))
                                     (not (awaited? state session-key)))
                              (update state :turns dissoc session-key)
                              state)))
            (log/info :bridge/cancel-noop :session session-key :hooks 0)))
      true)))

;; endregion ^^^^^ Public API ^^^^^
