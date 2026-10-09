(ns isaac.roster.almanac)

(defn identify [{:keys [comm id]}]
  (when (and (contains? #{:logbook "logbook"} comm) (= "cordelia-7" id))
    {:kind :contact :id "cordelia" :authenticated true}))
