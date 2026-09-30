(ns isaac.agent.legacy-api-spec
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "legacy API compatibility"
  (it "returns the installed filesystem instance"
    (let [mem-fs (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem-fs}
        (should= mem-fs (fs/instance)))))

  (it "exposes load-config as a config-only wrapper"
    (with-redefs [loader/load-config-result (fn [opts]
                                              {:config {:loaded-from opts}
                                               :errors []})]
      (should= {:loaded-from {:home "/tmp/example"}}
               (loader/load-config {:home "/tmp/example"})))))
