(ns isaac.tool.exec-spec
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.bridge.cancellation :as bridge]
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.tool.exec :as sut]
    [isaac.tool.support :as support]
    [speclj.core :refer :all]))

(describe "Exec tool"
  (before (support/clean!))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (helper/with-memory-store
      (nexus/-with-nested-nexus {:root support/test-dir}
        (example))))

  (it "runs a shell command and returns output"
    (let [result (with-redefs [sut/start-process (fn [_] ::proc)
                               sut/process-finished? (fn [_ _] true)
                               sut/read-process-output (fn [_] "hello world\n")
                               sut/process-exit-value (fn [_] 0)]
                   (sut/exec-tool {"command" "echo hello world"}))]
      (should-be-nil (:isError result))
      (should (str/includes? (:result result) "hello world"))))

  (it "returns error on non-zero exit"
    (let [result (with-redefs [sut/start-process (fn [_] ::proc)
                               sut/process-finished? (fn [_ _] true)
                               sut/read-process-output (fn [_] "boom\n")
                               sut/process-exit-value (fn [_] 1)]
                   (sut/exec-tool {"command" "exit 1"}))]
      (should (:isError result))))

  (it "respects workdir option"
    (let [captured-workdir (atom nil)
          result           (with-redefs [sut/start-process (fn [args]
                                                             (reset! captured-workdir (get args "workdir"))
                                                             ::proc)
                                         sut/process-finished? (fn [_ _] true)
                                         sut/read-process-output (fn [_] "target.txt\n")
                                         sut/process-exit-value (fn [_] 0)]
                              (sut/exec-tool {"command" "ls" "workdir" (str support/test-dir "/subdir")}))]
      (should= (str support/test-dir "/subdir") @captured-workdir)
      (should (str/includes? (:result result) "target.txt"))))

  (it "uses the session cwd as implicit workdir when none is provided"
    (let [captured-workdir (atom nil)
          session-key      "exec-session"
          cwd              (str support/test-dir "/exec-cwd")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file cwd))
      (let [result (with-redefs [sut/start-process (fn [args]
                                                     (reset! captured-workdir (get args "workdir"))
                                                     ::proc)
                                 sut/process-finished? (fn [_ _] true)
                                 sut/read-process-output (fn [_] "ok\n")
                                 sut/process-exit-value (fn [_] 0)]
                     (sut/exec-tool {"command" "pwd" "session_key" session-key}))]
        (should= cwd @captured-workdir)
        (should= "ok" (:result result)))))

  (it "prefers explicit workdir over the session cwd when it stays inside the role workspace"
    (let [captured-workdir (atom nil)
          session-key      "exec-session-explicit"
          cwd              (str support/test-dir "/exec-cwd")
          explicit         (str cwd "/nested")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file explicit))
      (let [result (with-redefs [sut/start-process (fn [args]
                                                     (reset! captured-workdir (get args "workdir"))
                                                     ::proc)
                                 sut/process-finished? (fn [_ _] true)
                                 sut/read-process-output (fn [_] "ok\n")
                                 sut/process-exit-value (fn [_] 0)]
                     (sut/exec-tool {"command" "pwd"
                                     "workdir" explicit
                                     "session_key" session-key
                                    }))]
        (should= explicit @captured-workdir)
        (should= "ok" (:result result)))))

  (it "rejects a workdir outside the role workspace"
    (let [session-key "exec-session-outside"
          cwd         (str support/test-dir "/exec-cwd-sandbox")
          outside     (str support/test-dir "/outside-sandbox")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file cwd))
      (.mkdirs (io/file outside))
      (let [result (sut/exec-tool {"command" "pwd"
                                   "workdir" outside
                                   "session_key" session-key})]
        (should (:isError result))
        (should (re-find #"path outside allowed directories" (:error result))))))

  (it "ignores the session cwd when it is not a directory"
    (let [captured-workdir (atom ::unset)
          session-key      "exec-session-missing-cwd"
          cwd              (str support/test-dir "/missing-dir")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (let [result (with-redefs [sut/start-process (fn [args]
                                                     (reset! captured-workdir (get args "workdir" ::missing))
                                                     ::proc)
                                 sut/process-finished? (fn [_ _] true)
                                 sut/read-process-output (fn [_] "ok\n")
                                 sut/process-exit-value (fn [_] 0)]
                     (sut/exec-tool {"command" "pwd" "session_key" session-key}))]
        (should= ::missing @captured-workdir)
        (should= "ok" (:result result)))))

  (it "resolves workdir \".\" to the session cwd"
    (let [captured-workdir (atom nil)
          session-key      "exec-session-dot"
          cwd              (str support/test-dir "/exec-cwd-dot")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file cwd))
      (with-redefs [sut/start-process (fn [args]
                                        (reset! captured-workdir (get args "workdir"))
                                        ::proc)
                    sut/process-finished? (fn [_ _] true)
                    sut/read-process-output (fn [_] "ok\n")
                    sut/process-exit-value (fn [_] 0)]
        (sut/exec-tool {"command" "pwd" "workdir" "." "session_key" session-key}))
      (should= cwd @captured-workdir)))

  (it "resolves an empty workdir string to the session cwd"
    (let [captured-workdir (atom nil)
          session-key      "exec-session-empty"
          cwd              (str support/test-dir "/exec-cwd-empty")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file cwd))
      (with-redefs [sut/start-process (fn [args]
                                        (reset! captured-workdir (get args "workdir"))
                                        ::proc)
                    sut/process-finished? (fn [_ _] true)
                    sut/read-process-output (fn [_] "ok\n")
                    sut/process-exit-value (fn [_] 0)]
        (sut/exec-tool {"command" "pwd" "workdir" "" "session_key" session-key}))
      (should= cwd @captured-workdir)))

  (it "resolves a relative workdir against the session cwd"
    (let [captured-workdir (atom nil)
          session-key      "exec-session-rel"
          cwd              (str support/test-dir "/exec-cwd-rel")]
      (helper/create-session! support/test-dir session-key {:crew "main" :cwd cwd})
      (.mkdirs (io/file cwd "sub"))
      (with-redefs [sut/start-process (fn [args]
                                        (reset! captured-workdir (get args "workdir"))
                                        ::proc)
                    sut/process-finished? (fn [_ _] true)
                    sut/read-process-output (fn [_] "ok\n")
                    sut/process-exit-value (fn [_] 0)]
        (sut/exec-tool {"command" "pwd" "workdir" "sub" "session_key" session-key}))
      (should= (.getCanonicalPath (io/file cwd "sub")) @captured-workdir)))

  (it "falls back to the default timeout when timeout is not an integer"
    (let [polls (atom [])]
      (with-redefs [sut/start-process (fn [_] ::proc)
                     sut/process-finished? (fn [_ wait-ms] (swap! polls conj wait-ms) true)
                     sut/read-process-output (fn [_] "ok\n")
                     sut/process-exit-value (fn [_] 0)]
        (should= "ok" (:result (sut/exec-tool {"command" "pwd" "timeout" "bogus"}))))
      (should= [30000] @polls)))

  (it "returns an error when process startup throws"
    (let [result (with-redefs [sut/start-process (fn [_] (throw (ex-info "boom" {})))]
                   (sut/exec-tool {"command" "ignored"}))]
      (should (:isError result))
      (should= "boom" (:error result))))

  (it "captures stderr in the output"
    (let [result (with-redefs [sut/start-process (fn [_] ::proc)
                               sut/process-finished? (fn [_ _] true)
                               sut/read-process-output (fn [_] "err\n")
                               sut/process-exit-value (fn [_] 0)]
                   (sut/exec-tool {"command" "echo err >&2"}))]
      (should (string? (:result result)))))

  (it "returns error on timeout"
    (let [destroyed (atom false)
          result    (with-redefs [sut/start-process (fn [_] ::proc)
                                  sut/process-finished? (fn [_ _] @destroyed)
                                  sut/destroy-process! (fn [_ _] (reset! destroyed true))]
                      (sut/exec-tool {"command" "ignored" "timeout" 1}))]
      (should (:isError result))
      (should (re-find #"(?i)timeout" (:error result)))))

  (it "waits once for the requested timeout window before timing out"
    (let [polls  (atom [])
          result (with-redefs [sut/start-process (fn [_] ::proc)
                                sut/process-finished? (fn [_ wait-ms] (swap! polls conj wait-ms) false)
                                sut/destroy-process! (fn [& _] nil)]
                    (sut/exec-tool {"command" "ignored" "timeout" 75}))]
      (should (:isError result))
      (should= [75] @polls)))

  (it "uses shortened cleanup grace on timeout"
    (let [destroyed (atom false)
          grace-ms  (atom nil)
          result    (with-redefs [sut/start-process (fn [_] ::proc)
                                  sut/process-finished? (fn [_ _] @destroyed)
                                  sut/destroy-process! (fn [_ ms]
                                                         (reset! grace-ms ms)
                                                         (reset! destroyed true))]
                      (sut/exec-tool {"command" "ignored" "timeout" 1}))]
      (should (:isError result))
      (should= 10 @grace-ms)))

  (it "returns cancelled when the session is cancelled mid-command"
    (let [turn       (bridge/begin-turn! "exec-cancel")
          started?   (promise)
          destroyed? (promise)
          result     (future
                       (with-redefs [sut/start-process (fn [_] (deliver started? true) ::proc)
                                     sut/process-finished? (fn [_ _] (deref destroyed? 1000 false))
                                     sut/destroy-process! (fn [& _] (deliver destroyed? true))]
                         (sut/exec-tool {"command" "ignored" "session_key" "exec-cancel"})))]
      @started?
      (bridge/cancel! "exec-cancel")
      (should= :cancelled (:error (deref result 1000 nil)))
      (bridge/end-turn! "exec-cancel" turn))))
