(ns isaac.config.runtime-spec
  (:require
    [isaac.config.install :as install]
    [isaac.config.runtime :as sut]
    [speclj.core :refer :all]))

(describe "isaac.config.runtime"

  (describe "install delegates"

    (it "install! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/install! (fn [opts] (reset! called opts) ::installed)]
          (should= ::installed (sut/install! {:config :cfg}))
          (should= {:config :cfg} @called))))))