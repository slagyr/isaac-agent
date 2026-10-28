(ns isaac.agent.quota-cli-spec
  (:require
    [isaac.agent.quota :as quota]
    [isaac.agent.quota-cli :as sut]
    [speclj.core :refer [describe it should should=]]))

(describe "quota command"
  (it "prints a whole percent and a UTC reset for each window"
    (let [output (with-out-str
                   (with-redefs [quota/reports (fn [_] [{:provider "chatgpt" :status :ok
                                                        :windows [{:label "5-hour" :used-percent 42.3
                                                                   :resets-at (java.util.Date/from
                                                                                (java.time.Instant/parse "2026-10-28T13:00:00Z"))}]}])]
                     (should= 0 (sut/run-command {:_raw-args ["--provider" "chatgpt"]}))))]
      (should (re-find #"chatgpt\s+5-hour\s+42%\s+2026-10-28 13:00 UTC" output)))))
