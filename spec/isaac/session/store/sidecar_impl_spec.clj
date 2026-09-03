(ns isaac.session.store.sidecar-impl-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sut]
    [isaac.session.store.impl-common :as c]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def test-dir "/test/storage")
(def test-key "user1")
(def test-provider-id marigold/helm-systems)

(defn- s [] (sut/create-store test-dir))

(defn- clean-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- sidecar-path [id]
  (c/session-edn-path test-dir id))

(defn- current-path [id]
  (c/current-transcript-path test-dir id))

(defn- read-sidecar [id]
  (edn/read-string (fs/slurp (nexus/get :fs) (sidecar-path id))))

(defn- seed-transcript! [opts messages]
  (let [session      (sut/create-session! test-dir test-key opts)
        session-id   (:sessionId session)
        id           (:id session)
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
    (c/write-transcript! test-dir id (into [header] entries) fs*)
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
  (let [fs*        (nexus/get :fs)
        id         (:id session)
        transcript (c/read-transcript-raw test-dir id fs*)
        additions  (loop [remaining specs parent-id (:id (last transcript)) out []]
                     (if-let [spec (first remaining)]
                       (let [entry (seeded-entry parent-id spec)]
                         (recur (next remaining) (:id entry) (conj out entry)))
                       out))]
    (c/write-transcript! test-dir id (into transcript additions) fs*)
    additions))

(describe "Session Storage"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  ;; region ----- create-session! -----

  (describe "create-session!"

    (it "creates a new session with sidecar and transcript"
      (let [entry (sut/create-session! test-dir test-key)]
        (should= "user1" (:key entry))
        (should (string? (:sessionId entry)))
        (should-not (contains? entry :session-file))
        (should (fs/exists? (nexus/get :fs) (current-path test-key)))
        (should (fs/exists? (nexus/get :fs) (sidecar-path test-key)))
        (should-be-nil (:channel entry))
        (should-be-nil (:chat-type entry))
        (should-not (contains? entry :chatType))
        (should= :retain (:history-retention entry))
        (should= 0 (:compaction-count entry))
        (should= 0 (:input-tokens entry))
        (should= 0 (:output-tokens entry))
        (should= 0 (:total-tokens entry))))

    (it "supports an explicit fs arity without binding a thread-local fs"
      (let [mem   (fs/mem-fs)
            entry (sut/create-session! test-dir test-key {} mem)]
        (should= test-key (:id entry))
        (should= test-key (:id (store/get-session (sut/create-store test-dir mem) test-key)))
        (should (fs/exists? mem (sidecar-path test-key)))))

    (it "stores an explicit history-retention override"
      (let [entry (sut/create-session! test-dir test-key {:history-retention :prune})]
        (should= :prune (:history-retention entry))))

    (it "writes session metadata with kebab-case schema keys"
      (sut/create-session! test-dir test-key {:chatType "direct"})
      (let [entry (read-sidecar test-key)]
        (should= "direct" (:chat-type entry))
        (should (contains? entry :created-at))
        (should-not (contains? entry :chatType))
        (should-not (contains? entry :createdAt))))

    (it "does not include an agent field on newly created sessions"
      (let [entry (sut/create-session! test-dir test-key)]
        (should= "main" (:crew entry))
        (should-not (contains? entry :agent))))

    (it "stores cwd in the sidecar entry"
      (let [entry (sut/create-session! test-dir test-key)]
        (should (string? (:cwd entry)))
        (should (not (clojure.string/blank? (:cwd entry))))))

    (it "defaults origin to cli when none is provided"
      (let [entry (sut/create-session! test-dir test-key)]
        (should= {:kind :cli} (:origin entry))))

    (it "stores an explicit origin in the sidecar entry"
      (let [entry (sut/create-session! test-dir test-key {:origin {:kind :cron :name "health-check"}})]
        (should= {:kind :cron :name "health-check"} (:origin entry))))

    (it "writes a session header to the transcript"
      (sut/create-session! test-dir test-key)
      (let [transcript (store/get-transcript (s) test-key)]
        (should= 1 (count transcript))
        (should= "session" (:type (first transcript)))))

    (it "resumes an existing session instead of creating a duplicate"
      (let [first  (sut/create-session! test-dir test-key)
            second (sut/create-session! test-dir test-key)]
        (should= (:sessionId first) (:sessionId second))
        (should= 1 (count (store/list-sessions-by-agent (s) "main")))))

    (it "rejects a different session name that slug-collides with an existing session"
      (sut/create-session! test-dir "friday-debug")
      (let [error (try
                    (sut/create-session! test-dir "Friday Debug")
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (should-not-be-nil error)
        (should= "session already exists: friday-debug" (ex-message error))))

    (it "creates a fresh session when the sidecar exists but its transcript is missing"
      (let [first  (sut/create-session! test-dir test-key)
            _      (fs/delete (nexus/get :fs) (current-path test-key))
            second (sut/create-session! test-dir test-key)]
        (should-not= (:sessionId first) (:sessionId second))
        (should= 1 (count (store/list-sessions-by-agent (s) "main")))))

    (it "uses sequential names for unnamed sessions when configured"
      (do (config/dangerously-install-config! {:sessions {:naming-strategy :sequential}} "spec")
        (let [first  (sut/create-session! test-dir nil)
              second (sut/create-session! test-dir nil)]
          (should= "session-1" (:name first))
          (should= "session-2" (:name second)))))

    (it "persists the sequential counter across unnamed creates"
      (do (config/dangerously-install-config! {:sessions {:naming-strategy :sequential}} "spec")
        (sut/create-session! test-dir nil)
        (should= "1" (str/trim (fs/slurp (nexus/get :fs) (str test-dir "/sessions/.counter"))))
        (let [entry (sut/create-session! test-dir nil)]
          (should= "session-2" (:name entry))
          (should= "2" (str/trim (fs/slurp (nexus/get :fs) (str test-dir "/sessions/.counter")))))))

    (it "prefers an explicit name over the configured sequential strategy"
      (do (config/dangerously-install-config! {:sessions {:naming-strategy :sequential}} "spec")
        (let [entry (sut/create-session! test-dir "friday-debug")]
          (should= "friday-debug" (:name entry))
          (should= nil (store/get-session (s) "session-1"))))))

  ;; endregion ^^^^^ create-session! ^^^^^

  ;; region ----- list-sessions -----

  (describe "list-sessions"

    (it "returns empty when no sessions"
      (should= [] (store/list-sessions-by-agent (s) "main")))

    (it "lists created sessions"
      (sut/create-session! test-dir test-key)
      (sut/create-session! test-dir "user2")
      (should= 2 (count (store/list-sessions-by-agent (s) "main"))))

    (it "lists sessions without reading transcripts"
      (sut/create-session! test-dir test-key)
      (should= 1 (count (store/list-sessions-by-agent (s) "main"))))

    (it "writes sidecar files keyed by session id"
      (let [entry      (sut/create-session! test-dir "Friday Debug!")
            entry-map   (read-sidecar "friday-debug")]
        (should= "Friday Debug!" (:name entry-map))
        (should (fs/exists? (nexus/get :fs) (current-path "friday-debug")))))

    (it "errors when opening an unmigrated jsonl session"
      (let [fs* (nexus/get :fs)]
        (fs/mkdirs fs* (str test-dir "/sessions"))
        (fs/spit fs* (str test-dir "/sessions/legacy.jsonl")
                 "{\"type\":\"session\",\"id\":\"h1\"}\n")
        (try
          (store/get-session (s) "legacy")
          (should-fail "expected unmigrated error")
          (catch clojure.lang.ExceptionInfo e
            (should= :unmigrated (:reason (ex-data e))))))))

  ;; endregion ^^^^^ list-sessions ^^^^^

  ;; region ----- append-message! -----

  (describe "append-message!"

    (it "appends a message to the transcript"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "Hello"})
      (let [transcript (store/get-transcript (s) test-key)]
        (should= 2 (count transcript))
        (should= "message" (:type (second transcript)))
        (should= "user" (get-in (second transcript) [:message :role]))))

    (it "links entries via parentId"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "First"})
      (store/append-message! (s) test-key {:role "assistant" :content "Second"})
      (let [transcript (store/get-transcript (s) test-key)
            header     (first transcript)
            msg1       (second transcript)
            msg2       (nth transcript 2)]
        (should= (:id header) (:parentId msg1))
        (should= (:id msg1) (:parentId msg2))))

    (it "updates last-channel and last-to on routing messages"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "Hi" :channel marigold/skybeam :to marigold/captain})
      (let [listing (store/list-sessions-by-agent (s) "main")
            entry   (first listing)]
        (should= marigold/skybeam (:last-channel entry))
        (should= marigold/captain (:last-to entry))))

    (it "does not add an agent field when assistant messages resolve a crew"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "assistant" :content "Hello"})
      (let [entry      (first (store/list-sessions-by-agent (s) "main"))
            transcript (store/get-transcript (s) test-key)
            message    (get-in (last transcript) [:message])]
        (should= "main" (:crew entry))
        (should-not (contains? entry :agent))
        (should= "main" (:crew message))
        (should-not (contains? message :agent))))

    (it "stamps content-based tokens on appended transcript messages"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "Read my notes please"})
      (store/append-message! (s) test-key {:role "assistant"
                                           :content [{:type "toolCall"
                                                      :id "tc-1"
                                                      :name "fs__read"
                                                      :arguments {:file_path "notes.txt"}}]})
      (store/append-message! (s) test-key {:role "toolResult"
                                           :toolCallId "tc-1"
                                           :content (apply str (repeat 80 "x"))})
      (store/append-message! (s) test-key {:role "assistant"
                                           :content "Forty chars of reply text, exactly forty"})
      (let [[user tool-call tool-result assistant]
            (->> (store/get-transcript (s) test-key)
                 rest)]
        (should= 5 (:tokens user))
        (should (pos? (:tokens tool-call)))
        (should= 20 (:tokens tool-result))
        (should= 10 (:tokens assistant)))))

  ;; endregion ^^^^^ append-message! ^^^^^

  ;; region ----- get-transcript -----

  (describe "get-transcript"

    (it "does not log orphan tool call diagnostics"
      (sut/create-session! test-dir test-key)
      (store/append-message! (s) test-key {:role "user" :content "What's in fridge.txt?"})
      (store/append-message! (s) test-key {:role    "assistant"
                                            :content [{:type      "toolCall"
                                                       :id        "call_old"
                                                       :name      "read"
                                                       :arguments {:filePath "fridge.txt"}}]})
      (log/capture-logs
        (let [transcript (store/get-transcript (s) test-key)
              events     (map :event @log/captured-logs)]
          (should= 3 (count transcript))
          (should-not-contain :transcript/orphan-toolcalls-detected events)))))

  ;; endregion ^^^^^ get-transcript ^^^^^

  ;; region ----- append-error! -----

  (describe "append-error!"

    (it "stores errors as type error entries"
      (sut/create-session! test-dir test-key)
      (store/append-error! (s) test-key {:content "something went wrong"
                                          :error   ":connection-refused"
                                          :model   marigold/helm-spark
                                          :provider test-provider-id})
      (let [transcript (store/get-transcript (s) test-key)
            last-entry (last transcript)]
        (should= "error" (:type last-entry))
        (should= "something went wrong" (:content last-entry))
        (should= ":connection-refused" (:error last-entry))
        (should= marigold/helm-spark (:model last-entry))
        (should= test-provider-id (:provider last-entry)))))

  ;; endregion ^^^^^ append-error! ^^^^^

  ;; region ----- update-session! -----

  (describe "rename-session!"

    (it "moves sidecar, transcript, and entry fields to the new key"
      (let [session (sut/create-session! test-dir test-key {:tags #{:project/x}})
            _       (store/update-session! (s) test-key {:total-tokens 5000 :last-input-tokens 5000})
            _       (store/append-message! (s) test-key {:role "user" :content "hello"})
            result  (store/rename-session! (s) test-key "skipper")
            entry   (store/get-session (s) "skipper")
            fs*     (nexus/get :fs)]
        (should= "skipper" (:id result))
        (should= "skipper" (:key entry))
        (should= #{:project/x} (:tags entry))
        (should= 5000 (:total-tokens entry))
        (should-be-nil (store/get-session (s) test-key))
        (should-not (fs/exists? fs* (sidecar-path test-key)))
        (should (fs/exists? fs* (sidecar-path "skipper")))
        (should-not (fs/exists? fs* (current-path test-key)))
        (should (fs/exists? fs* (current-path "skipper")))
        (should= 2 (count (store/get-transcript (s) "skipper")))))

    (it "refuses a collision without clobbering the target"
      (sut/create-session! test-dir test-key)
      (sut/create-session! test-dir "skipper" {:crew "ketch"})
      (try
        (store/rename-session! (s) test-key "skipper")
        (should-fail "expected collision")
        (catch clojure.lang.ExceptionInfo e
          (should= :collision (:reason (ex-data e)))
          (should= "ketch" (:crew (store/get-session (s) "skipper")))
          (should-not-be-nil (store/get-session (s) test-key)))))

    (it "refuses renaming an in-flight session"
      (let [store (s)]
        (sut/create-session! test-dir test-key)
        (store/mark-in-flight! store test-key)
        (try
          (store/rename-session! store test-key "skipper")
          (should-fail "expected in-flight refusal")
          (catch clojure.lang.ExceptionInfo e
            (should= :in-flight (:reason (ex-data e)))
            (should-not-be-nil (store/get-session store test-key))
            (should-be-nil (store/get-session store "skipper")))))))

  (describe "update-session!"

    (it "updates arbitrary fields on the sidecar entry"
      (sut/create-session! test-dir test-key)
      (store/update-session! (s) test-key {:input-tokens 42})
      (let [entry (first (store/list-sessions-by-agent (s) "main"))]
        (should= 42 (:input-tokens entry))))

    (it "normalizes updated-at to ISO timestamp"
      (sut/create-session! test-dir test-key)
      (store/update-session! (s) test-key {:updated-at 1000})
      (let [entry (first (store/list-sessions-by-agent (s) "main"))]
        (should (string? (:updated-at entry)))
        (should (re-find #"^\d{4}-\d{2}-\d{2}T" (:updated-at entry)))))

    (it "conforms legacy-shaped updates before writing the sidecar"
      (sut/create-session! test-dir test-key)
      (store/update-session! (s) test-key {:createdAt "2026-05-08T10:00:00" :chatType "direct"})
      (let [entry (read-sidecar test-key)]
        (should= "2026-05-08T10:00:00" (:created-at entry))
        (should= "direct" (:chat-type entry))
        (should-not (contains? entry :createdAt))
        (should-not (contains? entry :chatType)))))

  ;; endregion ^^^^^ update-session! ^^^^^

  ;; region ----- append-compaction! -----

  (describe "append-compaction!"

    (it "appends a compaction entry and increments count"
      (let [{:keys [entries]} (seed-transcript! {} [{:role "user" :content "Hello"}])
            last-id           (:id (last entries))]
        (store/append-compaction! (s) test-key
                                  {:summary "A summary" :firstKeptEntryId last-id :tokensBefore 100})
        (let [updated-transcript (store/get-transcript (s) test-key)
              compaction         (last updated-transcript)
              listing            (store/list-sessions-by-agent (s) "main")
              entry              (first listing)]
          (should= "compaction" (:type compaction))
          (should= "A summary" (:summary compaction))
          (should= 1 (:compaction-count entry))))))

  ;; endregion ^^^^^ append-compaction! ^^^^^

  ;; region ----- splice-compaction! -----

  (describe "splice-compaction!"

    (it "replaces compacted entries in place and preserves later entries under prune"
      (let [{:keys [session entries]} (seed-transcript! {:history-retention :prune}
                                                        [{:role "user" :content "First"}
                                                         {:role "assistant" :content "Second"}
                                                         {:role "user" :content "Third"}
                                                         {:role "assistant" :content "Fourth"}
                                                         {:role "user" :content "Later"}])
            session-id                (:sessionId session)
            [first-msg second-msg third-msg fourth-msg later-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary          "Summary"
                                   :firstKeptEntryId (:id third-msg)
                                   :tokensBefore     50
                                   :compactedEntryIds [(:id first-msg) (:id second-msg)]})
        (let [result         (store/get-transcript (s) test-key)
              compaction     (nth result 0)
              kept-msg       (nth result 1)
              kept-assistant (nth result 2)
              surviving-msg  (nth result 3)]
          (should= 4 (count result))
          (should= "compaction" (:type compaction))
          (should= session-id (:parentId compaction))
          (should= "message" (:type kept-msg))
          (should= [{:type "text" :text "Third"}] (get-in kept-msg [:message :content]))
          (should= (:id compaction) (:parentId kept-msg))
          (should= (:id third-msg) (:id kept-msg))
          (should= (:id fourth-msg) (:id kept-assistant))
          (should= (:id later-msg) (:id surviving-msg))))

    (it "reparents surviving entries whose parent was compacted under prune"
      (let [{:keys [session entries]} (seed-transcript! {:history-retention :prune}
                                                        [{:role "user" :content "First"}
                                                         {:role "assistant" :content "Second"}
                                                         {:role "user" :content "Later"}])
            session-id                (:sessionId session)
            [first-msg second-msg later-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary          "Summary"
                                   :firstKeptEntryId nil
                                   :tokensBefore     50
                                   :compactedEntryIds [(:id first-msg) (:id second-msg)]})
        (let [result     (store/get-transcript (s) test-key)
              compaction (nth result 0)
              kept-msg   (nth result 1)]
          (should= 2 (count result))
          (should= session-id (:parentId compaction))
          (should= (:id compaction) (:parentId kept-msg))
          (should= (:id later-msg) (:id kept-msg)))))

    (it "preserves prior compaction entries during repeated compaction"
      (sut/create-session! test-dir test-key)
      (let [session-id  (:id (first (store/get-transcript (s) test-key)))
            first-msg   (store/append-message! (s) test-key {:role "user" :content "First"})
            second-msg  (store/append-message! (s) test-key {:role "assistant" :content "Second"})
            compaction1 (store/append-compaction! (s) test-key
                                                  {:summary          "Summary one"
                                                   :firstKeptEntryId nil
                                                   :tokensBefore     50})
            _           (store/truncate-after-compaction! (s) test-key)
            third-msg   (store/append-message! (s) test-key {:role "user" :content "Third"})]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Summary two"
                                   :firstKeptEntryId  nil
                                   :tokensBefore      60
                                   :compactedEntryIds [(:id compaction1) (:id third-msg)]})
        (let [result         (store/get-transcript (s) test-key)
              first-summary  (nth result 1)
              second-summary (nth result 2)]
          (should= 3 (count result))
          (should= session-id (:parentId first-summary))
          (should= "Summary one" (:summary first-summary))
          (should= (:id first-summary) (:parentId second-summary))
          (should= "Summary two" (:summary second-summary)))))

    (it "does not freeze a segment under prune"
      (let [{:keys [entries]} (seed-transcript! {:history-retention :prune}
                                                [{:role "user" :content "Hello"}
                                                 {:role "assistant" :content "Hi"}])
            [first-msg second-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Compacted"
                                   :firstKeptEntryId  nil
                                   :tokensBefore      10
                                   :compactedEntryIds [(:id first-msg) (:id second-msg)]})
        (should-not (fs/exists? (nexus/get :fs) (c/frozen-transcript-path test-dir test-key 0)))
        (should (fs/exists? (nexus/get :fs) (current-path test-key)))))

    (it "does not log splice diagnostics"
      (let [{:keys [entries]} (seed-transcript! {} [{:role "user" :content "Hello"}
                                                    {:role "assistant" :content "Hi"}])
            [first-msg second-msg] entries]
        (log/capture-logs
          (store/splice-compaction! (s) test-key
                                    {:summary           "Compacted"
                                     :firstKeptEntryId  nil
                                     :tokensBefore      10
                                     :compactedEntryIds [(:id first-msg) (:id second-msg)]})
          (let [events (map :event @log/captured-logs)]
            (should-not-contain :transcript/splice-start events)
            (should-not-contain :transcript/splice-written events)))))

    (it "drops orphan tool calls after compaction splice"
      (let [{:keys [entries]} (seed-transcript! {:history-retention :prune}
                                                [{:role "user" :content "What's in fridge.txt?"}
                                                 {:role    "assistant"
                                                  :content [{:type      "toolCall"
                                                             :id        "call_old"
                                                             :name      "read"
                                                             :arguments {:filePath "fridge.txt"}}]}
                                                 {:role "toolResult" :id "call_old" :content "one sad lemon"}
                                                 {:role "assistant" :content "The fridge has a lemon."}])
            [_msg1 _tool-call tool-result kept-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Summary"
                                   :firstKeptEntryId  (:id kept-msg)
                                   :tokensBefore      20
                                   :compactedEntryIds [(:id tool-result)]})
        (let [transcript (store/get-transcript (s) test-key)
              rendered   (pr-str transcript)]
          (should-not-contain "call_old" rendered)
          (should-contain "The fridge has a lemon." rendered))))

    (it "preserves paired tool calls after compaction splice when results use toolCallId"
      (let [{:keys [entries]} (seed-transcript! {}
                                                [{:role "user" :content "Earlier question"}
                                                 {:role    "assistant"
                                                  :content [{:type      "toolCall"
                                                             :id        "call_old"
                                                             :name      "read"
                                                             :arguments {:filePath "fridge.txt"}}]}
                                                 {:role "toolResult" :toolCallId "call_old" :content "one sad lemon"}
                                                 {:role "assistant" :content "The fridge has a lemon."}])
            [old-msg tool-call _tool-result kept-msg] entries]
        (store/splice-compaction! (s) test-key
                                  {:summary           "Summary"
                                   :firstKeptEntryId  (:id tool-call)
                                   :tokensBefore      20
                                   :compactedEntryIds [(:id old-msg)]})
        (let [transcript    (store/get-transcript (s) test-key)
              tool-call-ids (->> transcript
                                 (filter #(= "message" (:type %)))
                                 (mapcat (fn [entry]
                                           (->> (get-in entry [:message :content])
                                                (filter #(= "toolCall" (:type %)))
                                                (map :id))))
                                 set)
              rendered      (pr-str transcript)]
          (should-contain "call_old" tool-call-ids)
          (should-contain "one sad lemon" rendered)
          (should-contain "The fridge has a lemon." rendered))))

    (it "freezes current.ednl as 0.ednl under retain"
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
        (should (fs/exists? (nexus/get :fs) (c/frozen-transcript-path test-dir test-key 0)))
        (let [frozen  (c/read-ednl (nexus/get :fs) (c/frozen-transcript-path test-dir test-key 0))
              current (store/get-transcript (s) test-key)]
          (should-contain "First" (pr-str frozen))
          (should= ["compaction" "message"] (mapv :type current))
          (should= 1 (:segment (store/get-session (s) test-key))))))))

  ;; endregion ^^^^^ splice-compaction! ^^^^^

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

  ;; region ----- update-tokens! -----

  (describe "update-tokens!"

    (it "accumulates token counts without inferring last-input-tokens from cumulative input"
      (sut/create-session! test-dir test-key)
      (sut/update-tokens! test-dir test-key {:input-tokens 10 :output-tokens 5})
      (sut/update-tokens! test-dir test-key {:input-tokens 20 :output-tokens 15})
      (let [entry (first (store/list-sessions-by-agent (s) "main"))]
        (should= 30 (:input-tokens entry))
        (should= 20 (:turn-input-tokens entry))
        (should= 20 (:output-tokens entry))
        (should= 50 (:total-tokens entry))
        (should= 0 (:last-input-tokens entry))))

    (it "replaces last-input-tokens when provided explicitly"
      (sut/create-session! test-dir test-key)
      (sut/update-tokens! test-dir test-key {:input-tokens 10 :output-tokens 5 :last-input-tokens 10})
      (sut/update-tokens! test-dir test-key {:input-tokens 42 :output-tokens 1 :last-input-tokens 42})
      (let [entry (first (store/list-sessions-by-agent (s) "main"))]
        (should= 42 (:last-input-tokens entry))
        (should= 58 (:total-tokens entry))))

    (it "tracks cache tokens when provided"
      (sut/create-session! test-dir test-key)
      (sut/update-tokens! test-dir test-key {:input-tokens 10 :output-tokens 5 :cache-read 3 :cache-write 2})
      (let [entry (first (store/list-sessions-by-agent (s) "main"))]
        (should= 3 (:cache-read entry))
        (should= 2 (:cache-write entry)))))

  ;; endregion ^^^^^ update-tokens! ^^^^^

  ;; region ----- Logging -----

  (describe "session lifecycle logging"

    (helper/with-captured-logs)

    (it "logs session creation"
      (sut/create-session! test-dir test-key)
      (should (some #(= :session/created (:event %)) @log/captured-logs)))

    (it "logs session resume when session already exists"
      (sut/create-session! test-dir test-key)
      (sut/create-session! test-dir test-key)
      (should (some #(= :session/opened (:event %)) @log/captured-logs)))

    )

  ;; endregion ^^^^^ Logging ^^^^^
  )
