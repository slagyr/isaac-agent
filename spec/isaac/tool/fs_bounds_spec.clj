(ns isaac.tool.fs-bounds-spec
  (:require
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.nexus :as nexus]
    [isaac.tool.fs-bounds :as sut]
    [speclj.core :refer [describe it should should=]]))

(describe "tool fs bounds"

  (it "prefers the explicit state_dir arg over the installed runtime"
    (nexus/-with-nexus {:root "/test/runtime"}
      (should= "/test/explicit"
               (sut/root {"state_dir" "/test/explicit"}))))

  (it "uses the installed runtime session store when args omit it"
    (let [session-store (store/create nil :memory)]
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store}}
        (should= session-store
                 (sut/session-store {"session_key" "chat-1"})))))

  (it "uses the installed runtime fs when args omit it"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (should= mem
                 (sut/filesystem {"session_key" "chat-1"}))))))

  (it "creates crew quarters through the installed runtime fs"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)]
      (store/open-session! session-store "chat-1" {:crew marigold/captain})
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store} :fs mem}
        (config/dangerously-install-config! {:crew {marigold/captain {:tools {:directories []}}}} "spec")
        (should= [(str "/test/runtime/crew/" marigold/captain)]
                 (sut/allowed-directories {"session_key" "chat-1"}))
        #_{:clj-kondo/ignore [:invalid-arity]}
        (should (fs/exists? mem (str "/test/runtime/crew/" marigold/captain))))))
