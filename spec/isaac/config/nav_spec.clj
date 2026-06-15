(ns isaac.config.nav-spec
  (:require
    [isaac.config.nav :as sut]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.module.loader :as module-loader]
    [speclj.core :refer :all]))

(defn- root []
  (schema-compose/effective-root-schema (module-loader/builtin-index)))

(describe "config nav"

  (marigold.agent/with-manifest)

  (describe "path->spec"

    (it "returns ok for an effort path (int type)"
      (let [result (sut/path->spec (root) "crew.joe.effort")]
        (should (:ok? result))
        (should= :int (:type (:spec result)))))

    (it "returns ok with member for a set-typed terminal"
      (let [result (sut/path->spec (root) "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= :wip (:member result))
        (should (:set-type? (:spec result)))))

    (it "returns ok with namespaced member for a set-typed terminal"
      (let [result (sut/path->spec (root) "crew.joe.tags.role/worker")]
        (should (:ok? result))
        (should= :role/worker (:member result))))

    (it "returns ok for a nested compaction path"
      (let [result (sut/path->spec (root) "crew.joe.compaction.threshold")]
        (should (:ok? result)))))

  (describe "set-value"

    (it "adds a member to a set-typed terminal"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/set-value (root) base "crew.joe.tags.wip" nil)]
        (should (:ok? result))
        (should= #{:role/worker :wip} (get-in (:config result) [:crew :joe :tags]))))

    (it "is idempotent when adding a set member already present"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/set-value (root) base "crew.joe.tags.role/worker" nil)]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))

    (it "initializes set when adding first member"
      (let [result (sut/set-value (root) {:crew {:joe {}}} "crew.joe.tags.wip" nil)]
        (should (:ok? result))
        (should= #{:wip} (get-in (:config result) [:crew :joe :tags])))))

  (describe "unset-value"

    (it "removes a member from a set-typed terminal"
      (let [base   {:crew {:joe {:tags #{:role/worker :wip}}}}
            result (sut/unset-value (root) base "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))

    (it "is idempotent when removing a set member not present"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/unset-value (root) base "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))))