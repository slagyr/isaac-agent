(ns isaac.legacy-api-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "legacy API compatibility"
  (it "returns the bound filesystem instance"
    (let [mem-fs (fs/mem-fs)]
      (binding [fs/*fs* mem-fs]
        (should= mem-fs (fs/instance)))))

  (it "exposes load-config as a config-only wrapper"
    (with-redefs [loader/load-config-result (fn [opts]
                                              {:config {:loaded-from opts}
                                               :errors []})]
      (should= {:loaded-from {:home "/tmp/example"}}
               (loader/load-config {:home "/tmp/example"})))))
