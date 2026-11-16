(ns isaac.agent.tool.timer-spec
  (:require
    [cheshire.core :as json]
    [isaac.agent.tool.timer :as sut]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.turn.queue :as queue]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(describe "session timers"
  (around [example]
    (nexus/-with-nexus {:root "/test/timer" :fs (fs/mem-fs)}
      (binding [memory/*now* (Instant/parse "2026-03-01T18:00:00Z")]
        (example))))

  (it "persists a due turn for the original session"
    (with-redefs [loader/snapshot (fn [_] {})]
      (let [result (json/parse-string (:result (sut/set-tool {"session_key" "bridge" "state_dir" "/test/timer"
                                                               "after" 120 "note" "Check on the scouts."})) true)
            turn (queue/read-held (:handle result))]
        (should= "2026-03-01T18:02:00Z" (:due result))
        (should= "bridge" (:session turn))
        (should= {:kind :timer} (:origin turn))
        (should= "Check on the scouts." (:input turn)))))

  (it "lists and cancels only pending timers owned by the session"
    (with-redefs [loader/snapshot (fn [_] {})]
      (let [handle (:handle (json/parse-string (:result (sut/set-tool {"session_key" "bridge" "state_dir" "/test/timer"
                                                                        "after" 120 "note" "Check."})) true))]
        (should= 1 (count (:timers (json/parse-string (:result (sut/list-tool {"session_key" "bridge" "state_dir" "/test/timer"})) true))))
        (should (:isError (sut/cancel-tool {"session_key" "other" "state_dir" "/test/timer" "handle" handle})))
        (sut/cancel-tool {"session_key" "bridge" "state_dir" "/test/timer" "handle" handle})
        (should= [] (:timers (json/parse-string (:result (sut/list-tool {"session_key" "bridge" "state_dir" "/test/timer"})) true))))))

  (it "refuses a second timer when the crew's pending limit is one"
    (with-redefs [loader/snapshot (fn [_] {:defaults {:crew {:max-timers 1}}})]
      (sut/set-tool {"session_key" "bridge" "state_dir" "/test/timer" "after" 120 "note" "First"})
      (should-contain "timer limit reached (1 pending)"
                      (:error (sut/set-tool {"session_key" "bridge" "state_dir" "/test/timer" "after" 300 "note" "Second"}))))))
