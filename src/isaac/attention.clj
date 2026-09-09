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

(defn- notify-coords [cfg]
  (get-in cfg [:attention :notify]))

(defn- enqueue-attention! [cfg content]
  (if-let [{:keys [comm target]} (notify-coords cfg)]
    (queue/enqueue! {:comm    (if (string? comm) (keyword comm) comm)
                     :target  target
                     :content content})
    (log/warn :attention/unconfigured :content content)))

(defn- clock-ms [override]
  (or override
      (when-let [now (memory/now)]
        (.toEpochMilli now))
      (System/currentTimeMillis)))

(defn- provider-content [{:keys [provider model session message]} suppressed]
  (str/join " "
            (remove str/blank?
                    [(str "Provider " provider " is broken")
                     (when model (str "model " model))
                     (when session (str "session " session))
                     (when (pos? suppressed)
                       (str suppressed " more " (if (= 1 suppressed) "failure" "failures")))
                     (when message message)])))

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

(defn maybe-notify-turn-failed!
  "Post attention when a turn dies with an uncaught throwable."
  [cfg session-key {:keys [message]}]
  (let [content (str/join " "
                          (remove str/blank?
                                  [(str "Turn failed for session " session-key)
                                   (when message (str "error " message))]))]
    (enqueue-attention! cfg content)))

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
         (enqueue-attention! cfg (provider-content payload suppressed))
         (swap! last-provider-notified* assoc provider {:at now :suppressed 0}))
       (let [next-suppressed (inc suppressed)]
         (swap! last-provider-notified* assoc-in [provider :suppressed] next-suppressed)
         (log/info :attention/provider-throttled
                   :provider provider
                   :suppressed next-suppressed))))))
