(ns isaac.episodes.segment-spec
  (:require
    [isaac.episodes.segment :as sut]
    [speclj.core :refer :all]))

(describe "isaac.episodes.segment"

  (context "parse-scenes"
    (it "reads an EDN vector of ordinal scenes"
      (should= [{:start 1 :end 2 :gist "a"} {:start 3 :end 4 :gist "b"}]
               (sut/parse-scenes "({:start 1 :end 2 :gist \"a\"} {:start 3 :end 4 :gist \"b\"})")))

    (it "accepts a bare vector"
      (should= [{:start 1 :end 2 :gist "x"}]
               (sut/parse-scenes "[{:start 1 :end 2 :gist \"x\"}]")))

    (it "returns nil on garbage"
      (should-be-nil (sut/parse-scenes "this is not edn at all")))
    )

  (context "validate-tiling"
    (it "accepts exact 1..N cover"
      (should (sut/valid-tiling? 4 [{:start 1 :end 2} {:start 3 :end 4}])))

    (it "rejects gaps"
      (should-not (sut/valid-tiling? 4 [{:start 1 :end 1} {:start 3 :end 4}])))

    (it "rejects overlaps"
      (should-not (sut/valid-tiling? 3 [{:start 1 :end 2} {:start 2 :end 3}])))

    (it "rejects out of range"
      (should-not (sut/valid-tiling? 2 [{:start 1 :end 3}])))
    )

  (context "resolve-ordinals"
    (it "maps start/end ordinals onto message ids"
      (let [msgs [{:id "a"} {:id "b"} {:id "c"} {:id "d"}]
            scenes [{:start 1 :end 2 :gist "Wine"} {:start 3 :end 4 :gist "Regatta"}]]
        (should= [{:start-id "a" :end-id "b" :gist "Wine" :start-ord 1 :end-ord 2}
                  {:start-id "c" :end-id "d" :gist "Regatta" :start-ord 3 :end-ord 4}]
                 (sut/resolve-ordinals msgs scenes))))
    )

  (context "compaction-spans"
    (it "splits on compaction boundaries and carries summary to the next span"
      (let [tx [{:type "session"}
                {:type "message" :id "1" :timestamp "t1" :message {:role "user" :content "a"}}
                {:type "message" :id "2" :timestamp "t2" :message {:role "assistant" :content "b"}}
                {:type "compaction" :summary "They planned the voyage provisions."}
                {:type "message" :id "3" :timestamp "t3" :message {:role "user" :content "c"}}
                {:type "message" :id "4" :timestamp "t4" :message {:role "assistant" :content "d"}}]
            spans (sut/compaction-spans tx)]
        (should= 2 (count spans))
        (should= ["1" "2"] (mapv :id (:messages (first spans))))
        (should-be-nil (:preceding-summary (first spans)))
        (should= ["3" "4"] (mapv :id (:messages (second spans))))
        (should= "They planned the voyage provisions."
                 (:preceding-summary (second spans)))))

    (it "yields one span when there is no compaction"
      (let [tx [{:type "message" :id "1" :message {:role "user" :content "a"}}
                {:type "message" :id "2" :message {:role "assistant" :content "b"}}]
            spans (sut/compaction-spans tx)]
        (should= 1 (count spans))
        (should= 2 (count (:messages (first spans))))))
    )
  )
