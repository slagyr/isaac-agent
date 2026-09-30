(ns isaac.agent.marigold-comms
  "Marigold's themed comm impls: each themed id instantiates one of the
   real built-in comms. Loaded lazily by isaac.agent.comm.factory/ensure-impl!
   via the contributions' :namespace."
  (:require
    [isaac.agent.comm.memory :as memory-comm]
    [isaac.agent.comm.null :as null-comm]
    [isaac.agent.comm.factory :as factory]))

(defmethod factory/create :longwave [node-path _slice]
  (null-comm/make {:name (last node-path)}))

(defmethod factory/create :skybeam [node-path _slice]
  (null-comm/make {:name (last node-path)}))

(defmethod factory/create :logbook [node-path _slice]
  (memory-comm/make {:name (last node-path)}))
