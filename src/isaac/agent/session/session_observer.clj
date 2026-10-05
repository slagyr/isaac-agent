(ns isaac.agent.session.session-observer
  "Ordered, off-turn-path notification for session observers."
  (:require
    [isaac.agent.attention :as attention]
    [isaac.foundation.logger :as log]))

(defonce ^:private factories* (atom {}))
(defonce ^:private queues* (atom {}))

(defn register! [id factory]
  (swap! factories* assoc (keyword id) factory))

(defn register-entry! [[id {:keys [factory]}]]
  (register! id (some-> factory requiring-resolve var-get)))

(defn known [module-index]
  (->> (concat (keys @factories*)
               (mapcat #(keys (get-in % [:manifest :isaac.agent/session-observer])) (vals module-index)))
       (map name) distinct sort vec))

(defn- deliver! [session-id observer event cfg]
  (try
    (when-let [factory (get @factories* observer)]
      ((factory cfg) (assoc event :session-id session-id)))
    (catch Throwable t
      (log/error :session/observer-error :observer (name observer) :session-id session-id :error (.getMessage t))
      (try
        (attention/maybe-notify-session-observer-failed! cfg session-id observer)
        (catch Throwable _ nil)))))

(defn publish! [session-id observers event]
  (when (and session-id (seq observers))
    (doseq [observer observers]
      (let [observer (keyword observer)
            key [session-id observer]
            next (promise)
            old (get (first (swap-vals! queues* assoc key next)) key)]
        (future
          (when old @old)
          (try (deliver! session-id observer event (:config event))
               (finally (deliver next true))))))))

(defn await! [session-id observer]
  (when-let [pending (get @queues* [session-id (keyword observer)])]
    @pending))

(defn drain! [session-id]
  (doseq [[[id observer] _] @queues* :when (= id session-id)]
    (await! id observer)))
