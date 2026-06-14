(ns isaac.spec-helper
  "Foundation-grade test scaffolding: log capture, config provisioning, and
   async-await. Session-store helpers live in isaac.session.spec-helper so this
   namespace stays free of session.store requires (foundation set)."
  (:require
    [isaac.config.api :as config]))

(defmacro with-captured-logs []
  '(speclj.core/around [it] (isaac.logger/capture-logs (it))))

(defmacro with-config
  "Commit `cfg` as the process-wide config snapshot for the duration of `body`.
   The test-setup way to provision config so in-flight readers (which read the
   committed snapshot) see it — replaces stubbing the loader."
  [cfg & body]
  `(do (config/dangerously-install-config! ~cfg "spec")
       ~@body))

(defn await-condition
  "Polls pred every 1ms until it returns truthy or timeout-ms elapses (default 1000).
  Use this instead of Thread/sleep whenever waiting for async state to change."
  ([pred] (await-condition pred 1000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (when (and (not (pred)) (< (System/currentTimeMillis) deadline))
         (Thread/sleep 1)
         (recur))))))
