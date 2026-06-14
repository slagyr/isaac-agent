(ns isaac.crew.store-spec
  (:require
    [isaac.crew.store :as sut]
    [speclj.core :refer :all]))

(describe "crew store"

  (it "returns tags for a crew"
    (should= #{:role/worker} (sut/tags-of {:tags #{:role/worker}})))

  (it "returns true when a crew has a tag"
    (should (sut/has-tag? {:tags #{:role/worker}} :role/worker)))

  (it "filters crews by required tags"
    (should= {"joe" {:tags #{:role/worker :project/chess}}}
             (sut/by-tags {"joe" {:tags #{:role/worker :project/chess}}
                           "sue" {:tags #{:role/worker}}}
                          #{:role/worker :project/chess}))))
