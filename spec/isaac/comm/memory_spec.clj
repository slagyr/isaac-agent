(ns isaac.comm.memory-spec
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.memory :as sut]
    [speclj.core :refer :all]))

(describe "Memory channel"

  (it "records text events in order"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-turn-start ch "agent:main:cli:direct:user1" "What is 2+2?")
      (comm/on-text-chunk ch "agent:main:cli:direct:user1" "Four, I think")
      (comm/on-turn-end ch "agent:main:cli:direct:user1" {:content "Four, I think"})
      (should= ["turn-start" "text-chunk" "turn-end"] (mapv :event @events))))

  (it "preserves whitespace-bearing text chunks exactly"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-text-chunk ch "agent:main:cli:direct:user1" "Once ")
      (comm/on-text-chunk ch "agent:main:cli:direct:user1" " ")
      (comm/on-text-chunk ch "agent:main:cli:direct:user1" " upon")
      (should= ["Once " " " " upon"] (mapv :text @events))))

  (it "records compaction lifecycle events separately from text events"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-compaction-start ch "agent:main:cli:direct:user1" {:provider "grover" :model "echo" :total-tokens 95 :context-window 100})
      (comm/on-compaction-failure ch "agent:main:cli:direct:user1" {:error :llm-error :consecutive-failures 2})
      (comm/on-compaction-disabled ch "agent:main:cli:direct:user1" {:reason :too-many-failures})
      (should= [{:context-window 100 :event "compaction-start" :model "echo" :provider "grover" :session "agent:main:cli:direct:user1" :total-tokens 95}
                {:consecutive-failures 2 :error :llm-error :event "compaction-failure" :session "agent:main:cli:direct:user1"}
                {:event "compaction-disabled" :reason :too-many-failures :session "agent:main:cli:direct:user1"}]
               @events)))

  (it "records tool lifecycle events"
    (let [events    (atom [])
          tool-call {:id "tc-1" :name "exec" :arguments {:command "echo hi"}}
          ch        (sut/channel events)]
      (comm/on-tool-call ch "agent:main:cli:direct:user1" tool-call)
      (comm/on-tool-result ch "agent:main:cli:direct:user1" tool-call "hi")
      (should= "tool-call" (:event (first @events)))
      (should= "exec" (get-in (first @events) [:tool :name]))
      (should= "tool-result" (:event (second @events)))
      (should= "exec" (get-in (second @events) [:tool :name])))))
