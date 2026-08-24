(ns isaac.session.store.impl-common
  (:require
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.naming :as naming]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as session-store])
  (:import
    (java.nio.charset StandardCharsets)
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

(defn session-dir [root session-id]
  (str (sessions-dir root) "/" session-id))

(defn session-edn-path [root session-id]
  (str (session-dir root session-id) "/session.edn"))

(defn sidecar-path [root session-id]
  (session-edn-path root session-id))

(defn current-transcript-path [root session-id]
  (str (session-dir root session-id) "/current.ednl"))

(defn frozen-transcript-path [root session-id n]
  (str (session-dir root session-id) "/" n ".ednl"))

(defn transcript-path [root session-id]
  (current-transcript-path root session-id))

(defn index-path [root]
  (str (sessions-dir root) "/index.edn"))

(defn flat-jsonl-path [root session-id]
  (str (sessions-dir root) "/" session-id ".jsonl"))

(defn flat-sidecar-path [root session-id]
  (str (sessions-dir root) "/" session-id ".edn"))

(defn turns-dir [root]
  (str (sessions-dir root) "/turns"))

(defn turn-marker-path [root session-id]
  (str (session-dir root session-id) "/turn.edn"))

(defn legacy-turn-marker-path [root session-id]
  (str (turns-dir root) "/" session-id ".edn"))

;; endregion ^^^^^ Paths ^^^^^

;; region ----- Turn markers -----

(defn record-turn-marker!* [root session-id marker fs]
  (let [path (turn-marker-path root session-id)]
    (mkdirs*! fs (fs/parent path))
    (spit*! fs path (write-edn (assoc marker :session-id (str session-id))))))

(defn clear-turn-marker!* [root session-id fs]
  (delete*! fs (turn-marker-path root session-id)))

(defn get-turn-marker* [root session-id fs]
  (let [path (turn-marker-path root session-id)]
    (when (exists?* fs path)
      (edn/read-string (slurp* fs path)))))

(defn turn-markers* [root fs]
  (let [dir (sessions-dir root)]
    (if-let [children (children* fs dir)]
      (->> children
           (keep (fn [name]
                   (let [path (turn-marker-path root name)]
                     (when (exists?* fs path)
                       (some-> (edn/read-string (slurp* fs path))
                               (assoc :session-id name))))))
           vec)
      [])))

;; endregion ^^^^^ Turn markers ^^^^^

;; region ----- Transcript -----

(defn read-ednl [fs path]
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

(defn write-ednl! [fs path entries]
  (mkdirs*! fs (fs/parent path))
  (spit*! fs path (apply str (map write-edn entries))))

(defn read-transcript-raw [root session-id fs]
  (read-ednl fs (current-transcript-path root session-id)))

(defn write-transcript! [root session-id entries fs]
  (write-ednl! fs (current-transcript-path root session-id) entries))

(defn last-transcript-entry
  "Last EDNL object, reading only a tail window."
  [fs path]
  (let [size (or (fs/size fs path) 0)]
    (when (pos? size)
      (loop [window 4096]
        (let [start    (max 0 (- size window))
              bytes    (or (fs/read-bytes fs path start (- size start)) (byte-array 0))
              text     (String. ^bytes bytes StandardCharsets/UTF_8)
              lines    (str/split-lines text)
              complete (if (pos? start) (rest lines) lines)
              kept     (vec (remove str/blank? complete))]
          (cond
            (seq kept)      (read-edn-line (peek kept))
            (zero? start)   nil
            :else           (recur (* 2 window))))))))

(defn append-entry! [root session-id entry fs]
  (let [path (current-transcript-path root session-id)]
    (mkdirs*! fs (fs/parent path))
    (spit*! fs path (write-edn entry) :append true)))

(defn frozen-segment-ns [root session-id fs]
  (->> (or (children* fs (session-dir root session-id)) [])
       (keep (fn [name]
               (when (re-matches #"\d+\.ednl" name)
                 (Long/parseLong (subs name 0 (- (count name) 5))))))
       sort
       vec))

(defn read-chronicle [root session-id fs]
  (let [frozen  (mapcat #(read-ednl fs (frozen-transcript-path root session-id %))
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
          (update :compaction-disabled #(if (nil? %) false %))
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

(defn assert-migrated! [root session-id fs]
  (when (and session-id
             (not (exists?* fs (session-edn-path root session-id)))
             (leftover-flat? root session-id fs))
    (throw (unmigrated-error session-id))))

(defn read-session-entry [with-session-defaults-fn root session-id fs]
  (let [path  (session-edn-path root session-id)
        raw   (edn/read-string (slurp* fs path))
        entry (if (map? raw) (keywordize-map raw) {})]
    [session-id (with-session-defaults-fn (assoc entry :id session-id))]))

(defn read-sidecar-store [with-session-defaults-fn root fs]
  (let [dir (sessions-dir root)]
    (->> (or (children* fs dir) [])
         (filter #(exists?* fs (session-edn-path root %)))
         (map #(read-session-entry with-session-defaults-fn root % fs))
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
      (let [renamed (conform-session!
                      (-> entry
                          (assoc :id new-id
                                 :key new-id
                                 :name (or new-name new-id)
                                 :updated-at (now-iso-fn))
                          (dissoc :session-file :effective-history-offset)))]
        (move-tree! fs (session-dir root old-id) (session-dir root new-id))
        (commit-fn store old-id renamed)
        renamed))))

(defn create-session! [read-session-fn write-fn now-iso-fn normalize-ts-fn root identifier opts fs]
  (let [opts     (entry-defaults opts)
        store    (read-session-fn root fs)
        name     (or identifier (naming/generate (session-store/ensure-naming-strategy! root fs)))
        id       (session-id name)
        existing (get store id)]
    (assert-migrated! root id fs)
    (cond
      (and existing (exists?* fs (current-transcript-path root id)) (not= name (:name existing)))
      (throw (ex-info (str "session already exists: " id)
                      {:name name :session-id id}))

      (and existing (exists?* fs (current-transcript-path root id)))
      (do
        (log/info :session/opened :sessionId id)
        existing)

      :else
      (let [now           (or (normalize-ts-fn (:updated-at opts)) (now-iso-fn))
            retention     (resolve-history-retention opts)
            transcript-id (new-id)
            header        {:type      "session"
                           :id        transcript-id
                           :timestamp now
                           :version   3
                           :cwd       (System/getProperty "user.dir")}
            entry         (with-session-defaults now-iso-fn normalize-ts-fn
                            {:id                id
                             :key               id
                             :name              name
                             :nonce             (or (:nonce opts) (new-nonce))
                             :sessionId         transcript-id
                             :origin            (:origin opts)
                             :history-retention retention
                             :created-at        now
                             :updated-at        now
                             :cwd               (or (:cwd opts) (System/getProperty "user.dir"))
                             :crew              (:crew opts)
                             :tags              (:tags opts)
                             :channel           (:channel opts)
                             :chat-type         (or (:chat-type opts) (:chatType opts))
                             :compaction-count  0
                             :segment           0
                             :input-tokens      0
                             :turn-input-tokens 0
                             :last-input-tokens 0
                             :output-tokens     0
                             :total-tokens      0})]
        (mkdirs*! fs (session-dir root id))
        (write-transcript! root id [header] fs)
        (write-fn store id (conform-session! (dissoc entry :session-file :effective-history-offset)))
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
        parent-id        (:id (last-transcript-entry fs (current-transcript-path root id)))
        msg-id           (new-id)
        now              (now-fn)
        resolved-agent   (or (:crew message)
                             (when (#{"assistant" "error" "toolResult"} (:role message)) (:crew entry))
                             (when (= "assistant" (:role message)) "main"))
        normalized-msg   (normalize-message (cond-> message
                                              resolved-agent (assoc :crew resolved-agent)))
        transcript-entry (cond-> {:type      "message"
                                  :id        msg-id
                                  :parentId  parent-id
                                  :timestamp now
                                  :message   normalized-msg}
                           (:tokens message) (assoc :tokens (:tokens message)))]
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
        parent-id        (:id (last-transcript-entry fs (current-transcript-path root id)))
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

(defn append-compaction! [get-session-fn update-entry-fn now-fn root identifier {:keys [summary firstKeptEntryId tokensBefore]} fs]
  (let [entry         (get-session-fn root identifier fs)
        id            (:id entry)
        parent-id     (:id (last-transcript-entry fs (current-transcript-path root id)))
        compaction-id (new-id)
        now           (now-fn)
        compaction    {:type             "compaction"
                       :id               compaction-id
                       :parentId         parent-id
                       :timestamp        now
                       :summary          summary
                       :firstKeptEntryId firstKeptEntryId
                       :tokensBefore     tokensBefore}]
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
                                  :tokensBefore     tokensBefore}
                           turnRequest (assoc :turnRequest turnRequest))
        after            (->> (subvec transcript (or first-kept-index (count transcript)))
                              (remove #(contains? removable-ids (:id %)))
                              (mapv (fn [e]
                                      (if (contains? removable-ids (:parentId e))
                                        (assoc e :parentId compaction-id)
                                        e))))]
    [compaction-entry (drop-orphan-toolcalls (into [compaction-entry] after))])))

(defn splice-compaction! [get-session-fn update-entry-fn now-fn root identifier {:keys [compactedEntryIds firstKeptEntryId summary tokensBefore turnRequest]} fs]
  (let [entry      (get-session-fn root identifier fs)
        id         (:id entry)
        transcript (read-transcript-raw root id fs)
        retention  (or (:history-retention entry) resolve/default-history-retention)
        now        (now-fn)
        [compaction-entry new-current] (compacted-current transcript compactedEntryIds firstKeptEntryId summary tokensBefore now turnRequest)
        n          (or (:segment entry) 0)
        current-path (current-transcript-path root id)]
    (when (= :retain retention)
      (fs/copy fs current-path (frozen-transcript-path root id n)))
    (let [tmp (str current-path ".tmp")]
      (write-ednl! fs tmp new-current)
      (fs/move fs tmp current-path))
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
