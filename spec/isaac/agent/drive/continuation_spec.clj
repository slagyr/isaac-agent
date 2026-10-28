(ns isaac.agent.drive.continuation-spec
  (:require
    [isaac.agent.drive.continuation :as sut]
    [speclj.core :refer [describe it should= should-be-nil]]))

(describe "continuation plan"
  (it "continues only a wrapped-up turn, and only while the budget remains"
    (let [wrapped {:ended-by :cycle-limit :exhaustion :wrapped-up}]
      (should-be-nil (#'sut/continuation-plan {:ended-by :reply} 0 2))
      (should-be-nil (#'sut/continuation-plan {:ended-by :cycle-limit :exhaustion :stopped} 0 2))
      (should= {:action :continue :continuation 1 :budget 2}
               (#'sut/continuation-plan wrapped 0 2))
      (should= {:action :exhausted :continuation 2 :budget 2}
               (#'sut/continuation-plan wrapped 2 2))))

  (it "reads the continuation count off the charge origin"
    (should= 0 (#'sut/continuation-count {}))
    (should= 2 (#'sut/continuation-count {:origin {:kind :memory :continuation 2}}))))
