(ns isaac.session.spec-helper
  "Session-store test helpers, split out of isaac.spec-helper so the
   foundation scaffolding (logs/config/await) stays free of session.store
   requires. These move with the session/server code at extraction time."
  (:require
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.session.store.memory :as memory]
    [isaac.nexus :as nexus]))

(def ^:dynamic *session-store* nil)

(defn- session-store [root]
  (or *session-store*
      (sidecar-store/create-store root)))

(defmacro with-memory-store [& body]
  ;; Install a fresh mem-fs so the body's writes/reads are isolated from
  ;; whatever fs an earlier spec might have leaked into the global nexus
  ;; (e.g., a real-fs from app/start! → nexus/init!). Outer fixtures that
  ;; need a specific fs should install it inside the body, not rely on
  ;; with-memory-store inheriting it.
  `(let [mem-store# (memory/create-store (loader/root))]
     (nexus/-with-nested-nexus {:fs       (fs/mem-fs)
                                 :sessions {:store mem-store#}}
       (binding [*session-store* (store/registered-store)]
         (with-redefs [sidecar-store/create-store (fn [& _#] *session-store*)]
           ~@body)))))

(defn create-session!
  ([root identifier]
   (create-session! root identifier {}))
  ([root identifier opts]
   (store/open-session! (session-store root) identifier opts)))

(defn list-sessions
  ([root]
   (store/list-sessions (session-store root)))
  ([root crew-id]
   (store/list-sessions-by-agent (session-store root) crew-id)))

(defn most-recent-session [root]
  (store/most-recent-session (session-store root)))

(defn get-session [root session-key]
  (store/get-session (session-store root) session-key))

(defn get-transcript [root session-key]
  (store/get-transcript (session-store root) session-key))

(defn update-session! [root session-key updates]
  (store/update-session! (session-store root) session-key updates))

(defn append-message! [root session-key message]
  (store/append-message! (session-store root) session-key message))

(defn append-error! [root session-key error-entry]
  (store/append-error! (session-store root) session-key error-entry))

(defn append-compaction! [root session-key compaction]
  (store/append-compaction! (session-store root) session-key compaction))

(defn splice-compaction! [root session-key compaction]
  (store/splice-compaction! (session-store root) session-key compaction))

(defn update-tokens! [root session-key {:keys [cache-read cache-write] :as updates}]
  (let [entry             (or (get-session root session-key) {})
        input-tokens      (:input-tokens updates 0)
        turn-input-tokens (or (:turn-input-tokens updates) input-tokens)
        last-input-tokens (or (:last-input-tokens updates) input-tokens)
        output-tokens     (:output-tokens updates 0)]
    (update-session! root session-key
                     (cond-> {:input-tokens      (+ (or (:input-tokens entry) 0) input-tokens)
                              :turn-input-tokens turn-input-tokens
                              :last-input-tokens last-input-tokens
                              :output-tokens     (+ (or (:output-tokens entry) 0) output-tokens)
                              :total-tokens      (+ (+ (or (:input-tokens entry) 0) input-tokens)
                                                    (+ (or (:output-tokens entry) 0) output-tokens))}
                       cache-read  (assoc :cache-read (+ (or (:cache-read entry) 0) cache-read))
                       cache-write (assoc :cache-write (+ (or (:cache-write entry) 0) cache-write))))))
