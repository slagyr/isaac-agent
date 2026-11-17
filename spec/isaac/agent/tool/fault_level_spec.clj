(ns isaac.agent.tool.fault-level-spec
  "Which real server faults reach the caller as
   {:isError true} and which stay at :error (isError + :fault)."
  (:require
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.tool.exec :as exec]
    [isaac.agent.tool.registry :as sut]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- with-tool-registry [f]
  (nexus/-with-nexus {:tool-registry (atom {})}
    (f)))

(defn- logged-at [level]
  (filter #(= level (:level %)) @log/captured-logs))

(describe "tool fault levels"

  (around [it] (with-tool-registry it))
  (before (sut/clear!))
  (helper/with-captured-logs)

  (it "an Isaac bug inside exec__run (NPE while starting the process) is logged at :error"
    (sut/register! {:name "exec__run" :handler exec/exec-tool})
    (with-redefs [exec/start-process (fn [_] (throw (NullPointerException. "isaac bug")))]
      (should (:isError (sut/execute "exec__run" {"command" "true"}))))
    (should (seq (logged-at :error))))

  (it "exec__run whose spawn the OS refuses (IOException) is a tool outcome at info"
    (sut/register! {:name "exec__run" :handler exec/exec-tool})
    (with-redefs [exec/start-process (fn [_] (throw (java.io.IOException. "Cannot run program")))]
      (should (:isError (sut/execute "exec__run" {"command" "true"}))))
    (should= [] (logged-at :error))
    (should (some #(= :tool/error-result (:event %)) @log/captured-logs)))

  (it "a handler-marked fault is logged at :error and :fault never reaches the caller"
    (sut/register! {:name "faulty" :handler (fn [_] {:isError true :error "caught crash" :fault true})})
    (should= {:isError true :error "caught crash"} (sut/execute "faulty" {}))
    (should= :tool/execute-failed (:event (first (logged-at :error)))))

  (it "a plain non-zero exit is not an :error"
    (sut/register! {:name "exec__run" :handler exec/exec-tool})
    (sut/execute "exec__run" {"command" "exit 3"})
    (should= [] (logged-at :error)))

  (it "a handler that throws past its own catch is still :error"
    (sut/register! {:name "boom" :handler (fn [_] (throw (IllegalStateException. "kaboom")))})
    (sut/execute "boom" {})
    (should= :tool/execute-failed (:event (first (logged-at :error))))))
