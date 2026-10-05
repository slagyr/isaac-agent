(ns isaac.agent.session.session-observer
  "Ordered, off-turn-path notification for session observers."
  (:require
    [isaac.agent.attention :as attention]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]
    [isaac.foundation.nexus :as nexus]))

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

(defn- contributed-factory [observer module-index]
  (some->> (vals module-index)
           (some #(get-in % [:manifest :isaac.agent/session-observer observer :factory]))
           requiring-resolve
           var-get))

(defn- observer-factory [observer cfg]
  (let [module-index (:module-index cfg)]
    (or (get @factories* observer)
        (when module-index
          (when-let [module-id (module-loader/supporting-module-id module-index
                                                                   :isaac.agent/session-observer observer)]
            (module-loader/activate! module-id module-index)
            (or (get @factories* observer)
                (contributed-factory observer module-index))))
        (contributed-factory observer module-index))))

(defn- deliver! [session-id observer event cfg]
  (try
    (when-let [factory (observer-factory observer cfg)]
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
            key      [session-id observer]
            next     (promise)
            old      (get (first (swap-vals! queues* assoc key next)) key)
            fs*      (nexus/get :fs)]
        (future
          (when old @old)
          (try
            (if fs*
              (nexus/-with-nested-nexus {:fs fs*}
                (deliver! session-id observer event (:config event)))
              (deliver! session-id observer event (:config event)))
            (finally (deliver next true))))))))

(defn await! [session-id observer]
  (when-let [pending (get @queues* [session-id (keyword observer)])]
    @pending))

(defn drain! [session-id]
  (doseq [[[id observer] _] @queues* :when (= id session-id)]
    (await! id observer)))

(defn drain-all! []
  (doseq [[[id observer] _] @queues*]
    (await! id observer)))

(defn reset-queues! []
  (reset! queues* {}))
