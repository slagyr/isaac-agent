;; mutation-tested: 2026-05-06
(ns isaac.tool.fs-bounds
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.tool.names :as names]
    [isaac.nexus :as nexus])
  (:import
    [java.io File]))

(defn canonical-path [path]
  (try
    (.getCanonicalPath (io/file path))
    (catch Exception _
      path)))

(defn path-inside? [parent child]
  (let [parent (canonical-path parent)
        child  (canonical-path child)]
    (or (= parent child)
        (str/starts-with? child (str parent File/separator)))))

(defn config-directories [root]
  #{(str root "/config")})

(defn crew-quarters [root crew-id]
  (str root "/crew/" crew-id))

(defn string-key-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))

(defn filesystem [args]
  (let [args (string-key-map args)]
    (or (get args "fs")
        (fs/instance)
        (throw (ex-info "fs-bounds requires :fs in args or system" {})))))

(defn root [args]
  (let [args (string-key-map args)]
    (or (get args "state_dir")
        (loader/root))))

(defn session-store [args]
  (let [args      (string-key-map args)
        root (root args)]
    (or (get args "session_store")
        (nexus/get-in [:sessions :store])
        (when root
          (store/create root)))))

(defn arg-bool [args k default]
  (let [value (get args k)]
    (cond
      (nil? value)     default
      (boolean? value) value
      (string? value)  (= "true" (str/lower-case value))
      :else            (boolean value))))

(defn arg-int [args k default]
  (let [value (get args k)]
    (cond
      (nil? value)     default
      (integer? value) value
      (string? value)  (parse-long value)
      :else            default)))

(defn session-workdir
  "Return the session's cwd as a string. For exec, require a real OS directory.
   For fs/* ACL expansion, return the configured cwd even on a mem filesystem."
  ([session-key-or-args]
   (session-workdir session-key-or-args false))
  ([session-key-or-args require-os-dir?]
   (let [args        (if (map? session-key-or-args)
                       (string-key-map session-key-or-args)
                       {"session_key" session-key-or-args})
         session-key (get args "session_key")
         store       (session-store args)]
     (when (and session-key store)
       (when-let [cwd (:cwd (store/get-session store session-key))]
         (if require-os-dir?
           (when (.isDirectory (io/file cwd)) cwd)
           cwd))))))

(defn resolve-path
  "Resolve a path against session-cwd:
   nil/blank/'.' → session-cwd, relative → joined with session-cwd, absolute → as-is.
   Returns nil when both path is nil/blank and session-cwd is nil."
  [path session-cwd]
  (cond
    (or (nil? path) (str/blank? path) (= "." path)) session-cwd
    (.isAbsolute (io/file path))                      path
    session-cwd                                       (str session-cwd
                                                            (when-not (str/ends-with? (str session-cwd) "/") "/")
                                                            path)
    :else                                             path))

(defn- directory-policy [tools]
  (let [directories (:directories tools)]
    (cond
      (map? directories) directories
      (sequential? directories) {:allow (vec directories)}
      :else nil)))

(defn- session-ctx [args session]
  (let [root    (root args)
        crew-id (or (:crew session) "main")]
    {:cwd      (or (:cwd session) (session-workdir args))
     :quarters (when root (crew-quarters root crew-id))}))

(defn path-outside-error [file-path]
  {:isError true :error (str "path outside allowed directories: " file-path)})

(defn ensure-path-allowed [args file-path]
  (when file-path
    (let [args        (string-key-map args)
          session-key (get args "session_key")
          store       (session-store args)
          session     (when (and session-key store)
                        (store/get-session store session-key))]
      (when session
        (let [cfg         (loader/snapshot "tool fs-bounds: directory policy")
              crew-id     (or (:crew session) "main")
              global-dirs (directory-policy (:tools cfg))
              crew-dirs   (directory-policy (get-in cfg [:crew crew-id :tools]))
              ctx         (session-ctx args session)]
          (when-not (names/path-allowed? global-dirs crew-dirs file-path ctx)
            (path-outside-error file-path)))))))
