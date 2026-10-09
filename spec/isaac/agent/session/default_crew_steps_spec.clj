(ns isaac.agent.session.default-crew-steps-spec
  (:require
    [clojure.edn :as edn]
    [isaac.agent.session.session-steps :as sut]
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
    (should= {:crew     {:bartholomew {:soul "You are Bartholomew."}}
              :defaults {:frequencies {:crew :bartholomew}}}
             (edn/read-string (#'sut/stamp-fixture-default-crew
                                "isaac.edn"
                                "{:crew {:bartholomew {:soul \"You are Bartholomew.\"}}}"))))

  (it "does not alter the scenario that verifies defaults.crew is required"
    (let [content "{:defaults {:crew {:model :llama}} :crew {:bartholomew {}}}"]
      (should= content (#'sut/stamp-fixture-default-crew "isaac.edn" content))))
  )
