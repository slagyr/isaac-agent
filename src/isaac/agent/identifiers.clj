(ns isaac.agent.identifiers
  "Resolve party handles at the durable turn submission boundary."
  (:require
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]))

(defn register-entry!
  "Validate an identifier factory when the module's berth is processed."
  [[_ {:keys [factory]}]]
  (requiring-resolve factory))

(defn identify
  "Ask installed identifiers to name a handle. Never let a failing identifier prevent a turn."
  [module-index party]
  (if (not= :handle (:kind party))
    party
    (or (some (fn [[module {:keys [manifest]}]]
                (some (fn [[_ {:keys [factory]}]]
                        (try
                          (module-loader/activate! module module-index)
                          (when-let [replacement ((deref (requiring-resolve factory)) party)]
                            (cond-> (dissoc replacement :authenticated)
                              (contains? party :authenticated) (assoc :authenticated (:authenticated party))))
                          (catch Throwable e
                            (log/warn :identifier/failed :module (name module) :error (.getMessage e))
                            nil)))
                      (:isaac.agent/identifiers manifest)))
              module-index)
        party)))
