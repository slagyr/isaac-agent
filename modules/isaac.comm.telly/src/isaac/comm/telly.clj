(ns isaac.comm.telly
  (:require
    [isaac.comm.factory :as factory]
    [c3kit.apron.env :as c3env]
    [isaac.api :as api]
    [isaac.comm.protocol :as comm]
    [isaac.logger :as log]))

(when (= "true" (c3env/env "ISAAC_TELLY_FAIL_ON_LOAD"))
  (throw (ex-info "telly load failed"
                  {:entry     'isaac.comm.telly
                   :module-id :isaac.comm.telly
                   :type      :module/activation-failed})))

(deftype Telly [host state]
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ _ _] nil)
  (on-tool-call [_ _ _] nil)
  (on-tool-cancel [_ _ _] nil)
  (on-tool-result [_ _ _ _] nil)
  (on-compaction-start [_ _ _] nil)
  (on-compaction-success [_ _ _] nil)
  (on-compaction-failure [_ _ _] nil)
  (on-compaction-disabled [_ _ _] nil)
  (on-turn-end [_ _ _] nil)
  (send! [_ _] {:ok false :transient? false})
  api/Reconfigurable
  (on-startup! [_ slice]
    (log/info :telly/started
              :module (let [name (:name host)]
                        (if (keyword? name) (clojure.core/name name) (str name))))
    (reset! state {:slice      slice
                   :started?   true
                   :host       host
                   :last-event :started}))
  (on-config-change! [_ old-slice new-slice]
    (if (nil? new-slice)
      (reset! state {:slice      nil
                     :started?   false
                     :host       host
                     :last-event :stopped
                     :prior      old-slice})
      (swap! state assoc
             :slice new-slice
             :last-event :changed
             :prior old-slice))))

(defn make [host]
  (->Telly host (atom {})))

(defmethod factory/create :telly [node-path _slice]
  (make {:name (last node-path)}))

(defn telly? [x]
  (instance? Telly x))

(defn state [^Telly t]
  @(.-state t))
