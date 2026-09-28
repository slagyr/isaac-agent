(ns isaac.turn.submit
  "Queue-only Agent turn submission for other in-process modules."
  (:require
    [isaac.config.loader :as loader]
    [isaac.drive.observer :as observer]
    [isaac.fs :as fs]
    [isaac.resource-pool :as pool]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.store.spi :as sessions]
    [isaac.turn.queue :as queue]))

(defn submit!
  "Accept one durable, keyed turn without running it. Returns its stable request record."
  [{:keys [root config frequencies resource-pools observers prompt preamble cycle id key origin]}]
  (let [cfg       (or config (loader/snapshot "turn submit"))
        pool-refs (mapv keyword resource-pools)
        pools     (pool/resolve-submitted cfg pool-refs)
        resolved  (when-not (:error pools)
                    (frequencies/resolve-session-targets
                      (cond-> frequencies
                        (string? (:session frequencies)) (update :session vector))
                      (sessions/registered-store) cfg))
        obs       (when (and resolved (not (:error resolved)))
                    (observer/resolve-submitted observers))
        error     (or (:error pools) (:error resolved) (:error obs))]
    (when error
      (throw (ex-info (or (:message pools) (:message resolved) (:message obs) (name error))
                      {:reason error})))
    (when (and (:session frequencies) (not (:session-key resolved)))
      (throw (ex-info "turn submission requires an existing session" {:frequencies frequencies})))
    (binding [queue/*root* (or root (loader/root))]
      (queue/enqueue! (cond-> {:input prompt :key key :origin (or origin {:kind :submit})
                              :resource-pools pool-refs :observers observers :state :queued}
                        (some? id) (assoc :id id)
                        (some? preamble) (assoc :preamble preamble)
                        (some? cycle) (assoc :cycle cycle)
                        (:session frequencies) (assoc :session (:session-key resolved)
                                                      :frequencies frequencies)
                        (not (:session frequencies)) (assoc :frequencies frequencies))))))
