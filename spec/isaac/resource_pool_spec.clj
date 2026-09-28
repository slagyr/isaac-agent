(ns isaac.resource-pool-spec
  (:require
    [isaac.logger :as log]
    [isaac.resource-pool :as sut]
    [speclj.core :refer [after before describe it should should-not should-be-nil should-contain should=]]))

(defn- recording-pool [events decision]
  (reify sut/ResourcePool
    (try-acquire [_ ctx]
      (swap! events conj [:acquire ctx])
      decision)
    (release! [_ lease]
      (swap! events conj [:release lease]))))

(describe "named resource pools"
  (before (sut/clear!) (sut/set-wake-hook! nil))
  (after (sut/clear!) (sut/set-wake-hook! nil))

  (it "registering a type alone does not create an instance"
    (sut/register! :scripted (fn [_] :pool))
    (should-be-nil (sut/resolve {} :dock)))

  (it "resolves an instance by its configured name and passes the config to the factory"
    (let [seen (atom nil)]
      (sut/register! :scripted (fn [config] (reset! seen config) :pool))
      (should= :pool (sut/resolve {:resource-pools {"dock" {:type :scripted :limit 1}}} :dock))
      (should= {:type :scripted :limit 1} @seen)))

  (it "rejects an unconfigured name before acquisition"
    (should= :unknown-resource-pool
             (:error (sut/resolve-submitted {} [:drydock]))))

  (it "resolves submitted instances in request order"
    (sut/register! :scripted (fn [{:keys [name]}] name))
    (should= [:crane :dock]
             (mapv :name (:resource-pools (sut/resolve-submitted
                                            {:resource-pools {"crane" {:type :scripted :name :crane}
                                                              "dock" {:type :scripted :name :dock}}}
                                            [:crane :dock])))))

  (it "tide is busy outside its window and returns a lease inside"
    (let [gate (sut/tide {:window "22:00-06:00"})]
      (should= :busy (sut/try-acquire gate {:now (java.time.Instant/parse "2026-03-01T14:00:00Z")}))
      (should (some? (sut/try-acquire gate {:now (java.time.Instant/parse "2026-03-01T23:30:00Z")})))))

  (it "rejects malformed windows"
    (should-not (sut/valid-window? "nonsense"))
    (should (sut/valid-window? "22:00-06:00")))

  (it "a busy second pool gives back the first lease"
    (let [events (atom [])
          lease  (sut/->ReleaseToken "dock")
          result (sut/acquire-all! [{:name :dock :pool (recording-pool events lease)}
                                    {:name :crane :pool (recording-pool events :busy)}] {})]
      (should= :hold (:reason result))
      (should-contain "crane" (:message result))
      (should= [:acquire :acquire :release] (mapv first @events))))

  (it "releases successful leases in reverse order and nudges the queue"
    (let [events (atom [])
          wakes  (atom 0)
          pools  (mapv (fn [name] {:name name :pool (recording-pool events (sut/->ReleaseToken (clojure.core/name name)))})
                       [:dock :crane])]
      (sut/set-wake-hook! #(swap! wakes inc))
      (sut/release-all! (:leases (sut/acquire-all! pools {})))
      (should= ["crane" "dock"] (mapv (comp :id second) (filter #(= :release (first %)) @events)))
      (should= 1 @wakes)))

  (it "a throwing release cannot prevent another lease being released"
    (let [released? (atom false)
          boom (reify sut/ResourcePool
                 (try-acquire [_ _] (sut/->ReleaseToken "boom"))
                 (release! [_ _] (throw (Exception. "berth exploded"))))
          ok (reify sut/ResourcePool
               (try-acquire [_ _] (sut/->ReleaseToken "ok"))
               (release! [_ _] (reset! released? true)))]
      (log/capture-logs
        (sut/release-all! (:leases (sut/acquire-all! [{:name :ok :pool ok} {:name :boom :pool boom}] {})))
        (should (some #(= :pool/release-failed (:event %)) @log/captured-logs)))
      (should @released?)))
  )
