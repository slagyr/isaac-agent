(ns isaac.agent.quota-spec
  (:require
    [isaac.agent.quota :as sut]
    [speclj.core :refer [describe it should=]]))

(describe "provider subscription quota"
  (it "turns ChatGPT usage windows into a uniform report"
    (let [report (sut/chatgpt-report {:plan_type "plus"
                                      :rate_limit {:allowed true :limit_reached false
                                                   :primary_window {:used_percent 42 :limit_window_seconds 18000
                                                                    :reset_at 1793192400}
                                                   :secondary_window nil}
                                      :credits {:balance "12"}})]
      (should= "plus" (:plan report))
      (should= [{:id :primary :label "5-hour" :used-percent 42.0
                 :resets-at (java.util.Date/from (java.time.Instant/ofEpochSecond 1793192400))
                 :length-seconds 18000 :limited? false}] (:windows report))
      (should= [{:id :credits :amount 12.0 :unit :credits}] (:balances report))))

  (it "turns Grok products without a percent into zero-used windows"
    (let [report (sut/grok-report {:config {:currentPeriod {:start "2026-10-23T14:00:00+00:00"
                                                           :end "2026-10-30T14:00:00+00:00"}
                                             :productUsage [{:product "GrokChat"}]}})]
      (should= 0.0 (get-in report [:windows 0 :used-percent]))
      (should= 0.0 (get-in report [:windows 1 :used-percent]))
      (should= {:product "GrokChat"} (get-in report [:windows 1 :scope]))))

  (it "uses a clock for a two-minute per-provider cache"
    (let [calls (atom 0)
          meter (fn [_] (swap! calls inc) {:windows []})]
      (binding [sut/*now* (java.time.Instant/parse "2026-10-28T12:00:00Z")]
        (sut/clear-cache!)
        (sut/report "ketch" {} meter)
        (sut/report "ketch" {} meter)
        (should= 1 @calls))
      (binding [sut/*now* (java.time.Instant/parse "2026-10-28T12:03:00Z")]
        (sut/report "ketch" {} meter)
        (should= 2 @calls))))

  (it "returns unknown when the meter throws"
    (sut/clear-cache!)
    (should= :unknown (:status (sut/report "ketch" {} (fn [_] (throw (Exception. "HTTP 500"))))))))
