(ns isaac.comm.memory-spec
  (:require
    [isaac.comm.memory :as sut]
    [isaac.comm.protocol :as comm]
    [speclj.core :refer :all]))

(describe "Memory channel"

  (it "records text events in order"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-turn-start ch "agent:main:cli:direct:user1" "What is 2+2?")
      (comm/on-chatter ch "agent:main:cli:direct:user1" {:n 1} "Four, I think")
      (comm/on-reply ch "agent:main:cli:direct:user1" "Four, I think")
      (comm/on-turn-end ch "agent:main:cli:direct:user1" {:content "Four, I think"})
      (should= ["turn-start" "chatter" "reply" "turn-end"] (mapv :event @events))))

  (it "preserves whitespace-bearing text chunks exactly"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-chatter ch "agent:main:cli:direct:user1" {:n 1} "Once ")
      (comm/on-chatter ch "agent:main:cli:direct:user1" {:n 1} " ")
      (comm/on-chatter ch "agent:main:cli:direct:user1" {:n 1} " upon")
      (should= ["Once " " " " upon"] (mapv :text @events))))

  (it "records compaction lifecycle events as bulletins"
    (let [events (atom [])
          ch     (sut/channel events)]
      (comm/on-bulletin ch "agent:main:cli:direct:user1" {:kind :compaction/start :provider "grover" :model "echo" :total-tokens 95 :context-window 100})
      (comm/on-bulletin ch "agent:main:cli:direct:user1" {:kind :compaction/failure :error :llm-error :consecutive-failures 2})
      (comm/on-bulletin ch "agent:main:cli:direct:user1" {:kind :compaction/disabled :reason :too-many-failures})
      (should= [{:context-window 100 :event "bulletin" :kind "compaction/start" :model "echo" :provider "grover" :session "agent:main:cli:direct:user1" :total-tokens 95}
                {:consecutive-failures 2 :error :llm-error :event "bulletin" :kind "compaction/failure" :session "agent:main:cli:direct:user1"}
                {:event "bulletin" :kind "compaction/disabled" :reason :too-many-failures :session "agent:main:cli:direct:user1"}]
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
      (should= "exec" (get-in (second @events) [:tool :name]))))
  )
