(ns isaac.attention-spec
  (:require
    [clojure.string :as str]
    [isaac.attention :as sut]
    [isaac.comm.delivery.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "attention"
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "enqueues compaction-disabled attention when notify is configured"
    (sut/clear-throttle!)
    (sut/maybe-notify-compaction-disabled!
     {:attention {:notify {:comm :discord :target "boiler-room"}}}
     "sess-1"
     {:reason :too-many-failures :total-tokens 99 :context-window 100})
    (let [pending (queue/list-pending)]
      (should= 1 (count pending))
      (should= :discord (:comm (first pending)))
      (should= "boiler-room" (:target (first pending)))
      (should (str/includes? (:content (first pending)) "Compaction disabled")))))
