(ns isaac.config.install-spec
  (:require
    [speclj.core :refer :all]
    [isaac.config.install :as sut]
    [isaac.config.loader :as config]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]))

(defn- fake-component [started]
  (reify reconfigurable/Reconfigurable
    (on-load [_ slice] (reset! started slice))
    (on-config-change! [_ _old _new] nil)
    (on-unload [_ _] nil)))

(describe "config install coordinator"

  (around [it]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (config/set-snapshot! nil "spec")
      (it)))

  (context "install!"

    (it "does not commit the snapshot — the caller commits before reconciling"
      (sut/install! {:config {:defaults {:crew "main"}}})
      (should-be-nil (config/snapshot "spec")))

    (it "registers a session store when root is known"
      (should-be-nil (store/registered-store))
      (sut/install! {:config {:root "/test/isaac"}})
      (should-not-be-nil (store/registered-store)))

    (it "skips store registration when no root"
      (sut/install! {:config {}})
      (should-be-nil (store/registered-store)))

    (it "reconciles injected registries into the nexus"
      (let [started  (atom nil)
            registry {:kind    :component
                      :path    [:thing]
                      :impl    "thing"
                      :factory (fn [_host] (fake-component started))}]
        (sut/install! {:config {:thing {:a 1}} :registries [registry] :host {}})
        (should= {:a 1} @started)
        (should-not-be-nil (nexus/get-in [:thing]))))

    (it "returns the config"
      (let [result (sut/install! {:config {:defaults {}}})]
        (should= {:defaults {}} (:config result)))))

  (context "load-and-install!"

    (it "loads via the loader and installs, surfacing loader errors"
      (let [result (sut/load-and-install! {:home "/test"})]
        (should (seq (:errors result)))
        (should-not-be-nil (config/snapshot "spec"))))))
