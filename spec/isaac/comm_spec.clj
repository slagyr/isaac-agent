(ns isaac.comm-spec
  (:require
    [isaac.bridge.prompt-cli :as prompt-cli]
    [isaac.comm.protocol :as sut]
    [isaac.comm.cli :as cli-comm]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.null :as null-comm]
    [speclj.core :refer :all]))

(describe "Channel protocol"

  (it "can dispatch all channel callbacks"
    (let [events (atom [])
          ch     (reify sut/Comm
                    (on-turn-start [_ session-key input]
                      (swap! events conj [:turn-start session-key input]))
                    (on-text-chunk [_ session-key text]
                      (swap! events conj [:text-chunk session-key text]))
                    (on-tool-call [_ session-key tool-call]
                      (swap! events conj [:tool-call session-key tool-call]))
                    (on-tool-cancel [_ session-key tool-call]
                      (swap! events conj [:tool-cancel session-key tool-call]))
                    (on-tool-result [_ session-key tool-call result]
                      (swap! events conj [:tool-result session-key tool-call result]))
                    (on-compaction-start [_ session-key payload]
                      (swap! events conj [:compaction-start session-key payload]))
                    (on-compaction-success [_ session-key payload]
                      (swap! events conj [:compaction-success session-key payload]))
                    (on-compaction-failure [_ session-key payload]
                      (swap! events conj [:compaction-failure session-key payload]))
                    (on-compaction-disabled [_ session-key payload]
                      (swap! events conj [:compaction-disabled session-key payload]))
                    (on-turn-end [_ session-key result]
                      (swap! events conj [:turn-end session-key result])))]
      (sut/on-turn-start ch "session-1" "hello")
      (sut/on-text-chunk ch "session-1" "chunk")
      (sut/on-tool-call ch "session-1" {:name "read"})
      (sut/on-tool-cancel ch "session-1" {:name "read"})
      (sut/on-tool-result ch "session-1" {:name "read"} "ok")
      (sut/on-compaction-start ch "session-1" {:total-tokens 95})
      (sut/on-compaction-success ch "session-1" {:tokens-saved 10})
      (sut/on-compaction-failure ch "session-1" {:error :llm-error})
      (sut/on-compaction-disabled ch "session-1" {:reason :too-many-failures})
      (sut/on-turn-end ch "session-1" {:content "done"})
      (should= 10 (count @events))))

  (it "built-in comm implementations dispatch every protocol method without AbstractMethodError"
    (let [channels [cli-comm/channel
                    (memory-comm/channel (atom []))
                    null-comm/channel
                    (prompt-cli/->PromptComm (atom ""))]]
      (doseq [ch channels]
        (let [stderr (java.io.StringWriter.)]
          (binding [*err* stderr]
            (with-out-str
              (should-not-throw (sut/on-turn-start ch "s" "hi"))
              (should-not-throw (sut/on-text-chunk ch "s" "chunk"))
              (should-not-throw (sut/on-tool-call ch "s" {:id "tc" :name "grep" :arguments {}}))
              (should-not-throw (sut/on-tool-cancel ch "s" {:id "tc" :name "grep" :arguments {}}))
              (should-not-throw (sut/on-tool-result ch "s" {:id "tc" :name "grep" :arguments {}} "ok"))
              (should-not-throw (sut/on-compaction-start ch "s" {:provider "grover" :model "m" :total-tokens 95 :context-window 100}))
              (should-not-throw (sut/on-compaction-success ch "s" {:summary "sum" :tokens-saved 10 :duration-ms 5}))
              (should-not-throw (sut/on-compaction-failure ch "s" {:error :llm-error :consecutive-failures 2}))
              (should-not-throw (sut/on-compaction-disabled ch "s" {:reason :too-many-failures}))
              (should-not-throw (sut/on-turn-end ch "s" {:content "done"})))))))))
