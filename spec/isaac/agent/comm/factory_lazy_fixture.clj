(ns isaac.agent.comm.factory-lazy-fixture
  "Fixture namespace loaded lazily by isaac.agent.comm.factory/ensure-impl!."
  (:require [isaac.agent.comm.factory :as factory]))

(defmethod factory/create :lazyimpl [_path _slice] ::lazy)
