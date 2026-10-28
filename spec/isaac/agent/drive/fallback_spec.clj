(ns isaac.agent.drive.fallback-spec
  (:require
    [isaac.agent.drive.fallback :as sut]
    [speclj.core :refer [describe it should= should-be-nil]]))

(describe "fallback search"
  (it "returns the first link whose engage function yields a request, and the links still waiting"
    (should= [:b :req '(:c)]
             (sut/search [:a :b :c] (fn [link] (when (= link :b) :req)))))

  (it "returns nil when no link engages"
    (should-be-nil (sut/search [:a :b] (constantly nil))))

  (it "returns nil for an empty chain"
    (should-be-nil (sut/search [] (constantly :req)))))
