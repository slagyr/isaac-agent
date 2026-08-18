(ns isaac.agent.module-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.agent.module-steps :as sut]
    [speclj.core :refer [around describe it should=]]))

(describe "module feature steps"

  (around [it]
    (g/reset!)
    (it)
    (g/reset!))

  (it "waits for a pending slash-command turn before asserting reply text"
    (let [ready (promise)]
      (g/assoc! :turn-future
                (future
                  @ready
                  {:output  ""
                   :request nil
                   :result  {:type    :command
                             :command :unknown
                             :message "unknown model: nonexistent"}}))
      (future (deliver ready true))
      (sut/reply-contains "unknown model: nonexistent")
      (should= "unknown model: nonexistent" (:message (g/get :llm-result)))))

  (it "reads an already-recorded reply when no turn is pending"
    (g/assoc! :llm-result {:type    :command
                           :command :unknown
                           :message "unknown model: nonexistent"})
    (sut/reply-contains "unknown model: nonexistent")
    (should= "unknown model: nonexistent" (:message (g/get :llm-result)))))
