(ns isaac.episodes.segment-spec
  (:require
    [isaac.episodes.segment :as sut]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "isaac.episodes.segment"

  (context "parse-scenes (line format)"
    (it "reads boundary lines"
      (should= [{:start 1 :end 2 :gist "wine pairing for pheasant"}
                {:start 3 :end 4 :gist "regatta schedule"}]
               (sut/parse-scenes
                 (str "1-2: wine pairing for pheasant\n"
                      "3-4: regatta schedule\n"))))

    (it "accepts bare single ordinal as N-N"
      (should= [{:start 7 :end 7 :gist "solo note"}]
               (sut/parse-scenes "7: solo note")))

    (it "ignores preamble, fences, and blank lines"
      (should= [{:start 1 :end 2 :gist "wine pairing"}
                {:start 3 :end 4 :gist "regatta"}]
               (sut/parse-scenes
                 (str "Sure, here are the scenes:\n"
                      "```\n"
                      "1-2: wine pairing\n"
                      "\n"
                      "3-4: regatta\n"
                      "```\n"
                      "Hope that helps!\n"))))

    (it "returns empty when no boundary lines match"
      (should= [] (sut/parse-scenes "this is not a scene line at all")))

    (it "resolves an open-ended final boundary (end/present/last) to n"
      (should= [{:start 1 :end 2 :gist "wine"}
                {:start 3 :end 5 :gist "wrap up"}]
               (sut/parse-scenes "1-2: wine\n3-present: wrap up" 5))
      (should= [{:start 1 :end 4 :gist "all of it"}]
               (sut/parse-scenes "1-end: all of it" 4))
      (should= [{:start 2 :end 6 :gist "tail"}]
               (sut/parse-scenes "2-last: tail" 6)))

    (it "drops open-ended boundaries when n is unknown"
      (should= [] (sut/parse-scenes "3-present: wrap up")))
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

  (context "segment-span!"
    (before
      (grover/install-test-fixture!)
      (grover/reset-queue!))

    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (example)))

    (it "returns ok for valid line-format output"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "text" :content "1-2: topic"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should (:ok result))
        (should= "a" (:start-id (first (:ok result))))))

    (it "surfaces provider errors without retry or flag"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "error" :content "auth-missing"}
                                {:type "text" :content "1-2: should not be consumed"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should= :provider-error (:error result))
        (should= "grover" (:provider result))
        (should= :llm-error (:error-key result))
        ;; second queued response must remain (no retry)
        (should= 1 (count @@#'grover/queue))))

    (it "retries once then flags with raw text"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "text" :content "garbage"}
                                {:type "text" :content "still garbage"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should= :flagged (:error result))
        (should= "still garbage" (:raw result))))
    )
  )
