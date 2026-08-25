(ns isaac.tool.fs-bounds-spec
  (:require
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.nexus :as nexus]
    [isaac.tool.fs-bounds :as sut]
    [speclj.core :refer [describe it should= should-be-nil should-not should]]))

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
                 (sut/filesystem {"session_key" "chat-1"})))))

  (it "denies every path when no directory grants exist"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)]
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store} :fs mem}
        (store/open-session! session-store "chat-1" {:crew marigold/captain :cwd "/work/project"})
        (config/dangerously-install-config! {:crew {marigold/captain {:tools {:allow [:fs/read]}}}} "spec")
        (should-not (nil? (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                   "/work/project/hello.txt"))))))

  (it "allows session cwd when global directories allow :cwd"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)]
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store} :fs mem}
        (store/open-session! session-store "chat-1" {:crew marigold/captain :cwd "/work/project"})
        (config/dangerously-install-config! {:tools {:directories {:allow [:cwd]}}
                                             :crew  {marigold/captain {:tools {:allow [:fs/read]}}}} "spec")
        (should-be-nil (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                "/work/project/hello.txt"))
        (should-not (nil? (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                   "/outside/secret.txt"))))))

  (it "allows crew quarters when global directories allow :quarters"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)
          quarters      (str "/test/runtime/crew/" marigold/captain)]
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store} :fs mem}
        (store/open-session! session-store "chat-1" {:crew marigold/captain :cwd "/work/project"})
        (config/dangerously-install-config! {:tools {:directories {:allow [:quarters]}}
                                             :crew  {marigold/captain {:tools {:allow [:fs/read]}}}} "spec")
        (should-be-nil (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                (str quarters "/notes.txt")))
        (should-not (nil? (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                   "/work/project/hello.txt"))))))

  (it "denies a path outside a global :cwd grant even when the file exists"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)]
      (nexus/-with-nexus {:root "/isaac-state" :sessions {:store session-store} :fs mem}
        (store/open-session! session-store "fence-test" {:crew "main" :cwd "/work/project"})
        (config/dangerously-install-config!
          {:defaults {:crew :main :model :echo}
           :tools    {:directories {:allow [:cwd]}}
           :crew     {"main" {:tools {:allow [:fs/read]}}}}
          "spec")
        (should-be-nil (sut/ensure-path-allowed {"session_key" "fence-test"}
                                                "/work/project/hello.txt"))
        (let [denied (sut/ensure-path-allowed {"session_key" "fence-test"}
                                              "/outside/secret.txt")]
          (should-not (nil? denied))
          (should (:isError denied))))))

  (it "does not implicitly grant quarters or cwd"
    (let [mem           (fs/mem-fs)
          session-store (store/create nil :memory)]
      (nexus/-with-nexus {:root "/test/runtime" :sessions {:store session-store} :fs mem}
        (store/open-session! session-store "chat-1" {:crew marigold/captain :cwd "/work/project"})
        (config/dangerously-install-config! {:crew {marigold/captain {:tools {:directories []}}}} "spec")
        (should-not (nil? (sut/ensure-path-allowed {"session_key" "chat-1"}
                                                   (str "/test/runtime/crew/" marigold/captain "/notes.txt")))))))
)
