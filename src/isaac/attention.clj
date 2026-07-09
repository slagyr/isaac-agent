(ns isaac.attention
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.logger :as log]))

(def ^:private session-throttle-ms (* 60 60 1000))

(defonce ^:private last-session-notified* (atom {}))

(defn clear-throttle!
  "Test hook — reset per-session attention throttle state."
  []
  (reset! last-session-notified* {}))

(defn- notify-coords [cfg]
  (get-in cfg [:attention :notify]))

(defn- enqueue-attention! [cfg content]
  (if-let [{:keys [comm target]} (notify-coords cfg)]
    (queue/enqueue! {:comm    (if (string? comm) (keyword comm) comm)
                     :target  target
                     :content content})
    (log/warn :attention/unconfigured :content content)))

(defn maybe-notify-compaction-disabled!
  "Post attention when compaction is disabled after too many failures."
  [cfg session-key {:keys [reason total-tokens context-window]}]
  (let [content (str/join " "
                          (remove str/blank?
                                  [(str "Compaction disabled for session " session-key)
                                   (when reason (str "reason " (name reason)))
                                   (when total-tokens (str "total-tokens " total-tokens))
                                   (when context-window (str "context-window " context-window))]))]
    (enqueue-attention! cfg content)))

(defn maybe-notify-context-exhausted!
  "Reserved; hail deferral posts context-exhausted attention (isaac-dark)."
  [_cfg _session-key _payload _now-ms]
  nil)
