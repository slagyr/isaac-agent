(ns isaac.slash.builtin-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.effort :as effort]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [isaac.slash.builtin :as sut]
    [speclj.core :refer :all]))

(def test-dir "/test/slash-builtin")

(defn- ctx []
  (let [session-store (memory/create-store)]
    {:root test-dir :session-store session-store}))

(defn- create-session! [session-key session-store]
  (store/open-session! session-store session-key {:crew "main"}))

(describe "slash builtin"

  (describe "resolve-cwd-path"

    (it "keeps absolute paths"
      (should= "/tmp/workspace"
               (#'sut/resolve-cwd-path {:root test-dir} "/tmp/workspace")))

    (it "expands home-relative paths"
      (with-redefs [root/user-home (fn [] "/Users/spec")]
        (should= "/Users/spec/project"
                 (#'sut/resolve-cwd-path {:root test-dir} "~/project"))))

    (it "resolves relative paths from root"
      (should= (str test-dir "/workspace")
               (#'sut/resolve-cwd-path {:root test-dir} "workspace")))

    (it "throws for relative paths without root"
      (with-redefs [loader/root (fn [] nil)
                    nexus/get        (fn [_] nil)]
        (should-throw clojure.lang.ExceptionInfo
                      "cwd command requires :root for relative paths"
                      (#'sut/resolve-cwd-path {} "workspace")))))

  (describe "handle-cwd"

    (it "shows the current working directory"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "cwd-show" session-store)
        (store/update-session! session-store "cwd-show" {:cwd "/tmp/workspace"})
        (should= {:type :command :command :cwd :message "current directory: /tmp/workspace"}
                 (sut/handle-cwd "cwd-show" "/cwd" ctx))))

    (it "updates cwd when the resolved directory exists"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "cwd-set" session-store)
        (with-redefs [fs/dir? (fn [_ path] (= (str test-dir "/workspace") path))]
          (should= {:type :command :command :cwd :message (str "working directory set to " test-dir "/workspace")}
                   (sut/handle-cwd "cwd-set" "/cwd workspace" ctx))
          (should= (str test-dir "/workspace") (:cwd (store/get-session session-store "cwd-set"))))))

    (it "reports unknown directories"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "cwd-missing" session-store)
        (with-redefs [fs/dir? (constantly false)]
          (should= {:type :command :command :unknown :message "no such directory: missing"}
                   (sut/handle-cwd "cwd-missing" "/cwd missing" ctx))))))

  (describe "handle-effort"

    (it "shows the current effort using the session override when present"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "effort-show" session-store)
        (store/update-session! session-store "effort-show" {:effort 3})
        (should= {:type :command :command :effort :message "current effort: 3"}
                 (#'sut/handle-effort "effort-show" "/effort" ctx))))

    (it "shows the default effort when the session has none"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "effort-default" session-store)
        (should= {:type :command :command :effort :message (str "current effort: " effort/default-effort)}
                 (#'sut/handle-effort "effort-default" "/effort" ctx))))

    (it "clears effort"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "effort-clear" session-store)
        (store/update-session! session-store "effort-clear" {:effort 5})
        (should= {:type :command :command :effort :message "effort cleared"}
                 (#'sut/handle-effort "effort-clear" "/effort clear" ctx))
        (should= nil (:effort (store/get-session session-store "effort-clear")))))

    (it "sets effort within range"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "effort-set" session-store)
        (should= {:type :command :command :effort :message "effort set to 10"}
                 (#'sut/handle-effort "effort-set" "/effort 10" ctx))
        (should= 10 (:effort (store/get-session session-store "effort-set")))))

    (it "rejects invalid effort values"
      (let [{:keys [session-store] :as ctx} (ctx)]
        (create-session! "effort-bad" session-store)
        (should= {:type :command :command :unknown :message "effort must be between 0 and 10"}
                 (#'sut/handle-effort "effort-bad" "/effort eleven" ctx))
        (should= {:type :command :command :unknown :message "effort must be between 0 and 10"}
                 (#'sut/handle-effort "effort-bad" "/effort 11" ctx))))))
