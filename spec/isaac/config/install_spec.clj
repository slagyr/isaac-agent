(ns isaac.agent.config.install-spec
  (:require
    [isaac.agent.config.install :as sut]
    [isaac.config.loader :as config]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "config install coordinator"

  (around [it]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (config/set-snapshot! nil "spec")
      (it)))

  (context "install!"

    (it "does not commit the snapshot — the caller commits before installing"
      (sut/install! {:config {:defaults {:crew "main"}}})
      (should-be-nil (config/snapshot "spec")))

    (it "registers a session store when root is known"
      (should-be-nil (store/registered-store))
      (sut/install! {:config {:root "/test/isaac"}})
      (should-not-be-nil (store/registered-store)))

    (it "skips store registration when no root"
      (sut/install! {:config {}})
      (should-be-nil (store/registered-store)))

    (it "returns the config"
      (let [result (sut/install! {:config {:defaults {}}})]
        (should= {:defaults {}} (:config result))))))