(ns isaac.agent.identifiers-spec
  (:require
    [isaac.agent.identifiers :as sut]
    [isaac.agent.spec-helper :as helper]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]
    [speclj.core :refer [describe it should should=]]))

(defn know-cordelia [{:keys [id]}]
  (when (= "cordelia-7" id)
    {:kind :contact :id "cordelia" :authenticated true}))

(defn fail [_]
  (throw (ex-info "fog" {})))

(defn attempted [_]
  (throw (ex-info "non-handle passed to identifier" {})))

(def index
  {:isaac.roster.almanac {:manifest {:isaac.agent/identifiers
                                     {:almanac {:factory 'isaac.agent.identifiers-spec/know-cordelia}}}}})

(describe "party identifiers"
  (helper/with-captured-logs)

  (it "names a known party but cannot certify its authentication"
    (with-redefs [module-loader/activate! (fn [& _] nil)]
      (should= {:kind :contact :id "cordelia" :authenticated false}
               (sut/identify index {:kind :handle :comm :logbook :id "cordelia-7" :authenticated false}))))

  (it "does not add authentication to a handle without a claim"
    (with-redefs [module-loader/activate! (fn [& _] nil)]
      (should= {:kind :contact :id "cordelia"}
               (sut/identify index {:kind :handle :comm :logbook :id "cordelia-7"}))))

  (it "keeps unknown handles intact"
    (with-redefs [module-loader/activate! (fn [& _] nil)]
      (let [handle {:kind :handle :comm :logbook :id "stranger-1"}]
        (should= handle (sut/identify index handle)))))

  (it "never hands crew senders to an identifier"
    (let [crew {:kind :crew :id "marvin"}
          index {:isaac.roster.fog {:manifest {:isaac.agent/identifiers
                                               {:fog {:factory 'isaac.agent.identifiers-spec/attempted}}}}}]
      (should= crew (sut/identify index crew))))

  (it "warns and continues to a healthy identifier after one throws"
    (with-redefs [module-loader/activate! (fn [& _] nil)]
      (let [index (array-map :isaac.roster.fog
                             {:manifest {:isaac.agent/identifiers
                                         {:fog {:factory 'isaac.agent.identifiers-spec/fail}}}}
                             :isaac.roster.almanac (get index :isaac.roster.almanac))]
        (should= {:kind :contact :id "cordelia"}
                 (sut/identify index {:kind :handle :comm :logbook :id "cordelia-7"}))
        (should (some #(and (= :warn (:level %)) (= :identifier/failed (:event %))
                            (= "isaac.roster.fog" (:module %))) @log/captured-logs)))))
  )
