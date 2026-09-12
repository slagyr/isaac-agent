(ns isaac.session.store.impl-common
  (:require
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.fs :as fs]
    [isaac.llm.prompt.builder :as prompt-builder]
    [isaac.logger :as log]
    [isaac.naming :as naming]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as session-store]
    [isaac.session.transcript :as transcript])
  (:import
    (java.util UUID)))

;; region ----- Helpers -----

(defn new-id []
  (subs (str (UUID/randomUUID)) 0 8))

(defn new-nonce []
  (str "N0NCE-" (subs (str (UUID/randomUUID)) 0 12)))

(defn parse-long-safe [s]
  (try
    (when (string? s) (Long/parseLong s))
    (catch Exception _ nil)))

(defn normalize-timestamp [ms->iso-fn ts]
  (cond
    (number? ts) (ms->iso-fn ts)
    (string? ts) (if-let [n (parse-long-safe ts)] (ms->iso-fn n) ts)
    :else        ts))

(defn write-edn [v]
  (binding [*print-namespace-maps* false]
    (str (pr-str v) "\n")))

;; tonsky/fast-edn is JSON-speed on the JVM (Java parser). Native bb has no
;; EdnParser class — fall back to clojure.edn there.
(def ^:private fast-read-string
  (try (requiring-resolve 'fast-edn.core/read-string)
       (catch Throwable _ nil)))

(def ^:private fast-parser
  (try (requiring-resolve 'fast-edn.core/parser)
       (catch Throwable _ nil)))

(def ^:private fast-read-next
  (try (requiring-resolve 'fast-edn.core/read-next)
       (catch Throwable _ nil)))

(defn read-edn-line [s]
  (if fast-read-string
    (fast-read-string s)
    (binding [*read-eval* false]
      (edn/read-string s))))

(defn keywordize-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) m)))

