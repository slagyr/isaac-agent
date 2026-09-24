(ns isaac.attention
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]))

(def ^:private session-throttle-ms (* 60 60 1000))
(def ^:private provider-throttle-ms (* 60 60 1000))

(defonce ^:private last-session-notified* (atom {}))
(defonce ^:private last-provider-notified* (atom {}))

(defn clear-throttle!
  "Test hook — reset per-session and per-provider attention throttle state."
  []
  (reset! last-session-notified* {})
  (reset! last-provider-notified* {}))

(def ^:private content-cap 1000)
(def ^:private provider-message-cap 400)

(defn- clip [s cap]
  (let [n (count s)]
    (if (<= n cap)
      s
      (str (subs s 0 cap) "… " (- n cap) " characters dropped"))))

(defn- clip-content [content]
  (let [s (str content)
        n (count s)]
    (when (> n content-cap)
      ;; The alert is a summary; the log is the transport for the payload.
      (log/info :attention/content-clipped :content-chars n :full-content s))
    (clip s content-cap)))

(defn- notify-coords [cfg]
  (get-in cfg [:attention :notify]))

(defn- enqueue-attention! [cfg content]
  (let [content (clip-content content)]
    (if-let [{:keys [comm target]} (notify-coords cfg)]
      (queue/enqueue! {:comm    (if (string? comm) (keyword comm) comm)
                       :target  target
                       :content content})
      (log/warn :attention/unconfigured :content content))))

(defn- clock-ms [override]
  (or override
      (when-let [now (memory/now)]
        (.toEpochMilli now))
      (System/currentTimeMillis)))

(defn- tail-clip
  "Keep the tail of a long payload: for a streamed provider error the head
  is the handshake and the failure is at the end (isaac-9af8)."
  [s cap]
  (let [n (count s)]
    (if (<= n cap)
      s
      (str "… " (- n cap) " characters dropped … " (subs s (- n cap))))))

(defn- provider-content [{:keys [provider model session error status message]} suppressed]
  (str/join " "
            (remove str/blank?
                    [(str "Provider " provider " is broken")
                     ;; The diagnosis leads so an operator never has to open
                     ;; the log to learn why (isaac-9af8).
                     (when error (str "error " (name error)))
                     (when status (str "status " status))
                     (when model (str "model " model))
                     (when session (str "session " session))
                     (when (pos? suppressed)
                       (str suppressed " more " (if (= 1 suppressed) "failure" "failures")))
                     ;; An alert is a summary, not a transport for a payload
                     ;; (isaac-9af8): the full text goes to the log when the
                     ;; message cap fires; the alert keeps the tail, where a
                     ;; streamed failure actually is.
                     (when message (tail-clip (str message) provider-message-cap))])))

(defn maybe-notify-conversation-blocked!
  "Post attention when a conversation is blocked and needs intervention."
  [cfg session-key {:keys [reason total-tokens context-window]}]
  (let [content (str/join " "
                          (remove str/blank?
                                  [(str "Conversation blocked for session " session-key)
                                   (when reason (str "reason " (name reason)))
                                   (when total-tokens (str "total-tokens " total-tokens))
                                   (when context-window (str "context-window " context-window))]))]
    (enqueue-attention! cfg content)))

(defn maybe-notify-context-exhausted!
  "Reserved; hail deferral posts context-exhausted attention (isaac-dark)."
  [_cfg _session-key _payload _now-ms]
  nil)

(defn- parked-content [session-key {:keys [reason provider]}]
  (str/join " "
            (remove str/blank?
                    [(str "Turn parked for session " session-key)
                     (str "suspended (" (name (or reason :wall)) ")")
                     (when provider (str "provider " provider))
                     (when (= :auth reason) "— only a human can re-login")])))

(defn maybe-notify-turn-parked!
  "Post attention for a turn parked on provider weather; returns true when it
   posted. Throttled per session, not per park: the :auth park posts the moment
   it parks (a wall clears itself, an expired login does not) and the
   :suspended-attention-ms threshold notice says the same thing about the same
   session, so they share one throttle instead of arriving back to back
   (isaac-f3hq)."
  ([cfg session-key payload] (maybe-notify-turn-parked! cfg session-key payload nil))
  ([cfg session-key payload now-ms]
   (let [now     (clock-ms now-ms)
         last-ms (get @last-session-notified* session-key)]
     (when (or (nil? last-ms) (>= (- now last-ms) session-throttle-ms))
       (enqueue-attention! cfg (parked-content session-key payload))
       (swap! last-session-notified* assoc session-key now)
       true))))

(defn maybe-notify-turn-failed!
  "Post attention when a turn dies with an uncaught throwable."
  [cfg session-key {:keys [message]}]
  (let [content (str/join " "
                          (remove str/blank?
                                  [(str "Turn failed for session " session-key)
                                   (when message (str "error " message))]))]
    (enqueue-attention! cfg content)))

(defn maybe-notify-continuations-exhausted!
  "Post attention when a session wrapped up as often as its budget allows and
   the work is still unfinished (isaac-xpkf)."
  [cfg session-key {:keys [continuation budget]}]
  (enqueue-attention!
    cfg
    (str/join " "
              (remove str/blank?
                      [(str "Session " session-key " ran out of continuations")
                       (when continuation (str "after " continuation))
                       (when budget (str "of " budget))
                       "with work still unfinished"]))))

(defn maybe-notify-provider-broken!
  "Post attention when a provider answers with a non-wall, non-auth error.
   Throttled per provider: first failure posts, then at most one post per hour
   while it keeps failing. The hourly repost carries the suppressed count."
  ([cfg payload]
   (maybe-notify-provider-broken! cfg payload nil))
  ([cfg {:keys [provider] :as payload} now-ms]
   (let [now        (clock-ms now-ms)
         state      (get @last-provider-notified* provider)
         last-ms    (:at state)
         suppressed (:suppressed state 0)
         elapsed?   (or (nil? last-ms)
                        (>= (- now last-ms) provider-throttle-ms))]
     (if elapsed?
       (do
         (when-let [m (:message payload)]
           (let [s (str m)]
             ;; The alert carries a head; the log carries the payload (isaac-9af8).
             (when (> (count s) provider-message-cap)
               (log/info :attention/provider-message-clipped
                         :provider provider
                         :message-chars (count s)
                         :full-message s))))
         (enqueue-attention! cfg (provider-content payload suppressed))
         (swap! last-provider-notified* assoc provider {:at now :suppressed 0}))
       (let [next-suppressed (inc suppressed)]
         (swap! last-provider-notified* assoc-in [provider :suppressed] next-suppressed)
         (log/info :attention/provider-throttled
                   :provider provider
                   :suppressed next-suppressed))))))
