(ns isaac.session.store.index-impl-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.impl-common :as c]
    [isaac.session.store.index :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def test-dir "/test/index-storage")
(def test-key "user1")

(defn- s [] (sut/create-store test-dir))

(defn- index-path []
  (str test-dir "/sessions/index.edn"))

(defn- read-index []
  (edn/read-string (fs/slurp (nexus/get :fs) (index-path))))

(defn- seed-transcript! [opts messages]
  (let [session      (sut/create-session! test-dir test-key opts)
        session-id   (:sessionId session)
        session-file (:session-file session)
        fs*          (nexus/get :fs)
        header       {:type      "session"
                      :id        session-id
                      :timestamp "2026-05-20T10:00:00"
                      :version   3
                      :cwd       test-dir}
        entries      (loop [remaining messages parent-id session-id out []]
                       (if-let [message (first remaining)]
                         (let [entry {:type      "message"
                                      :id        (c/new-id)
                                      :parentId  parent-id
                                      :timestamp "2026-05-20T10:00:00"
                                      :message   (c/normalize-message message)}]
                           (recur (next remaining) (:id entry) (conj out entry)))
                         out))]
    (c/write-transcript! test-dir session-file (into [header] entries) fs*)
    {:session session :entries entries}))

(defn- seeded-entry [parent-id spec]
  (let [base {:id        (c/new-id)
              :parentId  parent-id
              :timestamp "2026-05-20T10:00:00"}]
    (if (= "compaction" (:type spec))
      (merge base spec)
      (assoc base
             :type "message"
             :message (c/normalize-message spec)))))

(defn- append-seeded-entries! [session specs]
  (let [fs*          (nexus/get :fs)
        session-file (:session-file session)
        transcript   (c/read-transcript-raw test-dir session-file fs*)
        additions    (loop [remaining specs parent-id (:id (last transcript)) out []]
                       (if-let [spec (first remaining)]
                         (let [entry (seeded-entry parent-id spec)]
                           (recur (next remaining) (:id entry) (conj out entry)))
                         out))]
    (c/write-transcript! test-dir session-file (into transcript additions) fs*)
    additions))