(defn- text-blocks? [content]
  (and (vector? content)
       (every? map? content)
       (every? #(contains? % :type) content)))

(def text-content-roles #{"user"})

(defn normalize-message-content [role content]
  (if (contains? text-content-roles role)
    (cond
      (string? content) [{:type "text" :text content}]
      (text-blocks? content) content
      :else content)
    content))

(defn normalize-message [message]
  (let [role (:role message)]
    (cond-> (assoc message :content (normalize-message-content role (:content message)))
      (keyword? (:error message)) (update :error str))))

(defn ceil-chars->tokens [chars]
  (long (Math/ceil (/ (double (max 0 (long chars))) 4.0))))

(defn- text-tokens [text]
  (when (and (string? text) (not (str/blank? text)))
    (ceil-chars->tokens (count text))))

(defn- tool-call-args-text [content]
  (when (vector? content)
    (some->> content
             (filter #(= "toolCall" (:type %)))
             seq
             (map :arguments)
             (map pr-str)
             (str/join "\n"))))

(defn message-tokens [message]
  (let [role    (:role message)
        content (:content message)]
    (or (text-tokens (transcript/content->text content))
        (when (= "toolResult" role)
          (text-tokens (str content)))
        (when (= "assistant" role)
          (text-tokens (tool-call-args-text content))))))

(defn compaction-tokens [{:keys [summary turnRequest] :as compaction}]
  (text-tokens (prompt-builder/compaction-summary-text {:summary summary :turnRequest turnRequest})))

(defn stamp-message-tokens [message]
  (cond-> message
    (nil? (:tokens message)) (assoc :tokens (or (message-tokens message) 0))))

(defn slugify [s]
  (let [slug (-> (or s "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? slug) "session" slug)))

(defn session-id [identifier]
  (slugify identifier))

(defn entry-defaults [opts]
  (merge {:crew      (or (:crew opts) "main")
          :channel   (:channel opts)
          :chat-type (or (:chat-type opts) (:chatType opts))}
         (into {} (remove (comp nil? val) opts))))

(defn effective-config [passed-config]
  (or passed-config
      (loader/snapshot "session store config — ambient fallback when caller passes no :config")
      {}))

(defn resolve-history-retention [opts]
  (resolve/resolve-history-retention (effective-config (:config opts))
                                    (or (:crew opts) "main")
                                    (:history-retention opts)))

(defn conform-session-read [entry]
  (-> entry
      session-schema/conform-read
      session-schema/conform-read))

(defn conform-session! [entry]
  (session-schema/conform! entry))

(defn exists?* [fs path] (fs/exists? fs path))
(defn slurp* [fs path] (fs/slurp fs path))
(defn spit*! [fs path content & options] (apply fs/spit fs path content options))
(defn children* [fs path] (fs/children fs path))
(defn mkdirs*! [fs path] (fs/mkdirs fs path))
(defn delete*! [fs path] (fs/delete fs path))

(defonce ^:private persist-locks* (atom {}))

(defn- persist-lock [session-key]
  (get (swap! persist-locks* update session-key #(or % (Object.))) session-key))

(defn with-persist-lock
  "Serialize every persist and read of a session-key's backing files."
  [session-key f]
  (if session-key
    (locking (persist-lock session-key)
      (f))
    (f)))

(defn- session-key-from-path [path]
  (let [s (str path)]
    (or (when-let [[_ _id] (re-find #"/sessions/[^/]+/([^/]+)/" s)]
          ;; Nested layout: sessions/<crew>/<sid>/... — skip reserved names.
          (when-not (#{"recall" "turns" "index.edn"} _id)
            (when-not (re-matches #".*\.(edn|ednl|jsonl|tmp)$" _id)
              _id)))
        (when-let [[_ id] (re-find #"/sessions/([^/]+)/" s)]
          (when-not (#{"turns" "index.edn"} id)
            (when-not (re-matches #".*\.(edn|ednl|jsonl|tmp)$" id)
              id))))))

(defn atomic-spit!
  "Crash-safe whole-file rewrite: write to path.tmp then rename into place."
  [fs path content]
  (let [write (fn []
                (let [tmp (str path ".tmp")]
                  (mkdirs*! fs (fs/parent path))
                  (spit*! fs tmp content)
                  (fs/move fs tmp path)))]
    (if-let [k (session-key-from-path path)]
      (with-persist-lock k write)
      (write))))

(defn unreadable-session [session-id]
  (ex-info (str "session '" session-id "' is unreadable")
           {:reason :session/unreadable :id session-id}))

(defn delete-tree! [fs path]
  (when (exists?* fs path)
    (doseq [child (or (children* fs path) [])]
      (delete-tree! fs (str path "/" child)))
    (delete*! fs path)))

(defn move-tree! [fs source destination]
  (when (exists?* fs source)
    (if-let [kids (seq (or (children* fs source) []))]
      (do
        (mkdirs*! fs destination)
        (doseq [child kids]
          (move-tree! fs (str source "/" child) (str destination "/" child)))
        (delete-tree! fs source))
      (fs/move fs source destination))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Paths -----

(defn sessions-dir [root]
  (str root "/sessions"))

(defn crew-sessions-dir [root crew]
  (str (sessions-dir root) "/" (name (or crew "main"))))

(defn session-dir
  "Session directory. 3-arity is the nested product layout
   sessions/<crew>/<sid>/. 2-arity is the pre-b6w0 flat layout
   sessions/<sid>/ (legacy reads, unit fixtures, jsonl migrate)."
  ([root session-id]
   (str (sessions-dir root) "/" session-id))
  ([root crew session-id]
   (str (crew-sessions-dir root crew) "/" session-id)))

(defn session-edn-path
  ([root session-id]
   (str (session-dir root session-id) "/session.edn"))
  ([root crew session-id]
   (str (session-dir root crew session-id) "/session.edn")))

(defn sidecar-path
  ([root session-id]
   (session-edn-path root session-id))
  ([root crew session-id]
   (session-edn-path root crew session-id)))

(defn current-transcript-path
  ([root session-id]
   (str (session-dir root session-id) "/current.ednl"))
  ([root crew session-id]
   (str (session-dir root crew session-id) "/current.ednl")))

(defn frozen-transcript-path
  ([root session-id n]
   (str (session-dir root session-id) "/" n ".ednl"))
  ([root crew session-id n]
   (str (session-dir root crew session-id) "/" n ".ednl")))

(defn transcript-path
  ([root session-id]
   (current-transcript-path root session-id))
  ([root crew session-id]
   (current-transcript-path root crew session-id)))

(defn index-path [root]
  (str (sessions-dir root) "/index.edn"))

(def ^:private reserved-session-names #{"turns" "index.edn" "recall"})

(defn- ->index-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- keywordize-index-row [row]
  (let [m (if (map? row) (keywordize-map row) {})]
    (cond-> m
      (and (contains? m :session-policy) (string? (:session-policy m)))
      (update :session-policy keyword)
      (and (contains? m :crew) (keyword? (:crew m)))
      (update :crew name))))

(defn read-index
  "sessions/index.edn as {id {:crew :session-policy :updated-at ...}}. Empty map when absent."
  [fs root]
  (let [path (index-path root)]
    (if-not (exists?* fs path)
      {}
      (let [raw (try (edn/read-string (or (slurp* fs path) "{}"))
                     (catch Exception _ nil))]
        (if (map? raw)
          (reduce-kv (fn [acc k v]
                       (assoc acc (->index-key k) (keywordize-index-row v)))
                     {}
                     raw)
          {})))))

(defn write-index!
  "Atomic rewrite of sessions/index.edn (temp + rename)."
  [fs root index]
  (atomic-spit! fs (index-path root) (write-edn index)))

(defn upsert-index-row!
  [fs root session-id row]
  (let [id  (str session-id)
        idx (read-index fs root)]
    (write-index! fs root (assoc idx id (merge (get idx id) row {:id id})))))

(defn- reserved-name? [name]
  (or (contains? reserved-session-names name)
      (str/starts-with? (str name) ".")
      (re-matches #".*\.(edn|ednl|jsonl|tmp)$" (str name))))

(defn- session-edn-at [fs path]
  (when (exists?* fs path)
    (try
      (let [raw (edn/read-string (or (slurp* fs path) ""))]
        (when (map? raw) (keywordize-index-row (keywordize-map raw))))
      (catch Exception _ nil))))

(defn scan-session-dirs
  "Walk sessions/<crew>/<sid>/session.edn (and leftover flat sessions/<sid>/session.edn).
   Returns {id {:crew :session-policy :updated-at :dir}}."
  [fs root]
  (let [dir (sessions-dir root)]
    (if-not (exists?* fs dir)
      {}
      (reduce
        (fn [acc name]
          (if (reserved-name? name)
            acc
            (let [nested-edn (str dir "/" name)
                  ;; name is either a crew dir or a leftover flat session id
                  kids       (or (children* fs nested-edn) [])]
              (if (exists?* fs (str nested-edn "/session.edn"))
                ;; leftover flat sessions/<sid>/session.edn
                (let [entry (session-edn-at fs (str nested-edn "/session.edn"))
                      id    (or (:id entry) name)
                      crew  (or (:crew entry) "main")]
                  (assoc acc id (merge {:crew crew :dir nested-edn :id id} entry)))
                ;; nested sessions/<crew>/<sid>/
                (reduce
                  (fn [acc2 sid]
                    (if (reserved-name? sid)
                      acc2
                      (let [sdir (str nested-edn "/" sid)
                            edn-path (str sdir "/session.edn")]
                        (if-let [entry (session-edn-at fs edn-path)]
                          (let [id   (or (:id entry) sid)
                                crew (or (:crew entry) name)]
                            (assoc acc2 id (merge {:crew crew :dir sdir :id id} entry)))
                          acc2))))
                  acc
                  kids)))))
        {}
        (or (children* fs dir) [])))))

(defn repair-index!
  "Merge a scan of session directories into the derived index and rewrite it."
  [fs root]
  (let [scanned (scan-session-dirs fs root)
        current (read-index fs root)
        merged  (merge-with (fn [idx-row scanned-row]
                              (merge idx-row
                                     (select-keys scanned-row [:crew :session-policy :updated-at :id])))
                            current
                            (reduce-kv (fn [m k v]
                                         (assoc m k (select-keys v [:crew :session-policy :updated-at :id])))
                                       {}
                                       scanned))]
    (write-index! fs root merged)
    merged))

(defn locate-session
  "Resolve a session id via the index, falling back to a directory scan that
   repairs the index. Returns {:crew :session-policy :dir :id ...} or nil."
  [root session-id fs]
  (let [id          (str session-id)
        indexed     (get (read-index fs root) id)
        indexed-loc (when indexed
                      (let [crew (or (:crew indexed) "main")]
                        (assoc indexed :id id :crew crew :dir (session-dir root crew id))))
        scanned     (get (scan-session-dirs fs root) id)
        found       (if (and indexed-loc
                             (exists?* fs (str (:dir indexed-loc) "/session.edn")))
                      indexed-loc
                      (or scanned indexed-loc))]
    (when found
      (when-not indexed
        (upsert-index-row! fs root id (select-keys found [:crew :session-policy :updated-at :id])))
      found)))

(defn assert-unique-session-id!
  "Throw when session-id is already indexed (or on disk) under a different crew."
  [root session-id crew fs]
  (when-let [found (locate-session root session-id fs)]
    (let [want (name (or crew "main"))
          have (name (or (:crew found) "main"))]
      (when (not= want have)
        (throw (ex-info (str "session " session-id " belongs to crew " have)
                        {:reason :crew-collision :id session-id
                         :crew have :wanted-crew want}))))))

(defn flat-jsonl-path [root session-id]
  (str (sessions-dir root) "/" session-id ".jsonl"))

(defn flat-sidecar-path [root session-id]
  (str (sessions-dir root) "/" session-id ".edn"))

(defn turns-dir [root]
  (str (sessions-dir root) "/turns"))

(defn turn-marker-path
  ([root session-id]
   (str (session-dir root session-id) "/turn.edn"))
  ([root crew session-id]
   (str (session-dir root crew session-id) "/turn.edn")))

(defn legacy-turn-marker-path [root session-id]
  (str (turns-dir root) "/" session-id ".edn"))

;; endregion ^^^^^ Paths ^^^^^

;; region ----- Turn markers -----

(defn- turn-marker-path-for [root session-id fs]
  (if-let [loc (and fs (locate-session root session-id fs))]
    (str (or (:dir loc) (session-dir root (:crew loc) session-id)) "/turn.edn")
    (turn-marker-path root session-id)))

(defn record-turn-marker!* [root session-id marker fs]
  (when-let [loc (and fs (locate-session root session-id fs))]
    (let [path (str (or (:dir loc) (session-dir root (:crew loc) session-id)) "/turn.edn")]
      (with-persist-lock session-id
        (fn []
          (atomic-spit! fs path (write-edn (assoc marker :session-id (str session-id)))))))))

(defn request-cancel!* [root session-id fs]
  (with-persist-lock session-id
    (fn []
      (let [path (turn-marker-path root session-id)]
        (when (exists?* fs path)
          (let [marker (edn/read-string (slurp* fs path))]
            (atomic-spit! fs path (write-edn (assoc marker :cancelled true
                                                    :session-id (str session-id))))
            true))))))

(defn clear-turn-marker!* [root session-id fs]
  (with-persist-lock session-id
    (fn []
      (doseq [path [(turn-marker-path-for root session-id fs)
                    (turn-marker-path root session-id)
                    (legacy-turn-marker-path root session-id)]]
        (delete*! fs path)))))

(defn get-turn-marker* [root session-id fs]
  (with-persist-lock session-id
    (fn []
      (let [path (turn-marker-path-for root session-id fs)]
        (when (exists?* fs path)
          (edn/read-string (slurp* fs path)))))))

(defn- read-marker [fs path session-id]
  (with-persist-lock session-id
    (fn []
      (when (exists?* fs path)
        (some-> (edn/read-string (slurp* fs path))
                (assoc :session-id (str session-id)))))))

(defn turn-markers* [root fs]
  (let [scanned      (scan-session-dirs fs root)
        nested       (->> scanned
                          (keep (fn [[id loc]]
                                  (let [dir (or (:dir loc) (session-dir root (:crew loc) id))]
                                    (read-marker fs (str dir "/turn.edn") id))))
                          vec)
        nested-ids   (set (map :session-id nested))
        dir          (sessions-dir root)
        leftover     (if-let [children (children* fs dir)]
                       (->> children
                            (remove reserved-name?)
                            (keep (fn [name]
                                    (when-not (contains? nested-ids name)
                                      (read-marker fs (turn-marker-path root name) name))))
                            vec)
                       [])
        product      (into nested leftover)
        product-ids  (set (map :session-id product))
        turns-dir    (turns-dir root)
        legacy       (if-let [children (children* fs turns-dir)]
                       (->> children
                            (keep (fn [name]
                                    (when (str/ends-with? name ".edn")
                                      (let [id (subs name 0 (- (count name) 4))]
                                        (when-not (contains? product-ids id)
                                          (read-marker fs (legacy-turn-marker-path root id) id))))))
                            vec)
                       [])]
    (into product legacy)))

;; endregion ^^^^^ Turn markers ^^^^^

;; region ----- Transcript -----

(defn- read-ednl-unlocked [fs path]
  (if-not (exists?* fs path)
    []
    (let [s (or (slurp* fs path) "")]
      (if (and fast-parser fast-read-next (seq s))
        (let [eof ::eof
              p   (fast-parser {:eof eof} s)]
          (into [] (take-while #(not= eof %) (repeatedly #(fast-read-next p)))))
        (->> (str/split-lines s)
             (remove str/blank?)
             (mapv read-edn-line))))))

(defn read-ednl [fs path]
  (if-let [k (session-key-from-path path)]
    (with-persist-lock k #(read-ednl-unlocked fs path))
    (read-ednl-unlocked fs path)))

(defn write-ednl! [fs path entries]
  (atomic-spit! fs path (apply str (map write-edn entries))))

(defn- nested-or-flat-current [root session-id fs]
  (if-let [loc (and fs (locate-session root session-id fs))]
    (str (or (:dir loc) (session-dir root (:crew loc) session-id)) "/current.ednl")
    (current-transcript-path root session-id)))

(defn read-transcript-raw
  ([root session-id fs]
   (read-ednl fs (nested-or-flat-current root session-id fs)))
  ([root crew session-id fs]
   (read-ednl fs (current-transcript-path root crew session-id))))

(defn write-transcript!
  ([root session-id entries fs]
   (write-ednl! fs (nested-or-flat-current root session-id fs) entries))
  ([root crew session-id entries fs]
   (write-ednl! fs (current-transcript-path root crew session-id) entries)))

(defn- parse-edn-line [s]
  (try
    (read-edn-line s)
    (catch Exception _ nil)))

(defn- last-transcript-entry-unlocked [fs path]
  (when (exists?* fs path)
    (->> (or (slurp* fs path) "")
         str/split-lines
         (remove str/blank?)
         reverse
         (some parse-edn-line))))

(defn last-transcript-entry
  "Last EDNL object, reading only a tail window."
  [fs path]
  (if-let [k (session-key-from-path path)]
    (with-persist-lock k #(last-transcript-entry-unlocked fs path))
    (last-transcript-entry-unlocked fs path)))

(defn append-entry! [root session-id entry fs]
  (let [path (nested-or-flat-current root session-id fs)
        line (write-edn entry)]
    (mkdirs*! fs (fs/parent path))
    (with-persist-lock session-id
      (fn []
        (spit*! fs path line :append true)))))

(defn- session-dir-for [root session-id fs]
  (if-let [loc (locate-session root session-id fs)]
    (or (:dir loc) (session-dir root (:crew loc) session-id))
    (session-dir root session-id)))

(defn frozen-segment-ns [root session-id fs]
  (->> (or (children* fs (session-dir-for root session-id fs)) [])
       (keep (fn [name]
               (when (re-matches #"\d+\.ednl" name)
                 (Long/parseLong (subs name 0 (- (count name) 5))))))
       sort
       vec))

(defn read-chronicle [root session-id fs]
  (let [dir     (session-dir-for root session-id fs)
        frozen  (mapcat #(read-ednl fs (str dir "/" % ".ednl"))
                        (frozen-segment-ns root session-id fs))
        current (read-transcript-raw root session-id fs)]
    (vec (concat frozen current))))

;; endregion ^^^^^ Transcript ^^^^^

;; region ----- Session defaults & store helpers -----

(defn with-session-defaults [now-fn normalize-ts-fn entry]
  (let [entry (session-schema/conform-read entry)
        id    (or (:id entry) (:key entry))]
    (conform-session-read
      (-> entry
          (assoc :id id :key (or (:key entry) id))
          (update :name #(or % id))
          (update :origin #(or % {:kind :cli}))
          (update :cwd #(or % (System/getProperty "user.dir")))
          (update :created-at #(some-> % normalize-ts-fn))
          (update :updated-at #(or (some-> % normalize-ts-fn) (now-fn)))
          (update :tags #(or % #{}))
          (update :compaction-count #(or % 0))
          (update :segment #(or % 0))
          (update :input-tokens #(or % 0))
          (update :turn-input-tokens #(or % 0))
          (update :last-input-tokens #(or % 0))
          (update :output-tokens #(or % 0))
          (update :total-tokens #(or % 0))))))

(defn unmigrated-error [id]
  (ex-info (str "session '" id "' is in the old jsonl layout; run `isaac sessions migrate"
                (when id (str " " id)) "`")
           {:reason :unmigrated :id id}))

(defn leftover-flat? [root session-id fs]
  (or (exists?* fs (flat-jsonl-path root session-id))
      (exists?* fs (flat-sidecar-path root session-id))))

(defn- crew-of
  ([entry] (crew-of entry nil))
  ([entry fallback]
   (name (or (:crew entry) fallback "main"))))

(defn session-edn-for
  "Prefer nested sessions/<crew>/<sid>/session.edn; fall back to leftover flat."
  [root session-id crew fs]
  (let [nested (when crew (session-edn-path root crew session-id))]
    (cond
      (and nested (exists?* fs nested)) nested
      (exists?* fs (session-edn-path root session-id)) (session-edn-path root session-id)
      :else (or nested (session-edn-path root session-id)))))

(defn resolve-session-loc
  "Locate a session by id (index then scan then leftover flat). Returns
   {:id :crew :dir :session-policy ...} or nil."
  [root session-id fs]
  (or (locate-session root session-id fs)
      (when (exists?* fs (session-edn-path root session-id))
        (when-let [entry (session-edn-at fs (session-edn-path root session-id))]
          (assoc entry :id session-id :dir (session-dir root session-id)
                 :crew (crew-of entry))))))

(defn assert-migrated! [root session-id fs]
  (when (and session-id
             (not (or (exists?* fs (session-edn-path root session-id))
                      (some? (resolve-session-loc root session-id fs))))
             (leftover-flat? root session-id fs))
    (throw (unmigrated-error session-id))))

(defn- read-session-entry-at [with-session-defaults-fn root session-id path fs]
  (with-persist-lock session-id
    (fn []
      (let [s (or (slurp* fs path) "")]
        (when (str/blank? s)
          (throw (unreadable-session session-id)))
        (let [raw (try
                    (edn/read-string s)
                    (catch Exception _
                      (throw (unreadable-session session-id))))]
          (when-not (map? raw)
            (throw (unreadable-session session-id)))
          [session-id (with-session-defaults-fn (assoc (keywordize-map raw) :id session-id))])))))

(defn read-session-entry
  ([with-session-defaults-fn root session-id fs]
   (read-session-entry with-session-defaults-fn root session-id nil fs))
  ([with-session-defaults-fn root session-id crew fs]
   (let [loc  (resolve-session-loc root session-id fs)
         crew (or crew (:crew loc))
         path (session-edn-for root session-id crew fs)]
     (read-session-entry-at with-session-defaults-fn root session-id path fs))))

(defn read-sidecar-store [with-session-defaults-fn root fs]
  (let [scanned (scan-session-dirs fs root)
        flat    (let [dir (sessions-dir root)]
                  (->> (or (children* fs dir) [])
                       (remove reserved-name?)
                       (filter #(exists?* fs (session-edn-path root %)))
                       (map (fn [id] [id {:id id :crew "main" :dir (session-dir root id)}]))))
        all     (merge (into {} (map (fn [[id loc]] [id loc]) flat)) scanned)]
    (->> all
         (keep (fn [[id loc]]
                 (let [path (str (:dir loc) "/session.edn")]
                   (when (exists?* fs path)
                     (read-session-entry-at with-session-defaults-fn root id path fs)))))
         (into {}))))

(defn normalize-index-store [with-session-defaults-fn raw]
  (if (map? raw)
    (reduce-kv (fn [store key-str entry]
                 (let [id         (if (keyword? key-str) (name key-str) (str key-str))
                       entry      (if (map? entry) (keywordize-map entry) {})
                       normalized (with-session-defaults-fn (assoc entry :id id))]
                   (assoc store id normalized)))
               {}
               raw)
    {}))

(defn resolve-entry-id [store identifier]
  (cond
    (nil? identifier) nil
    (contains? store identifier) identifier
    :else (let [id (session-id identifier)] (when (contains? store id) id))))

(defn rename-session!
  [read-session-fn commit-fn now-iso-fn in-flight-check root old-name new-name fs]
  (let [store  (read-session-fn root fs)
        old-id (resolve-entry-id store old-name)
        new-id (session-id new-name)
        entry  (when old-id (get store old-id))]
    (cond
      (nil? entry)
      (do (when old-name (assert-migrated! root (session-id old-name) fs))
          nil)

      (in-flight-check old-id)
      (throw (ex-info (str "cannot rename in-flight session '" old-name
                           "': a turn is in progress. Wait for it to finish or cancel it first.")
                      {:reason :in-flight :old-id old-id :new-id new-id}))

      (and (not= old-id new-id) (contains? store new-id))
      (throw (ex-info (str "cannot rename to '" new-name
                           "': a session with that key already exists.")
                      {:reason :collision :old-id old-id :new-id new-id}))

      (= old-id new-id)
      entry

      :else
      (let [crew    (crew-of entry)
            renamed (conform-session!
                      (-> entry
                          (assoc :id new-id
                                 :key new-id
                                 :name (or new-name new-id)
                                 :updated-at (now-iso-fn))
                          (dissoc :session-file :effective-history-offset)))]
        (move-tree! fs (session-dir root crew old-id) (session-dir root crew new-id))
        (let [idx (read-index fs root)]
          (write-index! fs root (-> idx
                                    (dissoc old-id)
                                    (assoc new-id {:crew           crew
                                                   :session-policy (or (:session-policy renamed) :chronicle)
                                                   :updated-at     (:updated-at renamed)
                                                   :id             new-id}))))
        (commit-fn store old-id renamed)
        renamed))))

(defn- policy-stamp [opts]
  (let [raw (or (:session-policy opts) (:session-store opts))]
    (cond
      (keyword? raw) raw
      (string? raw)  (keyword raw)
      :else          nil)))

(defn create-session! [read-session-fn write-fn now-iso-fn normalize-ts-fn root identifier opts fs]
  (let [explicit-crew (when-let [c (:crew opts)]
                        (let [s (str c)]
                          (when-not (clojure.string/blank? s) s)))
        opts     (entry-defaults opts)
        store    (read-session-fn root fs)
        name     (or identifier (naming/generate (session-store/ensure-naming-strategy! root fs)))
        id       (session-id name)
        existing (get store id)
        crew     (or (when existing (crew-of existing))
                     (crew-of opts (:crew opts)))]
    (assert-migrated! root id fs)
    (when explicit-crew
      (assert-unique-session-id! root id explicit-crew fs))
    (cond
      (and existing (not= name (:name existing)))
      (throw (ex-info (str "session already exists: " id)
                      {:name name :session-id id}))

      (and existing (let [loc (locate-session root id fs)
                          dir (or (:dir loc) (session-dir root (crew-of existing) id))]
                      (exists?* fs (str dir "/current.ednl"))))
      (do
        (log/info :session/opened :sessionId id)
        existing)

      existing
      ;; Sidecar without transcript: recreate (matches pre-b6w0 sidecar spec).
      (let [now           (or (normalize-ts-fn (:updated-at opts)) (now-iso-fn))
            transcript-id (new-id)
            policy        (or (:session-policy existing) (policy-stamp opts) :chronicle)
            header        {:type      "session"
                           :id        transcript-id
                           :timestamp now
                           :version   3
                           :cwd       (System/getProperty "user.dir")}
            entry         (assoc existing :sessionId transcript-id :updated-at now)]
        (mkdirs*! fs (session-dir root (crew-of existing) id))
        (write-transcript! root (crew-of existing) id [header] fs)
        (write-fn store id (conform-session! (dissoc entry :session-file :effective-history-offset)))
        (upsert-index-row! fs root id {:crew (crew-of existing) :session-policy policy :updated-at now})
        (log/info :session/created :sessionId id)
        entry)

      :else
      (let [now           (or (normalize-ts-fn (:updated-at opts)) (now-iso-fn))
            retention     (resolve-history-retention opts)
            transcript-id (new-id)
            policy        (or (policy-stamp opts) :chronicle)
            header        {:type      "session"
                           :id        transcript-id
                           :timestamp now
                           :version   3
                           :cwd       (System/getProperty "user.dir")}
            entry         (with-session-defaults now-iso-fn normalize-ts-fn
                            (cond-> {:id                id
                                     :key               id
                                     :name              name
                                     :nonce             (or (:nonce opts) (new-nonce))
                                     :sessionId         transcript-id
                                     :origin            (:origin opts)
                                     :history-retention retention
                                     :created-at        now
                                     :updated-at        now
                                     :cwd               (or (:cwd opts) (System/getProperty "user.dir"))
                                     :crew              crew
                                     :session-policy    policy
                                     :tags              (:tags opts)
                                     :channel           (:channel opts)
                                     :chat-type         (or (:chat-type opts) (:chatType opts))
                                     :compaction-count  0
                                     :segment           0
                                     :input-tokens      0
                                     :turn-input-tokens 0
                                     :last-input-tokens 0
                                     :output-tokens     0
                                     :total-tokens      0}))]
        (mkdirs*! fs (session-dir root crew id))
        (write-transcript! root crew id [header] fs)
        (write-fn store id (conform-session! (dissoc entry :session-file :effective-history-offset)))
        (upsert-index-row! fs root id {:crew crew :session-policy policy :updated-at now})
        (log/info :session/created :sessionId id)
        entry))))

;; endregion ^^^^^ Session defaults & store helpers ^^^^^

;; region ----- Toolcall helpers -----

(defn entry-toolcall-ids [entry]
  (let [message (get entry :message)
        content (:content message)]
    (cond
      (= "toolCall" (:type message))
      (keep :id [message])

      (sequential? content)
      (->> content
           (filter #(= "toolCall" (:type %)))
           (keep :id))

      :else
      nil)))

(defn- tool-result-call-id [entry]
  (when (= "toolResult" (get-in entry [:message :role]))
    (or (get-in entry [:message :toolCallId])
        (get-in entry [:message :id])
        (:id entry))))

(defn drop-orphan-toolresults
  [transcript]
  (let [tool-call-ids (->> transcript
                           (filter #(= "message" (:type %)))
                           (mapcat entry-toolcall-ids)
                           set)]
    (vec (remove (fn [entry]
                   (when-let [call-id (tool-result-call-id entry)]
                     (not (contains? tool-call-ids call-id))))
                 transcript))))

(defn drop-orphan-toolcalls [transcript]
  (let [tool-call-ids   (->> transcript
                             (filter #(= "message" (:type %)))
                             (mapcat entry-toolcall-ids)
                             set)
        tool-result-ids (->> transcript
                             (filter #(= "toolResult" (get-in % [:message :role])))
                             (keep #(or (get-in % [:message :toolCallId])
                                        (get-in % [:message :id])
                                        (:id %)))
                             set)
        orphans         (set/difference tool-call-ids tool-result-ids)]
    (if (empty? orphans)
      transcript
      (let [remove?     (fn [entry]
                          (and (= "message" (:type entry))
                               (seq (set/intersection orphans (set (entry-toolcall-ids entry))))))
            removed-ids (->> transcript (filter remove?) (map :id) set)
            kept        (vec (remove remove? transcript))
            remap       (loop [remaining transcript last-kept nil mapping {}]
                          (if (empty? remaining)
                            mapping
                            (let [e (first remaining)]
                              (if (contains? removed-ids (:id e))
                                (recur (rest remaining) last-kept (assoc mapping (:id e) last-kept))
                                (recur (rest remaining) (:id e) mapping)))))]
        (mapv (fn [entry]
                (if-let [new-parent (get remap (:parentId entry))]
                  (assoc entry :parentId new-parent)
                  entry))
              kept)))))

(defn last-entry-id [transcript]
  (:id (last transcript)))

;; endregion ^^^^^ Toolcall helpers ^^^^^

;; region ----- Shared public API -----

(defn list-sessions [read-store-fn root crew-id fs]
  (let [sessions (->> (vals (read-store-fn root fs))
                      (sort-by :id)
                      vec)]
    (if crew-id
      (->> sessions (filter #(= crew-id (:crew %))) vec)
      sessions)))

(defn most-recent-session [read-store-fn root crew-id fs]
  (->> (list-sessions read-store-fn root crew-id fs)
       (sort-by :updated-at)
       last))

(defn get-session [read-store-fn root identifier fs]
  (let [store (read-store-fn root fs)]
    (if-let [id (resolve-entry-id store identifier)]
      (get store id)
      (when identifier
        (assert-migrated! root (session-id identifier) fs)
        nil))))

(defn get-transcript [get-session-fn root identifier fs]
  (when-let [entry (get-session-fn root identifier fs)]
    (read-transcript-raw root (:id entry) fs)))

(defn active-transcript [get-session-fn root identifier fs]
  (get-transcript get-session-fn root identifier fs))

(defn chronicle-transcript [get-session-fn root identifier fs]
  (when-let [entry (get-session-fn root identifier fs)]
    (read-chronicle root (:id entry) fs)))

(defn truncate-after-compaction! [get-session-fn root identifier fs]
  (let [entry      (get-session-fn root identifier fs)
        transcript (read-transcript-raw root (:id entry) fs)
        compaction (->> transcript (filter #(= "compaction" (:type %))) last)]
    (when compaction
      (let [first-kept-id  (:firstKeptEntryId compaction)
            compaction-id  (:id compaction)
            removed-ids    (loop [remaining transcript ids #{}]
                             (if (empty? remaining)
                               ids
                               (let [e (first remaining)]
                                 (cond
                                   (= (:id e) compaction-id) ids
                                   (and first-kept-id (= (:id e) first-kept-id)) ids
                                   (= "message" (:type e)) (recur (rest remaining) (conj ids (:id e)))
                                   :else (recur (rest remaining) ids)))))
            remap          (loop [remaining transcript last-kept nil mapping {}]
                             (if (empty? remaining)
                               mapping
                               (let [e (first remaining)]
                                 (if (contains? removed-ids (:id e))
                                   (recur (rest remaining) last-kept (assoc mapping (:id e) last-kept))
                                   (recur (rest remaining) (:id e) mapping)))))
            new-transcript (into []
                                 (keep (fn [e]
                                         (when-not (contains? removed-ids (:id e))
                                           (if-let [new-parent (get remap (:parentId e))]
                                             (assoc e :parentId new-parent)
                                             e))))
                                 transcript)]
        (when (pos? (count removed-ids))
          (write-transcript! root (:id entry) new-transcript fs)
          (count removed-ids))))))

(defn update-session! [update-entry-fn normalize-ts-fn root identifier updates fs]
  (update-entry-fn root identifier
                   (fn [entry]
                     (let [updates (if-let [compaction (:compaction updates)]
                                     (assoc updates :compaction (merge (or (:compaction entry) {}) compaction))
                                     updates)]
                       (-> (merge entry updates)
                           (assoc :key (:id entry))
                           (dissoc :session-file :effective-history-offset)
                           (update :updated-at normalize-ts-fn))))
                   fs))

(defn append-message! [get-session-fn update-entry-fn now-fn root identifier message fs]
  (let [entry            (get-session-fn root identifier fs)
        id               (:id entry)
        parent-id        (:id (last-transcript-entry fs (nested-or-flat-current root id fs)))
        msg-id           (new-id)
        now              (now-fn)
        resolved-agent   (or (:crew message)
                             (when (#{"assistant" "error" "toolResult"} (:role message)) (:crew entry))
                             (when (= "assistant" (:role message)) "main"))
        normalized-msg   (stamp-message-tokens
                           (normalize-message (cond-> message
                                                resolved-agent (assoc :crew resolved-agent))))
        transcript-entry {:type      "message"
                          :id        msg-id
                          :parentId  parent-id
                          :timestamp now
                          :message   normalized-msg
                          :tokens    (:tokens normalized-msg)}]
    (append-entry! root id transcript-entry fs)
    (update-entry-fn root identifier
                     (fn [e]
                       (cond-> (assoc e :updated-at now)
                         (:channel message) (assoc :last-channel (:channel message))
                         (:to message)      (assoc :last-to (:to message))
                         resolved-agent     (assoc :crew resolved-agent)))
                     fs)
    transcript-entry))

(defn append-error! [get-session-fn update-entry-fn now-fn root identifier error-entry fs]
  (let [entry            (get-session-fn root identifier fs)
        id               (:id entry)
        parent-id        (:id (last-transcript-entry fs (nested-or-flat-current root id fs)))
        error-id         (new-id)
        now              (now-fn)
        transcript-entry (cond-> {:type      "error"
                                  :id        error-id
                                  :parentId  parent-id
                                  :timestamp now
                                  :content   (:content error-entry)
                                  :error     (:error error-entry)
                                  :model     (:model error-entry)
                                  :provider  (:provider error-entry)}
                           (:ex-class error-entry) (assoc :ex-class (:ex-class error-entry)))]
    (append-entry! root id transcript-entry fs)
    (update-entry-fn root identifier #(assoc % :updated-at now) fs)
    transcript-entry))

(defn append-reckoning! [get-session-fn update-entry-fn now-fn root identifier {:keys [text]} fs]
  (let [entry            (get-session-fn root identifier fs)
        id               (:id entry)
        parent-id        (:id (last-transcript-entry fs (nested-or-flat-current root id fs)))
        rec-id           (new-id)
        now              (now-fn)
        transcript-entry {:type      "reckoning"
                          :id        rec-id
                          :parentId  parent-id
                          :timestamp now
                          :text      text}]
    (append-entry! root id transcript-entry fs)
    (update-entry-fn root identifier #(assoc % :updated-at now) fs)
    transcript-entry))

(defn append-checkpoint! [get-session-fn update-entry-fn now-fn root identifier {:keys [cycle]} fs]
  (let [entry            (get-session-fn root identifier fs)
        id               (:id entry)
        parent-id        (:id (last-transcript-entry fs (nested-or-flat-current root id fs)))
        ckpt-id          (new-id)
        now              (now-fn)
        transcript-entry {:type      "checkpoint"
                          :id        ckpt-id
                          :parentId  parent-id
                          :timestamp now
                          :cycle     cycle}]
    (append-entry! root id transcript-entry fs)
    (update-entry-fn root identifier #(assoc % :updated-at now) fs)
    transcript-entry))

(defn append-compaction! [get-session-fn update-entry-fn now-fn root identifier {:keys [summary firstKeptEntryId tokensBefore turnRequest]} fs]
  (let [entry         (get-session-fn root identifier fs)
        id            (:id entry)
        parent-id     (:id (last-transcript-entry fs (nested-or-flat-current root id fs)))
        compaction-id (new-id)
        now           (now-fn)
        compaction    (cond-> {:type             "compaction"
                               :id               compaction-id
                               :parentId         parent-id
                               :timestamp        now
                               :summary          summary
                               :firstKeptEntryId firstKeptEntryId
                               :tokensBefore     tokensBefore}
                        turnRequest (assoc :turnRequest turnRequest)
                        true        (assoc :tokens (or (compaction-tokens {:summary summary :turnRequest turnRequest}) 0)))]
    (append-entry! root id compaction fs)
    (update-entry-fn root identifier
                     (fn [e]
                       (-> e
                           (assoc :updated-at now)
                           (update :compaction-count inc)))
                     fs)
    compaction))

(defn compacted-current
  "New current-view transcript: compaction entry + kept tail."
  ([transcript compactedEntryIds firstKeptEntryId summary tokensBefore now]
   (compacted-current transcript compactedEntryIds firstKeptEntryId summary tokensBefore now nil))
  ([transcript compactedEntryIds firstKeptEntryId summary tokensBefore now turnRequest]
  (let [compacted-ids    (set compactedEntryIds)
        removable-ids    (->> transcript
                              (filter #(and (= "message" (:type %))
                                            (contains? compacted-ids (:id %))))
                              (map :id)
                              set)
        first-kept-index (when firstKeptEntryId
                           (some (fn [[idx e]]
                                   (when (= firstKeptEntryId (:id e)) idx))
                                 (map-indexed vector transcript)))
        insert-at        (or (some (fn [[idx e]]
                                     (when (contains? removable-ids (:id e)) idx))
                                   (map-indexed vector transcript))
                             (or first-kept-index (count transcript)))
        before           (subvec transcript 0 insert-at)
        compaction-id    (new-id)
        compaction-entry (cond-> {:type             "compaction"
                                  :id               compaction-id
                                  :parentId         (:id (last before))
                                  :timestamp        now
                                  :summary          summary
                                  :firstKeptEntryId firstKeptEntryId
                                  :tokensBefore     tokensBefore
                                  :tokens           (or (compaction-tokens {:summary summary :turnRequest turnRequest}) 0)}
                           turnRequest (assoc :turnRequest turnRequest))
        after            (->> (subvec transcript (or first-kept-index (count transcript)))
                              (remove #(contains? removable-ids (:id %)))
                              (mapv (fn [e]
                                      (if (contains? removable-ids (:parentId e))
                                        (assoc e :parentId compaction-id)
                                        e))))]
    [compaction-entry (drop-orphan-toolcalls (into [compaction-entry] after))])))

(defn frozen-segment
  "Pre-splice entries that do not appear in the compacted current.
   Under :retain this is the unique compacted prefix (header + discarded
   messages). The kept tail lives only in the new current."
  [transcript new-current]
  (let [kept-ids (set (map :id new-current))]
    (vec (remove #(contains? kept-ids (:id %)) transcript))))

(defn splice-compaction! [get-session-fn update-entry-fn now-fn root identifier {:keys [compactedEntryIds firstKeptEntryId summary tokensBefore turnRequest]} fs]
  (let [entry      (get-session-fn root identifier fs)
        id         (:id entry)
        transcript (read-transcript-raw root id fs)
        retention  (or (:history-retention entry) resolve/default-history-retention)
        now        (now-fn)
        [compaction-entry new-current] (compacted-current transcript compactedEntryIds firstKeptEntryId summary tokensBefore now turnRequest)
        prefix     (frozen-segment transcript new-current)
        n          (or (:segment entry) 0)
        dir          (session-dir-for root id fs)
        current-path (str dir "/current.ednl")]
    (when (= :retain retention)
      (write-ednl! fs (str dir "/" n ".ednl") prefix))
    (write-ednl! fs current-path new-current)
    (update-entry-fn root identifier
                     (fn [e]
                       (-> e
                           (assoc :updated-at now)
                           (cond-> (= :retain retention) (assoc :segment (inc n)))
                           (dissoc :effective-history-offset :session-file)
                           (update :compaction-count inc)))
                     fs)
    compaction-entry))

;; endregion ^^^^^ Shared public API ^^^^^

(defn repair-torn-transcript!*
  "Truncate a torn trailing EDNL line (a crash mid-append) to the last complete
   line and rewrite the file. Returns the surviving entries when something was
   dropped, nil when the transcript was already whole or absent."
  [root session-id fs]
  (let [path (nested-or-flat-current root session-id fs)]
    (when (exists?* fs path)
      (let [lines (str/split-lines (fs/slurp fs path))
            valid (loop [n (count lines)]
                    (if (zero? n)
                      []
                      (let [candidate (take n lines)]
                        (if (every? #(try (read-edn-line %) (catch Exception _ false))
                                    candidate)
                          candidate
                          (recur (dec n))))))]
        (when (< (count valid) (count lines))
          (log/warn :resume/transcript-repair
                    :session session-id
                    :repair :torn-line
                    :dropped-lines (- (count lines) (count valid)))
          (let [entries (mapv read-edn-line valid)]
            (write-ednl! fs path entries)
            entries))))))
