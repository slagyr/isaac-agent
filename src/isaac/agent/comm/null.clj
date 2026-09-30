(ns isaac.agent.comm.null
  (:require
    [isaac.agent.comm.protocol :as comm]
    [isaac.foundation.reconfigurable :as reconfigurable]))

(deftype NullComm [])

(extend NullComm
  comm/Comm
  (merge comm/defaults
         {:send! (fn [_ _] {:ok false :transient? false})})
  reconfigurable/Reconfigurable
  {:on-load           (fn [_ _] nil)
   :on-config-change! (fn [_ _ _] nil)
   :on-unload         (fn [_ _] nil)})

(defn make [_host]
  (->NullComm))

(def channel (->NullComm))
