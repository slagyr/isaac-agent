(ns isaac.session.store.memory-spec
  (:require
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.session.store.impl-common :as c]
    [isaac.session.store.memory :as sut]
    [speclj.core :refer :all]))

(describe "MemorySessionStore"

  (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

  (describe "open-session!"

    (it "creates a session with transcript metadata"
      (let [s     (sut/create-store)
            entry (store/open-session! s "friday-debug" {:crew "main"})]
        (should= "friday-debug" (:id entry))
        (should= "friday-debug.jsonl" (:session-file entry))
        (should= {:kind :cli} (:origin entry))
        (should= :retain (:history-retention entry))
        (should= 0 (:compaction-count entry))
        (should= 1 (count (store/get-transcript s "friday-debug")))))

    (it "reuses an existing session"
      (let [s     (sut/create-store)
            first (store/open-session! s "friday-debug" {:crew "main"})
            again (store/open-session! s "friday-debug" {:crew "main"})]
        (should= (:sessionId first) (:sessionId again))))

    (it "resolves retention from the passed :config"
      #_{:clj-kondo/ignore [:invalid-arity]}
      (let [s     (sut/create-store "/tmp/isaac")
            entry (store/open-session! s "friday-debug"
                                       {:crew "main" :config {:defaults {:history-retention :prune}}})]
        (should= :prune (:history-retention entry)))))

  (describe "append-message!"

    (it "appends transcript messages with parent links"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main"})
        (store/append-message! s "chat" {:role "user" :content "Hello"})
        (store/append-message! s "chat" {:role "assistant" :content "Hi"})
        (let [transcript (store/get-transcript s "chat")
              header     (nth transcript 0)
              user-msg   (nth transcript 1)
              asst-msg   (nth transcript 2)]
          (should= (:id header) (:parentId user-msg))
          (should= (:id user-msg) (:parentId asst-msg))
          (should= [{:type "text" :text "Hello"}] (get-in user-msg [:message :content])))))

    (it "updates last-channel and last-to metadata"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main"})
        (store/append-message! s "chat" {:role "user" :content "Hello" :channel marigold/longwave :to marigold/captain})
        (let [entry (store/get-session s "chat")]
          (should= marigold/longwave (:last-channel entry))
          (should= marigold/captain (:last-to entry))))))

  (describe "update-session!"

    (it "merges compaction state"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main"})
        (store/update-session! s "chat" {:compaction {:strategy :slinky :threshold 80}})
        (store/update-session! s "chat" {:compaction {:tail 40}})
        (should= {:strategy :slinky :threshold 80 :tail 40}
                 (:compaction (store/get-session s "chat"))))))

  (describe "drop-orphan-toolcalls"

    (it "returns the original transcript when there are no orphan tool calls"
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

    (it "removes orphan tool calls and reparents later entries"
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

  (describe "append-compaction! and truncate-after-compaction!"

    (it "appends compaction entries and truncates old messages"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main"})
        (store/append-message! s "chat" {:role "user" :content "First"})
        (let [kept (store/append-message! s "chat" {:role "assistant" :content "Second"})]
          (store/append-compaction! s "chat" {:summary "Summary" :firstKeptEntryId (:id kept) :tokensBefore 10})
          (store/truncate-after-compaction! s "chat")
          (let [transcript (store/get-transcript s "chat")]
            (should= ["session" "message" "compaction"] (mapv :type transcript))
            (should= "Second" (get-in (nth transcript 1) [:message :content])))))))

  (describe "splice-compaction!"

    (it "splices compaction entries into the transcript under prune"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main" :history-retention :prune})
        (let [m1 (store/append-message! s "chat" {:role "user" :content "First"})
              m2 (store/append-message! s "chat" {:role "assistant" :content "Second"})
              m3 (store/append-message! s "chat" {:role "user" :content "Third"})]
           (store/splice-compaction! s "chat" {:summary "Summary"
                                                :firstKeptEntryId (:id m3)
                                                :tokensBefore 20
                                                :compactedEntryIds [(:id m1) (:id m2)]})
          (let [transcript (store/get-transcript s "chat")]
            (should= ["session" "compaction" "message"] (mapv :type transcript))
            (should= (:id m3) (:id (nth transcript 2)))))))

    (it "retains compacted entries physically by default while exposing only the active view"
      (let [s (sut/create-store)]
        (store/open-session! s "chat" {:crew "main"})
        (let [m1 (store/append-message! s "chat" {:role "user" :content "First"})
              m2 (store/append-message! s "chat" {:role "assistant" :content "Second"})
              m3 (store/append-message! s "chat" {:role "user" :content "Third"})]
          (store/splice-compaction! s "chat" {:summary "Summary"
                                                :firstKeptEntryId (:id m3)
                                                :tokensBefore 20
                                                :compactedEntryIds [(:id m1) (:id m2)]})
          (let [transcript (store/get-transcript s "chat")
                active     (store/active-transcript s "chat")
                session    (store/get-session s "chat")]
            (should= ["session" "message" "message" "compaction" "message"] (mapv :type transcript))
            (should= ["compaction" "message"] (mapv :type active))
            (should= 3 (:effective-history-offset session))))))

  ))
