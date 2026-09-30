(ns isaac.agent.config.runtime-spec
  (:require
    [isaac.agent.config.runtime :as sut]
    [isaac.agent.config.install :as install]
    [speclj.core :refer :all]))

(describe "isaac.agent.config.runtime"

  (describe "install delegates"

    (it "install! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/install! (fn [opts] (reset! called opts) ::installed)]
          (should= ::installed (sut/install! {:config :cfg}))
          (should= {:config :cfg} @called))))))