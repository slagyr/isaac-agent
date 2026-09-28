(ns isaac.turn.store-spec
  (:require
    [isaac.fs :as fs]
    [isaac.turn.store :as sut]
    [speclj.core :refer :all]))

(describe "TurnStore"
  (it "accepts a key only once in memory"
    (let [store (sut/memory-store)]
      (should= "one" (:id (sut/submit! store {:id "one" :key "tide" :state :queued})))
      (should= "one" (:id (sut/submit! store {:id "two" :key "tide" :state :queued})))
      (should= 1 (count (sut/list-turns store)))))

  (it "allows exactly one concurrent claim on a queued file record"
    (let [store (sut/file-store (fs/mem-fs) "/test/isaac")]
      (sut/submit! store {:id "one" :state :queued})
      (should= 1 (count (filter identity (doall (map deref (repeatedly 20 #(future (sut/claim! store "one")))))))))))
