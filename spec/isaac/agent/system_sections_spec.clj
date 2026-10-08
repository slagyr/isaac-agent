(ns isaac.agent.system-sections-spec
  (:require
    [isaac.agent.system-sections :as sut]
    [isaac.foundation.fs :as fs]
    [speclj.core :refer [describe it should should=]]))

(defn slow-section [_]
  @(promise)
  {:text "Too late"})

(defn steady-section [{:keys [crew]}]
  {:text (str "Hello " crew) :tools #{"beacon__ping"}})

(describe "system sections"
  (it "orders contributions by position, then section id"
    (let [index {:fixture {:manifest {:isaac.agent/system-sections
                                     {:zulu {:factory 'isaac.agent.system-sections-spec/steady-section :order 400}
                                      :alpha {:factory 'isaac.agent.system-sections-spec/steady-section :order 400}}}}}
          sections (sut/resolve-sections {:crew "Basil" :module-index index :fs (fs/mem-fs)})]
      (should= [:alpha :zulu] (mapv :id sections))
      (should= "Hello Basil" (:text (last sections)))
      (should= #{"beacon__ping"} (:tools (last sections)))))

  (it "skips a slow contributor without holding the turn"
    (let [index {:fixture {:manifest {:isaac.agent/system-sections
                                     {:sleepy {:factory 'isaac.agent.system-sections-spec/slow-section}}}}}
          start (System/currentTimeMillis)
          sections (sut/resolve-sections {:module-index index :fs (fs/mem-fs)})]
      (should (not-any? #(= :sleepy (:id %)) sections))
      (should (< (- (System/currentTimeMillis) start) 2500)))))
