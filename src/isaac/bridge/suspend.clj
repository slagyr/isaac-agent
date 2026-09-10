(ns isaac.bridge.suspend
  (:require
    [isaac.bridge.cancellation :as cancel]
    [isaac.logger :as log]
    [isaac.session.policy :as policy]
    [isaac.session.store.spi :as store]))

(def default-timeout-ms 15000)

(defonce ^:private suspended-sessions* (atom #{}))
(defonce ^:private suspend-boundaries* (atom {}))

(defn clear! []
  (reset! suspended-sessions* #{})
  (reset! suspend-boundaries* {})
  nil)

(defn suspended-result []
  {:stopReason "suspended"})

(defn suspended-response? [result]
  (= "suspended" (:stopReason result)))

(defn session-suspended? [session-key]
  (contains? @suspended-sessions* session-key))

(defn interrupt-result [session-key]
  (if (session-suspended? session-key)
    (suspended-result)
    (cancel/cancelled-result)))

(defn- as-policy [store]
  (policy/wrap store))

(defn- stamp-suspended-marker! [store session-key boundary]
  (let [sess (as-policy store)]
    (when-let [marker (policy/get-turn-marker sess session-key)]
      (policy/record-turn-marker! sess session-key
                                  (assoc marker
                                         :suspended true
                                         :boundary boundary
                                         :interrupted-at (str (java.time.Instant/now)))))))

(defn release-turn-marker! [store session-key]
  (if (session-suspended? session-key)
    (let [boundary (or (get @suspend-boundaries* session-key) :clean)]
      (stamp-suspended-marker! store session-key boundary)
      (swap! suspended-sessions* disj session-key)
      (swap! suspend-boundaries* dissoc session-key))
    (policy/clear-turn-marker! (as-policy store) session-key)))

(defn suspend!
  [{:keys [timeout-ms session-store]
    :or   {timeout-ms default-timeout-ms}}]
  (when-not session-store
    (throw (ex-info "suspend! requires :session-store" {})))
  (let [sessions (store/in-flight-sessions session-store)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (when (seq sessions)
      (swap! suspended-sessions* into sessions)
      (log/info :bridge/suspend-starting :sessions (vec sessions) :timeout-ms timeout-ms)
      (doseq [sid sessions] (cancel/cancel! sid))
      (loop []
        (let [remaining (store/in-flight-sessions session-store)]
          (cond
            (empty? remaining) nil
            (>= (System/currentTimeMillis) deadline)
            (do
              (log/info :bridge/suspend-timeout :remaining (vec remaining))
              (doseq [sid remaining]
                (swap! suspend-boundaries* assoc sid :unclean)
                (stamp-suspended-marker! session-store sid :unclean)))
            :else
            (do (Thread/sleep 50) (recur))))))
    (log/info :bridge/suspend-complete)
    nil))