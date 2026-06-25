(ns isaac.session.compaction-spec
  (:require
    [clojure.set :as set]
    [clojure.java.io :as io]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    ;; Loading this registers the :responses factory so make-provider
    ;; can construct a real ResponsesAPI when callers pass `:api "responses"`.
     [isaac.llm.api.responses]
     [isaac.llm.prompt.builder :as prompt-builder]
     [isaac.logger :as log]
     [isaac.fs :as fs]
     [isaac.session.compaction :as sut]
     [isaac.session.store.spi :as store]
     [isaac.session.spec-helper :as storage]
     [isaac.nexus :as nexus]
     [isaac.tool.registry :as tool-registry]
     [speclj.core :refer :all]))

(defn- clean-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(def test-root "/test/compaction")

(describe "Session Compaction"

  (describe "default-threshold"
    (it "returns 0.8 regardless of window size"
      (should= 0.8 (sut/default-threshold 100))
      (should= 0.8 (sut/default-threshold 8192))
      (should= 0.8 (sut/default-threshold 32768))
      (should= 0.8 (sut/default-threshold 1048576))))

  (describe "default-head"
    (it "returns 0.3 regardless of window size"
      (should= 0.3 (sut/default-head 100))
      (should= 0.3 (sut/default-head 8192))
      (should= 0.3 (sut/default-head 32768))
      (should= 0.3 (sut/default-head 1048576))))

  (describe "resolve-config"
    (it "defaults to rubberband with percentage threshold and head"
      (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.8}
               (sut/resolve-config {} 32768)))

    (it "merges session overrides"
      (should= {:async? false :strategy :slinky :head 0.4 :threshold 0.8}
               (sut/resolve-config {:compaction {:strategy :slinky :threshold 0.8 :head 0.4}} 200)))

    (it "coerces string strategy values"
      (should= {:async? false :strategy :slinky :head 0.4 :threshold 0.8}
               (sut/resolve-config {:compaction {:strategy "slinky" :threshold 0.8 :head 0.4}} 200))))

  (describe "should-compact?"
    (it "uses strategy threshold percentage times context-window"
      (should-not (sut/should-compact? 159 {:compaction {:strategy :slinky :threshold 0.8 :head 0.4}} 200))
      (should (sut/should-compact? 160 {:compaction {:strategy :slinky :threshold 0.8 :head 0.4}} 200)))

    (it "keys off the live prompt estimate, not lagging last-input-tokens"
      (should (sut/should-compact? 310778 {:last-input-tokens 100000} 278528))
      (should-not (sut/should-compact? 30 {:total-tokens 5000 :last-input-tokens 5000
                                            :compaction {:strategy :slinky :threshold 0.8 :head 0.4}}
                                          200)))

    (it "returns true when the estimate reaches the default threshold"
      (should (sut/should-compact? 9000 {} 10000)))

    (it "returns true when the estimate equals exactly 80% threshold"
      (should (sut/should-compact? 800 {} 1000)))

    (it "returns false when the estimate is below the configured threshold"
      (should-not (sut/should-compact? 799 {} 1000)))

    (it "returns true when the estimate exceeds context-window"
      (should (sut/should-compact? 15000 {} 10000)))

    (it "returns false for a zero estimate"
      (should-not (sut/should-compact? 0 {} 10000)))

    (it "works with small context windows"
      (should (sut/should-compact? 80 {} 100))
      (should-not (sut/should-compact? 79 {} 100))))

  (describe "estimate-prompt-tokens"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-root :fs (fs/mem-fs)}
        (storage/with-memory-store
          (example))))

    (it "estimates from the live transcript instead of last-input-tokens"
      (let [key-str "isaac:main:cli:chat:estimate123"]
        (storage/create-session! test-root key-str)
        (storage/append-message! test-root key-str {:role "user" :content (apply str (repeat 4000 "word "))})
        (let [estimate (sut/estimate-prompt-tokens key-str {:soul           "You are helpful."
                                                           :context-window 10000
                                                           :model          "test-model"})]
          (should (> estimate 1000))
          (should (< estimate 10000))))))

  (describe "sliding compaction target"
    (it "for rubberband compacts the whole effective history"
      (let [entries [{:id "m1" :tokens 40}
                     {:id "m2" :tokens 40}
                     {:id "m3" :tokens 40}
                     {:id "m4" :tokens 50}]]
        (should= {:compact-count 4 :first-kept-entry-id nil :tokens-before 170}
                 (sut/compaction-target entries {:strategy :rubberband :head 0.4 :threshold 0.8} 200))))

    (it "for slinky compacts only enough oldest entries to preserve the head"
      (let [entries [{:id "m1" :tokens 40}
                     {:id "m2" :tokens 40}
                     {:id "m3" :tokens 40}
                     {:id "m4" :tokens 50}]]
        (should= {:compact-count 2 :first-kept-entry-id "m3" :tokens-before 80}
                 (sut/compaction-target entries {:strategy :slinky :head 0.4 :threshold 0.8} 200)))))

  (describe "tool-call-content"

    (it "reads tool calls from top-level message and vector content"
      (should= {:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}}
               (#'sut/tool-call-content {:message {:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}}}))
      (should= {:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}
               (#'sut/tool-call-content {:message {:content [{:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}]}})))

    (it "returns nil for non-tool-call content"
      (should-be-nil (#'sut/tool-call-content {:message {:content "plain text"}}))))

  (describe "compact!"

    (before-all (clean-dir! test-root))
    (after (clean-dir! test-root))
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example] (storage/with-memory-store (nexus/-with-nested-nexus {:root test-root :fs (fs/mem-fs)} (example))))

    (it "calls chat-fn with summary prompt and appends compaction"
      (let [key-str  "isaac:main:cli:chat:abc123"
            _session (storage/create-session! test-root key-str)
            _msg1    (storage/append-message! test-root key-str
                       {:role "user" :content "Hello"})
            _msg2    (storage/append-message! test-root key-str
                       {:role "assistant" :content "Hi there!"})
            chat-called (atom nil)
            mock-chat  (fn [request _tool-fn]
                         (reset! chat-called request)
                         {:message {:content "Summary of conversation"}})
            result   (sut/compact! key-str
                       {:model          "test-model"
                        :soul           "You are helpful."
                        :context-window 10000
                        :chat-fn        mock-chat})]
        (should-not-be-nil @chat-called)
        (should= "test-model" (:model @chat-called))
        (should= #{"memory_get" "memory_search" "memory_write"}
                 (set (map #(or (:name %) (get-in % [:function :name])) (:tools @chat-called))))
        (should= 2 (count (:messages @chat-called)))
        (should= "system" (-> @chat-called :messages first :role))
        (should= "compaction" (:type result))
        (should= "Summary of conversation" (:summary result))))

    (it "stores a non-blank summary when the model returns empty content"
      (let [key-str  "isaac:main:cli:chat:blank-summary"
            _session (storage/create-session! test-root key-str)
            _msg1    (storage/append-message! test-root key-str {:role "user" :content "Hello"})
            _msg2    (storage/append-message! test-root key-str {:role "assistant" :content "Hi"})
            mock-chat (fn [_request _tool-fn]
                        {:message {:content ""}})
            result   (sut/compact! key-str
                       {:model          "test-model"
                        :soul           "You are helpful."
                        :context-window 10000
                        :chat-fn        mock-chat})]
        (should= "compaction" (:type result))
        (should= prompt-builder/compaction-summary-fallback (:summary result))))

    (it "instructs the compaction prompt to preserve agent and user attribution"
      (let [key-str     "isaac:main:cli:chat:attribution123"
            _session    (storage/create-session! test-root key-str)
            _msg1       (storage/append-message! test-root key-str {:role "user" :content "Please debug this bug."})
            _msg2       (storage/append-message! test-root key-str {:role "assistant" :content "I inspected the websocket path."})
            chat-called (atom nil)
            mock-chat   (fn [request _tool-fn]
                          (reset! chat-called request)
                          {:message {:content "Summary of conversation"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (let [system-prompt (-> @chat-called :messages first :content)]
          (should-contain "first person" system-prompt)
          (should-contain "the user" system-prompt))))

    (it "returns error when chat-fn returns error"
      (let [key-str  "isaac:main:cli:chat:err123"
            _session (storage/create-session! test-root key-str)
            _msg     (storage/append-message! test-root key-str
                       {:role "user" :content "Hello"})
            mock-chat (fn [_request _tool-fn]
                        {:error "LLM unavailable"})
            result    (sut/compact! key-str
                         {:model          "test-model"
                          :soul           "You are helpful."
                          :context-window 10000
                          :chat-fn        mock-chat})]
        (should= "LLM unavailable" (:error result))))

    (it "restricts the compaction tool surface to memory tools even if others are registered"
      (let [key-str   "isaac:main:cli:chat:memory-only"
            _session  (storage/create-session! test-root key-str)
            _msg      (storage/append-message! test-root key-str {:role "user" :content "hello"})
            captured  (atom nil)
            mock-chat (fn [request _tool-fn]
                        (reset! captured request)
                        {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (should= ["memory_get" "memory_search" "memory_write"]
                 (sort (map #(or (:name %) (get-in % [:function :name])) (:tools @captured))))))

    (it "passes explicit runtime args to compaction tools"
      (let [key-str       "isaac:main:cli:chat:tool-runtime"
            explicit-dir  "/tmp/explicit-isaac"
            explicit-store (store/create nil :memory)
            _session      (store/open-session! explicit-store key-str {:crew "main" :cwd test-root})
            _msg          (store/append-message! explicit-store key-str {:role "user" :content "hello"})
            tool-called   (atom nil)
            mock-chat     (fn [_request tool-fn]
                            (reset! tool-called (tool-fn "memory_write" {"content" "note"}))
                            {:message {:content "Summary"}})]
        (with-redefs [tool-registry/execute (fn [name args allowed-tools]
                                              (should= "memory_write" name)
                                              (should= explicit-dir (get args "state_dir"))
                                              (should= explicit-store (get args "session_store"))
                                              (should= key-str (get args "session_key"))
                                              (should= #{"memory_get" "memory_search" "memory_write"} allowed-tools)
                                              {:result "ok"})]
          (sut/compact! key-str
                        {:model          "test-model"
                         :soul           "You are helpful."
                         :context-window 10000
                         :root      explicit-dir
                         :session-store  explicit-store
                         :chat-fn        mock-chat})
          (should= "ok" @tool-called))))

    (it "formats compaction tools for codex responses requests"
      (let [key-str   "isaac:main:cli:chat:codex-tools"
            _session  (storage/create-session! test-root key-str)
            _msg      (storage/append-message! test-root key-str {:role "user" :content "hello"})
            captured  (atom nil)
            mock-chat (fn [request _tool-fn]
                        (reset! captured request)
                        {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :api            (llm-provider/make-provider "chatgpt" {:api "responses"})
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (should= #{{:type "function" :name "memory_get"}
                   {:type "function" :name "memory_search"}
                   {:type "function" :name "memory_write"}}
                 (set (map #(select-keys % [:type :name]) (:tools @captured))))
        (let [memory-write (first (filter #(= "memory_write" (:name %)) (:tools @captured)))]
          (should= "string" (get-in memory-write [:parameters :properties "content" :type]))
          (should-be-nil (get-in memory-write [:parameters :properties "content" :anyOf])))))

    (it "includes tool results in compaction summary requests"
      (let [key-str   "isaac:main:cli:chat:toolresult"
            _session  (storage/create-session! test-root key-str)
            _msg1     (storage/append-message! test-root key-str {:role "user" :content "What is the session info?"})
            _msg2     (storage/append-message! test-root key-str {:role "assistant"
                                                                  :content [{:type "toolCall"
                                                                             :id "tc-1"
                                                                             :name "session_info"
                                                                             :arguments {}}]})
            _msg3     (storage/append-message! test-root key-str {:role "toolResult"
                                                                  :id "tc-1"
                                                                  :content "{\"session\":\"clever-signal\",\"context\":{\"used\":1025871}}"})
            captured  (atom nil)
            mock-chat (fn [request _tool-fn]
                        (reset! captured request)
                        {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :api            (llm-provider/make-provider "chatgpt" {:api "responses"})
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (let [prompt-body (-> @captured :messages second :content)]
          (should-contain "What is the session info?" prompt-body)
          (should-contain "clever-signal" prompt-body)
          (should-contain "1025871" prompt-body))))

    (it "includes tool call identity alongside tool results in compaction summary requests"
      (let [key-str   "isaac:main:cli:chat:toolpair"
            _session  (storage/create-session! test-root key-str)
            _msg1     (storage/append-message! test-root key-str {:role "user" :content "What's in fridge.txt?"})
            _msg2     (storage/append-message! test-root key-str {:role "assistant"
                                                                  :content [{:type      "toolCall"
                                                                             :id        "call_old"
                                                                             :name      "read"
                                                                             :arguments {:filePath "fridge.txt"}}]})
            _msg3     (storage/append-message! test-root key-str {:role "toolResult"
                                                                  :id "call_old"
                                                                  :content "one sad lemon"})
            captured  (atom nil)
            mock-chat (fn [request _tool-fn]
                        (reset! captured request)
                        {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :api            (llm-provider/make-provider "chatgpt" {:api "responses"})
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (let [prompt-body (-> @captured :messages second :content)]
          (should-contain "call_old" prompt-body)
          (should-contain "fridge.txt" prompt-body)
          (should-contain "one sad lemon" prompt-body))))

    (it "truncates tool results in compaction summary requests"
      (let [key-str     "isaac:main:cli:chat:tooltruncate"
            _session    (storage/create-session! test-root key-str)
            huge-result (str (apply str (repeat 80 "A"))
                             (apply str (repeat 160 "M"))
                             (apply str (repeat 80 "Z")))
            _msg1       (storage/append-message! test-root key-str {:role "user" :content "Read the big file"})
            _msg2       (storage/append-message! test-root key-str {:role "assistant"
                                                                    :content [{:type      "toolCall"
                                                                               :id        "call_big"
                                                                               :name      "read"
                                                                               :arguments {:filePath "huge.txt"}}]})
            _msg3       (storage/append-message! test-root key-str {:role "toolResult"
                                                                    :id "call_big"
                                                                    :content huge-result})
            captured    (atom nil)
            mock-chat   (fn [request _tool-fn]
                          (reset! captured request)
                          {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :api            (llm-provider/make-provider "chatgpt" {:api "responses"})
                       :soul           "You are helpful."
                       :context-window 100
                       :chat-fn        mock-chat})
        (let [prompt-body (-> @captured :messages second :content)]
          (should-contain "AAAAAAAA" prompt-body)
          (should-contain "characters truncated" prompt-body)
          (should-contain "ZZZZZZZZ" prompt-body))))

    (it "records tokensBefore in the compaction entry"
      (let [key-str  "isaac:main:cli:chat:tok123"
            _session (storage/create-session! test-root key-str)
            _msg     (storage/append-message! test-root key-str
                       {:role "user" :content "Some message content"})
            mock-chat (fn [_request _tool-fn]
                        {:message {:content "Summary"}})
            result    (sut/compact! key-str
                          {:model          "test-model"
                           :soul           "You are helpful."
                           :context-window 10000
                            :chat-fn        mock-chat})]
        (should (pos? (:tokensBefore result)))))

    (it "logs compaction calculations and chunk planning details"
      (let [key-str    "isaac:main:cli:chat:calc123"
            _session   (storage/create-session! test-root key-str)
            _msg1      (storage/append-message! test-root key-str {:role "user" :content "block A (oldest)" :tokens 70})
            _msg2      (storage/append-message! test-root key-str {:role "assistant" :content "reply A" :tokens 70})
            _msg3      (storage/append-message! test-root key-str {:role "user" :content "block B" :tokens 70})
            _msg4      (storage/append-message! test-root key-str {:role "assistant" :content "reply B" :tokens 70})
            mock-chat  (fn [request _tool-fn]
                         (let [body (-> request :messages second :content)]
                           (cond
                             (re-find #"block A" body) {:message {:content "A1"}}
                             (re-find #"reply A" body) {:message {:content "A2"}}
                             (re-find #"block B" body) {:message {:content "B1"}}
                             (re-find #"reply B" body) {:message {:content "B2"}}
                             :else                     {:message {:content "AB"}})))]
        (log/capture-logs
          (with-redefs [api/estimate-tokens (fn [prompt]
                                                 (let [body (-> prompt :messages second :content)]
                                                   (cond
                                                     (and (re-find #"block A" body) (re-find #"reply A" body)) 320
                                                     (and (re-find #"block B" body) (re-find #"reply B" body)) 320
                                                     (and (re-find #"block A" body) (re-find #"block B" body)) 600
                                                     (re-find #"A1|A2|B1|B2" body) 200
                                                     :else 150)))]
            (sut/compact! key-str
                          {:model          "test-model"
                           :soul           "You are helpful."
                           :context-window 300
                           :chat-fn        mock-chat})
            (let [analysis (first (filter #(= :session/compaction-analysis (:event %)) @log/captured-logs))
                  plan     (first (filter #(= :session/compaction-chunk-plan (:event %)) @log/captured-logs))]
              (should-not-be-nil analysis)
              (should-not-be-nil plan)
              (should= key-str (:session analysis))
              (should= 300 (:context-window analysis))
              (should= 4 (:compact-count analysis))
              (should= 320 (:summary-prompt-tokens analysis))
              (should= true (:needs-chunking analysis))
              (should= 3 (:chunk-count plan))
              (should= [150 150 150] (:chunk-request-tokens plan)))))))

    (it "compacts only the oldest messages that fit in the context window"
      (let [key-str     "isaac:main:cli:chat:partial123"
             _session    (storage/create-session! test-root key-str)
             _config     (storage/update-session! test-root key-str {:compaction {:strategy :slinky :threshold 0.8 :head 0.4}})
             message-1   {:role "user" :content "First question about the project status" :tokens 40}
             message-2   {:role "assistant" :content "The project status is healthy and on track" :tokens 40}
             message-3   {:role "user" :content "Second question about the upcoming release" :tokens 40}
             message-4   {:role "assistant" :content "The release is scheduled for the end of month" :tokens 50}
             _msg1       (storage/append-message! test-root key-str message-1)
             _msg2       (storage/append-message! test-root key-str message-2)
             kept-msg    (storage/append-message! test-root key-str message-3)
             _msg4       (storage/append-message! test-root key-str message-4)
             captured    (atom nil)
             mock-chat   (fn [request _tool-fn]
                           (reset! captured request)
                           {:message {:content "Summary of first exchange"}})
             result      (sut/compact! key-str
                                       {:model          "test-model"
                                        :soul           "You are helpful."
                                        :context-window 200
                                        :chat-fn        mock-chat})
             prompt-body (-> @captured :messages second :content)]
        (should-contain "First question about the project status" prompt-body)
        (should-contain "The project status is healthy and on track" prompt-body)
         (should-not-contain "Second question about the upcoming release" prompt-body)
         (should-not-contain "The release is scheduled for the end of month" prompt-body)
         (should= (:id kept-msg) (:firstKeptEntryId result))))

    (it "does not leave orphan tool calls behind after compaction"
      (let [key-str      "isaac:main:cli:chat:orphan-tools"
            _session     (storage/create-session! test-root key-str)
            _config      (storage/update-session! test-root key-str {:compaction {:strategy :slinky :threshold 0.8 :head 0.4}})
            _msg1        (storage/append-message! test-root key-str {:role "user" :content "Find the error" :tokens 40})
            _tool-call   (storage/append-message! test-root key-str {:role    "assistant"
                                                                     :content [{:type      "toolCall"
                                                                                :id        "tc-1"
                                                                                :name      "grep"
                                                                                :arguments {:q "error"}}]
                                                                     :tokens  10})
            _tool-result (storage/append-message! test-root key-str {:role "toolResult" :id "tc-1" :content "3 matches" :tokens 40})
            first-kept-msg (storage/append-message! test-root key-str {:role "assistant" :content "I found 3 errors." :tokens 40})
            kept-msg     (storage/append-message! test-root key-str {:role "user" :content "What next?" :tokens 50})
            mock-chat    (fn [_request _tool-fn]
                           {:message {:content "Summary of earlier work"}})
            _result      (sut/compact! key-str
                                       {:model          "test-model"
                                        :soul           "You are helpful."
                                        :context-window 200
                                        :chat-fn        mock-chat})
            transcript   (storage/get-transcript test-root key-str)
            messages     (filter #(= "message" (:type %)) transcript)
            tool-call-ids (->> messages
                               (mapcat (fn [entry]
                                         (->> (get-in entry [:message :content])
                                              (filter #(= "toolCall" (:type %)))
                                              (map :id))))
                               set)
            tool-result-ids (->> messages
                                 (filter #(= "toolResult" (get-in % [:message :role])))
                                 (map #(or (get-in % [:message :toolCallId])
                                           (get-in % [:message :id])))
                                 set)]
        (should= (:id first-kept-msg) (get-in (first (filter #(= "compaction" (:type %)) transcript)) [:firstKeptEntryId]))
        (should= #{} (set/difference tool-call-ids tool-result-ids))))

    (it "on a later pass, compacts the current compacted history instead of raw transcript messages"
      (let [key-str      "isaac:main:cli:chat:repeat123"
            _session     (storage/create-session! test-root key-str)
            _msg1        (storage/append-message! test-root key-str {:role "user" :content "Older question"})
            _msg2        (storage/append-message! test-root key-str {:role "assistant" :content "Older answer"})
            kept-msg     (storage/append-message! test-root key-str {:role "user" :content "Recent question"})
            _msg4        (storage/append-message! test-root key-str {:role "assistant" :content "Recent answer"})
            _compact     (storage/append-compaction! test-root key-str
                                                    {:summary          "Summary from first compact"
                                                     :firstKeptEntryId (:id kept-msg)
                                                     :tokensBefore     62})
            captured     (atom nil)
            mock-chat    (fn [request _tool-fn]
                           (reset! captured request)
                           {:message {:content "Summary from second compact"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :soul           "You are helpful."
                       :context-window 10000
                       :chat-fn        mock-chat})
        (let [prompt-body (-> @captured :messages second :content)]
          (should-contain "Summary from first compact" prompt-body)
          (should-contain "Recent question" prompt-body)
          (should-contain "Recent answer" prompt-body)
          (should-not-contain "Older question" prompt-body)
          (should-not-contain "Older answer" prompt-body))))

    (it "updates last-input-tokens after compaction without resetting cumulative totals"
      (let [key-str   "isaac:main:cli:chat:rebound123"
            _session  (storage/create-session! test-root key-str)
            _msg1     (storage/append-message! test-root key-str {:role "user" :content "Summarize"})
            _msg2     (storage/append-message! test-root key-str {:role "assistant" :content "Sure"})
            _tokens   (storage/update-tokens! test-root key-str {:input-tokens 120 :output-tokens 30})
            mock-chat (fn [_request _tool-fn]
                        {:message {:content "Summary"}})]
        (sut/compact! key-str
                      {:model          "test-model"
                       :soul           "You are helpful."
                       :context-window 200
                         :chat-fn        mock-chat})
        (let [entry (storage/get-session test-root key-str)]
          (should= 150 (:total-tokens entry))
          (should= 120 (:input-tokens entry))
          (should= 30 (:output-tokens entry))
          (should (< (:last-input-tokens entry) 200)))))

    (it "chunks compaction when the one-shot summary request exceeds the context window"
      (let [key-str    "isaac:main:cli:chat:chunk123"
             _session   (storage/create-session! test-root key-str)
             _msg1      (storage/append-message! test-root key-str {:role "user" :content "block A (oldest)" :tokens 60})
             _msg2      (storage/append-message! test-root key-str {:role "assistant" :content "reply A" :tokens 60})
             _msg3      (storage/append-message! test-root key-str {:role "user" :content "block B" :tokens 60})
             _msg4      (storage/append-message! test-root key-str {:role "assistant" :content "reply B" :tokens 60})
             _msg5      (storage/append-message! test-root key-str {:role "user" :content "latest question" :tokens 61})
             calls      (atom [])
             mock-chat  (fn [request _tool-fn]
                          (swap! calls conj request)
                          (if (> (api/estimate-tokens request) 300)
                            {:error :llm-error :message "context length exceeded"}
                            (let [body (-> request :messages second :content)]
                              (cond
                                (and (re-find #"block A" body) (re-find #"reply A" body))
                                {:message {:content "A"}}

                                (and (re-find #"block B" body) (re-find #"reply B" body))
                                {:message {:content "B"}}

                                (re-find #"latest question" body)
                                {:message {:content "Q"}}

                                :else
                                {:message {:content "ABQ"}}))))]
        (log/capture-logs
          (with-redefs [api/estimate-tokens
                        (fn [req]
                          (let [body (-> req :messages second :content str)]
                            (cond
                              (and (re-find #"block A" body) (re-find #"latest question" body)) 500
                              (and (re-find #"block B" body) (re-find #"latest question" body)) 500
                              :else 100)))]
            (let [result (sut/compact! key-str
                                       {:model          "test-model"
                                        :soul           "You are helpful."
                                        :context-window 300
                                        :chat-fn        mock-chat})]
              (should= "ABQ" (:summary result))
              (should= 3 (count @calls))
              (let [entry (first (filter #(= :session/compaction-chunked (:event %)) @log/captured-logs))]
                (should-not-be-nil entry)
                (should= key-str (:session entry))))))))

    (it "chunks compaction even when only four compacted entries overflow one shot"
      (let [key-str    "isaac:main:cli:chat:chunk4"
             _session   (storage/create-session! test-root key-str)
             _msg1      (storage/append-message! test-root key-str {:role "user" :content "block A (oldest)" :tokens 70})
             _msg2      (storage/append-message! test-root key-str {:role "assistant" :content "reply A" :tokens 70})
             _msg3      (storage/append-message! test-root key-str {:role "user" :content "block B" :tokens 70})
             _msg4      (storage/append-message! test-root key-str {:role "assistant" :content "reply B" :tokens 70})
             calls      (atom [])
             mock-chat  (fn [request _tool-fn]
                          (swap! calls conj request)
                          (if (> (api/estimate-tokens request) 300)
                            {:error :llm-error :message "context length exceeded"}
                            (let [body (-> request :messages second :content)]
                              (cond
                                (re-find #"block A" body)
                                {:message {:content "A1"}}

                                (re-find #"reply A" body)
                                {:message {:content "A2"}}

                                (re-find #"block B" body)
                                {:message {:content "B1"}}

                                (re-find #"reply B" body)
                                {:message {:content "B2"}}

                                :else
                                {:message {:content "AB"}}))))]
        (log/capture-logs
          (with-redefs [api/estimate-tokens (fn [prompt]
                                                 (let [body (-> prompt :messages second :content)]
                                                   (cond
                                                     (and (re-find #"block A" body) (re-find #"reply A" body)) 320
                                                     (and (re-find #"block B" body) (re-find #"reply B" body)) 320
                                                     (and (re-find #"block A" body) (re-find #"block B" body)) 600
                                                     (re-find #"A1|A2|B1|B2" body) 200
                                                     :else 150)))]
            (let [result (sut/compact! key-str
                                       {:model          "test-model"
                                        :soul           "You are helpful."
                                        :context-window 300
                                        :chat-fn        mock-chat})]
              (should= "AB" (:summary result))
              (should= 4 (count @calls))
              (let [entry (first (filter #(= :session/compaction-chunked (:event %)) @log/captured-logs))]
                (should-not-be-nil entry)
                (should= key-str (:session entry))))))))

    (it "chunks compaction for normal transcript entries without explicit token metadata"
      (let [key-str    "isaac:main:cli:chat:livechunk123"
             _session   (storage/create-session! test-root key-str)
             _msg1      (storage/append-message! test-root key-str {:role "user" :content "block A (oldest)"})
            _msg2      (storage/append-message! test-root key-str {:role "assistant" :content "reply A"})
            _msg3      (storage/append-message! test-root key-str {:role "user" :content "block B"})
            _msg4      (storage/append-message! test-root key-str {:role "assistant" :content "reply B"})
            _msg5      (storage/append-message! test-root key-str {:role "user" :content "latest question"})
            calls      (atom [])
            mock-chat  (fn [request _tool-fn]
                         (swap! calls conj request)
                         (let [body (-> request :messages second :content)]
                           (cond
                             (and (re-find #"block A" body) (re-find #"reply A" body) (not (re-find #"block B" body)))
                             {:message {:content "summary of A"}}

                             (and (re-find #"block B" body) (re-find #"reply B" body))
                             {:message {:content "summary of B"}}

                             :else
                             {:message {:content "summary of summaries"}})))]
        (log/capture-logs
          (with-redefs [api/estimate-tokens (fn [prompt]
                                                 (let [messages (:messages prompt)
                                                       body     (-> (if (= 1 (count messages))
                                                                      (first messages)
                                                                      (second messages))
                                                                    :content)]
                                                   (if (= 1 (count messages))
                                                     (cond
                                                        (re-find #"block A|reply A|block B|reply B" body) 15
                                                        (re-find #"latest question" body)                10
                                                        :else                                              10)
                                                      (cond
                                                        (and (re-find #"block A" body) (re-find #"block B" body) (re-find #"latest question" body)) 220
                                                        :else 40))))]
            (let [result (sut/compact! key-str
                                       {:model          "test-model"
                                        :soul           "You are helpful."
                                        :context-window 60
                                        :chat-fn        mock-chat})]
              (should= "summary of summaries" (:summary result))
              (should= 3 (count @calls))
              (let [entry (first (filter #(= :session/compaction-chunked (:event %)) @log/captured-logs))]
                (should-not-be-nil entry)
                (should= key-str (:session entry))))))))))
