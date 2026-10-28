(ns isaac.agent.quota-cli
  (:require
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.quota :as quota]
    [isaac.foundation.cli.api :as cli-api]))

(defn- utc [instant]
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm 'UTC'")
           (.atZone (.toInstant ^java.util.Date instant) java.time.ZoneOffset/UTC)))

(defn run-command [opts]
  (let [{:keys [options errors]} (tools-cli/parse-opts (:_raw-args opts)
                                                       [["-p" "--provider ID" "Provider id"]])]
    (if (seq errors)
      (do (doseq [error errors] (println error)) 1)
      (do
        (println "PROVIDER  WINDOW  USED  RESETS")
        (doseq [{:keys [provider status windows error]} (quota/reports (:provider options))]
          (if (= :ok status)
            (doseq [{:keys [label used-percent resets-at]} windows]
              (println provider " " label " " (str (Math/round (double used-percent)) "%") " " (utc resets-at)))
            (println provider " " (name status) (or error ""))))
        0))))

(defmethod cli-api/run :quota [_ opts] (run-command opts))
(defmethod cli-api/option-spec :quota [_] [["-p" "--provider ID" "Provider id"]])