(describe "Index Session Storage"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  ;; region ----- create-session! -----

  (describe "create-session!"

    (it "creates a new session and writes to the combined index"
      (let [entry (sut/create-session! test-dir test-key)]
        (should= "user1" (:key entry))
        (should (string? (:sessionId entry)))
        (should (string? (:session-file entry)))
        (should= :retain (:history-retention entry))
        (should= 0 (:compaction-count entry))
        (should= 0 (:input-tokens entry))
        (should= 0 (:output-tokens entry))
        (should= 0 (:total-tokens entry))
        (should (fs/exists? (nexus/get :fs) (index-path)))))

    (it "index contains the session entry"
      (sut/create-session! test-dir test-key)
      (let [index (read-index)]
        (should (contains? index "user1"))
        (should= "user1" (get-in index ["user1" :id]))))

    (it "stores multiple sessions in a single index file"
      (sut/create-session! test-dir "chat-1")
      (sut/create-session! test-dir "chat-2")
      (let [index (read-index)]
        (should= 2 (count index))
        (should (contains? index "chat-1"))
        (should (contains? index "chat-2"))))

    (it "does not create per-session sidecar .edn files"
      (sut/create-session! test-dir test-key)
      (let [dir      (str test-dir "/sessions")
            edn-files (->> (or (fs/children (nexus/get :fs) dir) [])
                            (filter #(str/ends-with? % ".edn")))]
        (should= ["index.edn"] edn-files)))

    (it "writes session metadata with kebab-case schema keys"
      (sut/create-session! test-dir test-key {:chatType "direct"})
      (let [entry (get (read-index) "user1")]
        (should= "direct" (:chat-type entry))
        (should-not (contains? entry :chatType))))

    (it "stores an explicit history-retention override"
      (let [entry (sut/create-session! test-dir test-key {:history-retention :prune})]
        (should= :prune (:history-retention entry))))

    (it "resumes an existing session"
      (let [first  (sut/create-session! test-dir test-key)
            second (sut/create-session! test-dir test-key)]
        (should= (:sessionId first) (:sessionId second))
        (should= 1 (count (store/list-sessions-by-agent (s) "main")))))

    (it "rejects a different name that slug-collides with an existing session"
      (sut/create-session! test-dir "friday-debug")
      (let [error (try
                    (sut/create-session! test-dir "Friday Debug")
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-not-be-nil error)
        (should= "session already exists: friday-debug" (ex-message error))))

    (it "creates a fresh session when its transcript is missing"
      (let [first  (sut/create-session! test-dir test-key)
            _      (fs/delete (nexus/get :fs) (str test-dir "/sessions/" (:session-file first)))
            second (sut/create-session! test-dir test-key)]
        (should-not= (:sessionId first) (:sessionId second))
        (should= 1 (count (store/list-sessions-by-agent (s) "main")))))

    (it "uses sequential names for unnamed sessions when configured"
      (do (config/dangerously-install-config! {:sessions {:naming-strategy :sequential}} "spec")
        (let [first  (sut/create-session! test-dir nil)
              second (sut/create-session! test-dir nil)]
          (should= "session-1" (:name first))
          (should= "session-2" (:name second))))))

  ;; endregion ^^^^^ create-session! ^^^^^

  ;; region ----- list-sessions -----

  (describe "list-sessions"

    (it "returns empty when no sessions"
      (should= [] (store/list-sessions-by-agent (s) "main")))

    (it "lists all created sessions"
      (sut/create-session! test-dir test-key)
      (sut/create-session! test-dir "user2")
      (should= 2 (count (store/list-sessions-by-agent (s) "main"))))

    (it "lists sessions without migrating transcripts"
      (sut/create-session! test-dir test-key)
      (let [migrations (atom 0)]
        (with-redefs [c/migrate-transcript! (fn [& _]
                                              (swap! migrations inc)
                                              [])]
          (should= 1 (count (store/list-sessions-by-agent (s) "main"))))
        (should= 0 @migrations)))

    (it "filters sessions by crew"
      (sut/create-session! test-dir "chat-a" {:crew "alpha"})
      (sut/create-session! test-dir "chat-b" {:crew "beta"})
      (should= 1 (count (store/list-sessions-by-agent (s) "alpha")))
      (should= 1 (count (store/list-sessions-by-agent (s) "beta")))))

  ;; endregion ^^^^^ list-sessions ^^^^^

  ;; region ----- migrate from sidecars -----

  (describe "migration from sidecar format"

    (it "imports existing sidecar entries into the index on first read"
      (let [sidecar-content {:id "chat-1" :name "Chat 1" :session-file "chat-1.jsonl"
                              :crew "main" :updated-at "2026-05-10T10:00:00"}
            sessions-dir    (str test-dir "/sessions")]
        (fs/mkdirs (nexus/get :fs) sessions-dir)
        (fs/spit   (nexus/get :fs) (str sessions-dir "/chat-1.edn")
                   (binding [*print-namespace-maps* false]
                     (str (pr-str sidecar-content) "\n")))
        (fs/spit (nexus/get :fs) (str sessions-dir "/chat-1.jsonl")
                 (str (json/generate-string {:type "session" :id "abc12345"
                                              :timestamp "2026-05-10T10:00:00"
                                              :version 3 :cwd test-dir}) "\n"))
        (let [sessions (store/list-sessions-by-agent (s) "main")]
          (should= 1 (count sessions))
          (should= "chat-1" (:id (first sessions)))
          (should (fs/exists? (nexus/get :fs) (index-path)))))))

  ;; endregion ^^^^^ migrate from sidecars ^^^^^

  ;; region ----- append-message! -----

  (describe "append-message!"

    (it "appends a message to the transcript"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "Hello"})
      (let [transcript (store/get-transcript (s) test-key)]
        (should= 2 (count transcript))
        (should= "message" (:type (second transcript)))))

    (it "updates updated-at in the index"
      (let [counter (atom 0)]
        (with-redefs [sut/now-iso (fn [] (format "2026-01-01T00:00:00.%03d" (swap! counter inc)))]
          (sut/create-session! test-dir test-key)
          (let [before (:updated-at (store/get-session (s) test-key))]
            (store/append-message! (s) test-key {:role "user" :content "Hello"})
            (let [after (:updated-at (store/get-session (s) test-key))]
              (should-not= before after)))))))

  ;; endregion ^^^^^ append-message! ^^^^^

  ;; region ----- update-session! -----

  (describe "update-session!"

    (it "updates the index entry"
      (sut/create-session! test-dir test-key)
      (store/update-session! (s) test-key {:input-tokens 100 :output-tokens 50})
      (let [entry (store/get-session (s) test-key)]
        (should= 100 (:input-tokens entry))
        (should= 50 (:output-tokens entry)))))

  ;; endregion ^^^^^ update-session! ^^^^^

  ;; region ----- splice-compaction! -----

  (describe "splice-compaction!"

    (it "retains compacted entries on disk and exposes an active transcript view"
      (let [{:keys [entries]} (seed-transcript! {:history-retention :retain}
                                                [{:role "user" :content "First"}
                                                 {:role "assistant" :content "Second"}
                                                 {:role "user" :content "Third"}])
            [first-msg second-msg third-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Summary"
                                   :firstKeptEntryId  (:id third-msg)
                                   :tokensBefore      20
                                   :compactedEntryIds [(:id first-msg) (:id second-msg)]})
        (let [transcript (store/get-transcript (s) test-key)
              active     (store/active-transcript (s) test-key)
              session    (store/get-session (s) test-key)]
          (should= ["session" "message" "message" "compaction" "message"] (mapv :type transcript))
          (should= ["compaction" "message"] (mapv :type active))
          (should (integer? (:effective-history-offset session)))))

    (it "stores an effective-history-offset that lands exactly on the compaction entry boundary (isaac-63f3)"
      ;; The offset is measured over what actually lands on disk (the written
      ;; prefix up to the compaction entry), so it sits on a real line boundary
      ;; and a read from it never hits a partial line.
      (let [{:keys [session entries]} (seed-transcript! {:history-retention :retain}
                                                        [{:role "user" :content "First"}
                                                         {:role "assistant" :content "Second"}
                                                         {:role "user" :content "Third"}])
            [first-msg second-msg third-msg] entries
            session-file (:session-file session)
            fs*          (nexus/get :fs)]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Summary"
                                   :firstKeptEntryId  (:id third-msg)
                                   :tokensBefore      20
                                   :compactedEntryIds [(:id first-msg) (:id second-msg)]})
        (let [offset (:effective-history-offset (store/get-session (s) test-key))
              bytes  (.getBytes ^String (fs/slurp fs* (c/transcript-path test-dir session-file)) "UTF-8")]
          ;; offset sits right after a newline — a real entry boundary — ...
          (should (or (zero? offset) (= \newline (char (aget bytes (dec offset))))))
          ;; ... and the round-trip read parses cleanly, compaction entry first.
          (should= "compaction"
                   (:type (first (c/read-transcript-from-offset test-dir session-file offset fs*)))))))))

  ;; endregion ^^^^^ splice-compaction! ^^^^^

  ;; region ----- drop-orphan-toolcalls -----

  (describe "drop-orphan-toolcalls"

    (it "returns the original transcript when every tool call has a result"
      (let [transcript [{:type "session" :id "session"}
                        {:type "message"
                         :id "tool-call"
                         :parentId "session"
                         :message {:role "assistant"
                                   :content [{:type "toolCall" :id "tc-1" :name "search" :arguments {}}]}}
                        {:type "message"
                         :id "tool-result"
                         :parentId "tool-call"
                         :message {:role "toolResult" :toolCallId "tc-1" :content "ok"}}]]
        (should= transcript (c/drop-orphan-toolcalls transcript))))

    (it "removes orphan tool call messages and reparents their children"
      (let [transcript [{:type "session" :id "session"}
                        {:type "message"
                         :id "orphan-call"
                         :parentId "session"
                         :message {:role "assistant"
                                   :content [{:type "toolCall" :id "tc-orphan" :name "search" :arguments {}}]}}
                        {:type "message"
                         :id "followup"
                         :parentId "orphan-call"
                         :message {:role "assistant" :content "continuing"}}
                        {:type "message"
                         :id "kept-call"
                         :parentId "followup"
                         :message {:role "assistant"
                                   :content [{:type "toolCall" :id "tc-kept" :name "fetch" :arguments {}}]}}
                        {:type "message"
                         :id "kept-result"
                         :parentId "kept-call"
                         :message {:role "toolResult" :toolCallId "tc-kept" :content "ok"}}]
            result     (c/drop-orphan-toolcalls transcript)]
        (should= ["session" "followup" "kept-call" "kept-result"] (mapv :id result))
        (should= "session" (:parentId (nth result 1)))
        (should= "followup" (:parentId (nth result 2))))))

  ;; endregion ^^^^^ drop-orphan-toolcalls ^^^^^

  ;; region ----- truncate-after-compaction! -----

  (describe "truncate-after-compaction!"

    (it "returns nil when no compaction entry exists"
      (seed-transcript! {} [{:role "user" :content "Hello"}])
      (should-be-nil (store/truncate-after-compaction! (s) test-key)))

    (it "removes all message entries before compaction when firstKeptEntryId is nil"
      (let [{:keys [session]} (seed-transcript! {} [{:role "user" :content "First"}
                                                    {:role "assistant" :content "Second"}])]
        (append-seeded-entries! session [{:type "compaction" :summary "All summarized" :firstKeptEntryId nil :tokensBefore 50}
                                         {:role "user" :content "New question"}])
        (store/truncate-after-compaction! (s) test-key)
        (let [result (store/get-transcript (s) test-key)]
          (should= 3 (count result))
          (should= "session" (:type (nth result 0)))
          (should= "compaction" (:type (nth result 1)))
          (should= "message" (:type (nth result 2))))))

    (it "removes message entries before firstKeptEntryId"
      (let [{:keys [session entries]} (seed-transcript! {} [{:role "user" :content "First"}
                                                            {:role "assistant" :content "Second"}
                                                            {:role "user" :content "Third"}])
            third-msg-id      (:id (last entries))]
        (append-seeded-entries! session [{:type "compaction" :summary "Partial summary" :firstKeptEntryId third-msg-id :tokensBefore 50}
                                         {:role "user" :content "New question"}])
        (store/truncate-after-compaction! (s) test-key)
        (let [result (store/get-transcript (s) test-key)]
          (should= 4 (count result))
          (should= "session" (:type (nth result 0)))
          (should= "message" (:type (nth result 1)))
          (should= [{:type "text" :text "Third"}] (get-in (nth result 1) [:message :content]))
          (should= "compaction" (:type (nth result 2)))
          (should= "message" (:type (nth result 3))))))

    (it "reparents the first kept message to the session header"
      (let [{:keys [session entries]} (seed-transcript! {} [{:role "user" :content "First"}
                                                            {:role "user" :content "Second"}])
            second-id                 (:id (last entries))
            session-id                (:sessionId session)]
        (append-seeded-entries! session [{:type "compaction" :summary "Summary" :firstKeptEntryId second-id :tokensBefore 50}])
        (store/truncate-after-compaction! (s) test-key)
        (let [result   (store/get-transcript (s) test-key)
              kept-msg (nth result 1)]
          (should= session-id (:parentId kept-msg)))))

    (it "returns nil when no entries were removed"
      (let [{:keys [session entries]} (seed-transcript! {} [{:role "user" :content "Only message"}])
            msg-id            (:id (last entries))]
        (append-seeded-entries! session [{:type "compaction" :summary "Summary" :firstKeptEntryId msg-id :tokensBefore 50}])
        (should-be-nil (store/truncate-after-compaction! (s) test-key)))))

  ;; endregion ^^^^^ truncate-after-compaction! ^^^^^

  ;; region ----- delete-session! -----

  (describe "delete-session!"

    (it "removes the session from the index and deletes the transcript"
      (sut/create-session! test-dir test-key)
      (store/delete-session! (s) test-key)
      (should= [] (store/list-sessions-by-agent (s) "main"))
      (should= nil (store/get-session (s) test-key)))

    (it "leaves other sessions intact"
      (sut/create-session! test-dir test-key)
      (sut/create-session! test-dir "other")
      (store/delete-session! (s) test-key)
      (should= 1 (count (store/list-sessions-by-agent (s) "main")))
      (should-not-be-nil (store/get-session (s) "other"))))

  ;; endregion ^^^^^ delete-session! ^^^^^

  ;; region ----- store/create integration -----

  (describe "store/create with :jsonl-edn-index"

    (it "returns a working SessionStore from store/create"
      (let [index-store (store/create test-dir :jsonl-edn-index)]
        (should-not-be-nil index-store)
        (let [session (store/open-session! index-store "test" {})]
          (should= "test" (:id session))
          (should= 1 (count (store/list-sessions index-store)))))))

  ;; endregion ^^^^^ store/create integration ^^^^^
  )
