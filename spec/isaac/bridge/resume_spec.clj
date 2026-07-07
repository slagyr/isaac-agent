(ns isaac.bridge.resume-spec
  (:require
    [isaac.bridge.resume :as sut]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [isaac.spec-helper :as foundation-helper]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def test-root "/test/isaac")

(describe "bridge resume"
  (foundation-helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [it]
    (nexus/-with-nested-nexus {:root test-root :fs (fs/mem-fs)}
      (helper/with-memory-store
        (config/dangerously-install-config! {} "spec")
        (it))))

  (it "logs scan-complete when no turn markers exist"
    (should= nil
             (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                             :root          test-root
                                             :cfg           {}
                                             :now           (Instant/parse "2026-07-07T16:37:28Z")}))
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= :info (:level entry))
      (should= 0 (:markers entry))
      (should= 0 (:requeued entry))
      (should= 0 (:dropped entry))))

  (it "logs scan-complete with requeued hail count for a suspended hail marker"
    (helper/create-session! test-root "isaac-verify")
    (store/record-turn-marker! (store/registered-store) "isaac-verify"
                               {:source       :hail
                                :session-id   "isaac-verify"
                                :suspended    true
                                :boundary     :clean
                                :started-at   "2026-07-07T16:37:09Z"
                                :delivery-id  "c27493a3"
                                :delivery     {:id "c27493a3"
                                               :prompt "verify the bean"
                                               :crew "verify"
                                               :attempts 2}})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-07-07T16:37:28Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= 1 (:markers entry))
      (should= 1 (:requeued entry))
      (should= 0 (:dropped entry)))
    (let [delivery-path (str test-root "/hail/deliveries/c27493a3.edn")]
      (should (fs/exists? (nexus/get :fs) delivery-path))
      (should= nil (store/get-turn-marker (store/registered-store) "isaac-verify"))))

  (it "logs scan-complete with dropped comm count for a stale comm marker"
    (helper/create-session! test-root "firewatch")
    (store/record-turn-marker! (store/registered-store) "firewatch"
                               {:source         :comm
                                :session-id     "firewatch"
                                :suspended      true
                                :boundary       :clean
                                :interrupted-at "2026-07-07T15:20:00Z"
                                :started-at     "2026-07-07T15:19:30Z"})
    (sut/resume-interrupted-turns! {:session-store      (store/registered-store)
                                    :root               test-root
                                    :cfg                {}
                                    :resume-window-ms   600000
                                    :now                (Instant/parse "2026-07-07T16:37:28Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= 1 (:markers entry))
      (should= 0 (:requeued entry))
      (should= 1 (:dropped entry)))
    (should= nil (store/get-turn-marker (store/registered-store) "firewatch")))
  )
