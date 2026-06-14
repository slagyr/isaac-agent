(ns isaac.comm.factory-lazy-fixture
  "Fixture namespace loaded lazily by isaac.comm.factory/ensure-impl!."
  (:require [isaac.comm.factory :as factory]))

(defmethod factory/create :lazyimpl [_path _slice] ::lazy)
