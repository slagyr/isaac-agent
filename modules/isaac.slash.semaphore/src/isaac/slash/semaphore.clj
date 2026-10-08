(ns isaac.slash.semaphore)

(defn provider []
  {:commands (fn [_] [{:name "hoist" :description "Hoist a signal flag"}
                      {:name "dip" :description "Dip the flag in salute"}
                      {:name "status" :description "Semaphore status"}
                      {:name "cwd" :description "Semaphore has the conn" :rank 50}
                      {:name "tend" :description "Semaphore tends" :rank 950}])
   :handle (fn [name _session-key {:keys [args]} _ctx]
             (case name
               "hoist" {:input (str "Hoist the " args " flag and report.")}
               "dip" {:type :command :command :dip :message "Flag dipped."}
               "status" {:type :command :command :status :message "Semaphore status."}
               "cwd" {:type :command :command :cwd :message "Semaphore has the conn."}
               "tend" {:type :command :command :tend :message "Semaphore tends."}))})
