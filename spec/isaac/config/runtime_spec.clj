(ns isaac.config.runtime-spec
  (:require
    [isaac.config.change-source :as change-source]
    [isaac.config.configurator :as configurator]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.config.install :as install]
    [isaac.config.runtime :as sut]
    [speclj.core :refer :all]))

;; config.runtime is a pure facade: each fn forwards to its source at call
;; time so `with-redefs` on the underlying fn takes effect through the API.
;; These specs pin that delegation contract — args forwarded unchanged, the
;; source's return value passed back — for every public fn.

(describe "isaac.config.runtime"

  (describe "install delegates"

    (it "install! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/install! (fn [opts] (reset! called opts) ::installed)]
          (should= ::installed (sut/install! {:config :cfg}))
          (should= {:config :cfg} @called))))

    (it "install-config-berths! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/install-config-berths! (fn [opts] (reset! called opts) ::berths)]
          (should= ::berths (sut/install-config-berths! {:config :cfg :module-index :mi}))
          (should= {:config :cfg :module-index :mi} @called))))

    (it "reload! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/reload! (fn [opts] (reset! called opts) ::reloaded)]
          (should= ::reloaded (sut/reload! {:root "/r"}))
          (should= {:root "/r"} @called))))

    (it "validate-config! delegates to config.install"
      (let [called (atom nil)]
        (with-redefs [install/validate-config! (fn [cfg reg] (reset! called [cfg reg]) [::err])]
          (should= [::err] (sut/validate-config! :cfg :registry))
          (should= [:cfg :registry] @called)))))

  (describe "Reconfigurable"

    (it "re-exports the public protocol"
      (should= (:name reconfigurable/Reconfigurable) (:name sut/Reconfigurable))
      (should= (set (keys (:sigs reconfigurable/Reconfigurable)))
               (set (keys (:sigs sut/Reconfigurable)))))

    (it "on-load delegates to the instance's protocol method"
      (let [called   (atom nil)
            instance (reify sut/Reconfigurable
                       (on-load [_ slice] (reset! called slice) ::loaded)
                       (on-config-change! [_ _ _] nil)
                       (on-unload [_ _] nil))]
        (should= ::loaded (sut/on-load instance {:a 1}))
        (should= {:a 1} @called)))

    (it "on-unload delegates to the instance's protocol method"
      (let [called   (atom nil)
            instance (reify sut/Reconfigurable
                       (on-load [_ _] nil)
                       (on-config-change! [_ _ _] nil)
                       (on-unload [_ slice] (reset! called slice) ::unloaded))]
        (should= ::unloaded (sut/on-unload instance {:a 1}))
        (should= {:a 1} @called)))

    (it "on-config-change! delegates to the instance's protocol method"
      (let [called   (atom nil)
            instance (reify sut/Reconfigurable
                       (on-load [_ _] nil)
                       (on-config-change! [_ old new] (reset! called [old new]) ::changed)
                       (on-unload [_ _] nil))]
        (should= ::changed (sut/on-config-change! instance {:old 1} {:new 2}))
        (should= [{:old 1} {:new 2}] @called))))

  (describe "configurator delegates"

    (it "reconcile! delegates to configurator"
      (let [called (atom nil)]
        (with-redefs [configurator/reconcile! (fn [host old new reg]
                                                (reset! called [host old new reg])
                                                ::reconciled)]
          (should= ::reconciled (sut/reconcile! :host :old :new :reg))
          (should= [:host :old :new :reg] @called))))

    (it "slot-impl delegates to configurator"
      (let [called (atom nil)]
        (with-redefs [configurator/slot-impl (fn [slot slice] (reset! called [slot slice]) ::impl)]
          (should= ::impl (sut/slot-impl :slot :slice))
          (should= [:slot :slice] @called))))

    (it "->name delegates to configurator"
      (with-redefs [configurator/->name (fn [x] [::name x])]
        (should= [::name :foo] (sut/->name :foo)))))

  (describe "change-source delegates"

    (it "watch-service-source delegates to change-source"
      (let [called (atom nil)]
        (with-redefs [change-source/watch-service-source (fn [root] (reset! called root) ::watcher)]
          (should= ::watcher (sut/watch-service-source "/root"))
          (should= "/root" @called))))

    (it "memory-source delegates to change-source"
      (let [called (atom nil)]
        (with-redefs [change-source/memory-source (fn [root] (reset! called root) ::memsource)]
          (should= ::memsource (sut/memory-source "/root"))
          (should= "/root" @called))))

    (it "start! delegates to change-source"
      (let [called (atom nil)]
        (with-redefs [change-source/start! (fn [source] (reset! called source) ::started)]
          (should= ::started (sut/start! ::src))
          (should= ::src @called))))

    (it "stop! delegates to change-source"
      (let [called (atom nil)]
        (with-redefs [change-source/stop! (fn [source] (reset! called source) ::stopped)]
          (should= ::stopped (sut/stop! ::src))
          (should= ::src @called))))

    (it "poll! delegates to change-source with the default timeout"
      (let [called (atom nil)]
        (with-redefs [change-source/poll! (fn [source] (reset! called source) ::polled)]
          (should= ::polled (sut/poll! ::src))
          (should= ::src @called))))

    (it "poll! delegates to change-source with an explicit timeout"
      (let [called (atom nil)]
        (with-redefs [change-source/poll! (fn [source timeout] (reset! called [source timeout]) ::polled)]
          (should= ::polled (sut/poll! ::src 500))
          (should= [::src 500] @called))))

    (it "notify-path! delegates to change-source"
      (let [called (atom nil)]
        (with-redefs [change-source/notify-path! (fn [source path] (reset! called [source path]) ::notified)]
          (should= ::notified (sut/notify-path! ::src "/path"))
          (should= [::src "/path"] @called))))))
