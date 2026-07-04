(ns isaac.session.session-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.config.loader :as loader]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as sut]
    [speclj.core :refer [around describe it should should=]]))

(describe "session feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (grover/reset-queue!)
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it))
    (grover/reset-queue!)
    (g/reset!))

  (it "does not wait for a Grover gate after a turn already completed"
    (g/assoc! :turn-future (future {:output "done"
                                    :request {:id :request}
                                    :result  {:ok true}}))
    (let [started-at (System/nanoTime)]
      (sut/turn-ends-on-session "bridge")
      (should (< (/ (- (System/nanoTime) started-at) 1000000.0)
                 500.0)))
    (should= {:ok true} (g/get :llm-result)))

  (it "reuses loaded config until a feature fixture changes it"
    (let [loads* (atom 0)
          cfg    {:defaults {:crew "main"}
                  :crew     {"main" {}}
                  :models   {}
                  :providers {}}]
      (g/assoc! :root "/target/test-state")
      (g/assoc! :mem-fs (nexus/get :fs))
      (with-redefs [loader/load-config-result (fn [_]
                                                (swap! loads* inc)
                                                {:config cfg})]
        (should= cfg (#'sut/loaded-config))
        (should= cfg (#'sut/loaded-config))
        (should= 1 @loads*)
        (ffs/file-exists-with "config/crew/main.edn" "{:model :grover}")
        (should= cfg (#'sut/loaded-config))
        (should= 2 @loads*)))))
