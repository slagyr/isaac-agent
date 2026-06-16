(ns isaac.api-spec
  (:require
    [isaac.api :as sut]
    [isaac.bridge.core]
    [isaac.comm.protocol :as comm-impl]
    [isaac.comm.registry :as registry]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.session.store.memory :as memory]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around describe it should should-not should=]]))

(describe "isaac.api"

  (describe "Comm"

    (it "re-exports the same named protocol"
      (should= (:name comm-impl/Comm) (:name sut/Comm))
      (should= (set (keys (:sigs comm-impl/Comm))) (set (keys (:sigs sut/Comm)))))

    (it "a type implementing api/Comm satisfies api/Comm"
      (let [r (reify sut/Comm
                (on-turn-start [_ _ _] nil)
                (on-text-chunk [_ _ _] nil)
                (on-tool-call [_ _ _] nil)
                (on-tool-cancel [_ _ _] nil)
                (on-tool-result [_ _ _ _] nil)
                (on-compaction-start [_ _ _] nil)
                (on-compaction-success [_ _ _] nil)
                (on-compaction-failure [_ _ _] nil)
                (on-compaction-disabled [_ _ _] nil)
                (on-turn-end [_ _ _] nil))]
        (should (satisfies? sut/Comm r)))))

  (describe "Reconfigurable"

    (it "re-exports the public protocol"
      (should= (:name reconfigurable/Reconfigurable) (:name sut/Reconfigurable))
      (should= (set (keys (:sigs reconfigurable/Reconfigurable)))
               (set (keys (:sigs sut/Reconfigurable)))))

    (it "a type implementing api/Reconfigurable satisfies api/Reconfigurable"
      (let [r (reify sut/Reconfigurable
                (on-load [_ _] :loaded)
                (on-config-change! [_ _ _] :changed)
                (on-unload [_ _] :unloaded))]
        (should (satisfies? sut/Reconfigurable r)))))

  (describe "comm registry delegates"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (binding [registry/*registry* (atom (registry/fresh-registry))]
        (example)))

    (it "register-comm! delegates to comm.registry"
      (sut/register-comm! "parrot" identity)
      (should (registry/registered? "parrot")))

    (it "comm-registered? delegates to comm.registry"
      (registry/register-factory! "parrot" identity)
      (should (sut/comm-registered? "parrot"))
      (should-not (sut/comm-registered? "ghost"))))

  (describe "session delegates"

    (it "create-session! delegates to session store"
      (let [called (atom nil)]
        (with-redefs [sidecar-store/create-store (fn [root] [:store root])
                      store/open-session!      (fn [& args] (reset! called (vec args)) {:id "s1"})]
          (sut/create-session! "/sdir" "my-session" {:crew "main"}))
        (should= [[:store "/sdir"] "my-session" {:crew "main"}] @called)))

    (it "get-session delegates to session store"
      (let [called (atom nil)]
        (with-redefs [sidecar-store/create-store (fn [root] [:store root])
                      store/get-session       (fn [session-store id] (reset! called [session-store id]) {:id "s1"})]
          (sut/get-session "/sdir" "my-session"))
        (should= [[:store "/sdir"] "my-session"] @called))))

  (it "create-session! uses the installed runtime session store"
    (let [session-store (memory/create-store "/tmp/api-spec")]
      (nexus/-with-nexus {:root "/tmp/api-spec" :sessions {:store session-store} :fs (fs/mem-fs)}
        (sut/create-session! "api-session" {:crew "main"})
        (should= "main" (:crew (store/get-session session-store "api-session"))))))

  (it "dispatch! forwards the installed runtime to bridge dispatch"
    (let [captured (atom nil)]
      (nexus/-with-nexus {:root "/tmp/api-spec" :sessions {:store :runtime-store} :fs (fs/mem-fs)}
        (with-redefs [isaac.bridge.core/dispatch! (fn [request]
                                                    (reset! captured request)
                                                    {:ok true})]
          (sut/dispatch! {:session-key "s" :input "hi"})
          (should= "/tmp/api-spec" (:root @captured))
          (should= :runtime-store (get-in @captured [:sessions :store])))))))
