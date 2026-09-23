(ns isaac.session.default-crew-steps-spec
  (:require
    [clojure.edn :as edn]
    [isaac.session.session-steps :as sut]
    [speclj.core :refer [describe it should=]]))

(describe "feature default crew fixtures"
  (it "stamps main when an isaac.edn fixture omits defaults.crew"
    (should= {:tools    {:web_search {:provider :brave}}
              :defaults {:frequencies {:crew "main"}}
              :crew     {"main" {}}}
             (edn/read-string (#'sut/stamp-fixture-default-crew
                                "isaac.edn"
                                "{:tools {:web_search {:provider :brave}}}"))))

  (it "uses the only configured crew as the fixture default without replacing its config"
    (should= {:crew     {:yopp {:soul "You are Yopp."}}
              :defaults {:frequencies {:crew :yopp}}}
             (edn/read-string (#'sut/stamp-fixture-default-crew
                                "isaac.edn"
                                "{:crew {:yopp {:soul \"You are Yopp.\"}}}"))))

  (it "does not alter the scenario that verifies defaults.crew is required"
    (let [content "{:defaults {:crew {:model :llama}} :crew {:yopp {}}}"]
      (should= content (#'sut/stamp-fixture-default-crew "isaac.edn" content))))
  )
