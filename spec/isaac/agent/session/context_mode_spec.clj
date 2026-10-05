(ns isaac.agent.session.context-mode-spec
  (:require
    [isaac.agent.session.context-mode :as sut]
    [speclj.core :refer :all]))

(describe "context-mode register"

  (it "keeps wrap-input when a later registration only supplies a factory"
    (sut/register! :ka10-wrap {:factory    sut/identity-transcript
                              :wrap-input (fn [i] (str "W:" i))})
    (sut/register! :ka10-wrap {:factory sut/identity-transcript})
    (should= "W:hi" (sut/wrap-input :ka10-wrap "hi")))
  )
