(ns isaac.bridge.resume-spec
  (:require
    [isaac.bridge.resume :as sut]
    [isaac.config.api :as config]
    [isaac.drive.turn :as drive-turn]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [isaac.spec-helper :as foundation-helper]
    [isaac.turn.queue :as queue]
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

  (it "enqueues an interrupted comm turn on the turn queue instead of driving it on the scan thread"
    (helper/create-session! test-root "logbook")
    (store/record-turn-marker! (store/registered-store) "logbook"
                               {:source     :comm
                                :session-id "logbook"
                                :started-at "2026-04-21T09:59:30Z"})
    (let [driven (atom [])]
      (with-redefs [drive-turn/run-turn! #(swap! driven conj %)]
        (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                        :root          test-root
                                        :cfg           {}
                                        :now           (Instant/parse "2026-04-21T10:00:00Z")}))
      (should= [] @driven))
    (let [record (first (binding [queue/*root* test-root] (queue/list-held)))]
      (should-not-be-nil record)
      (should= "logbook" (:session record))
      (should-contain "interrupted" (:input record))
      (should= :resume (get-in record [:origin :kind]))
      (should= :comm (get-in record [:origin :source])))
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should= 1 (:requeued entry))
      (should= 0 (:dropped entry)))
    (should= nil (store/get-turn-marker (store/registered-store) "logbook")))

  (it "enqueues a weather-suspended turn whose retry-at has passed and clears its marker"
    (helper/create-session! test-root "trash-can")
    (store/record-turn-marker! (store/registered-store) "trash-can"
                               {:source       :comm
                                :session-id   "trash-can"
                                :suspended    true
                                :reason       :wall
                                :suspended-at "2026-04-21T09:50:00Z"
                                :retry-at     "2026-04-21T09:59:00Z"
                                :started-at   "2026-04-21T09:59:30Z"})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-04-21T10:00:00Z")})
    (let [record (first (binding [queue/*root* test-root] (queue/list-held)))]
      (should-not-be-nil record)
      (should= "trash-can" (:session record)))
    (should= nil (store/get-turn-marker (store/registered-store) "trash-can")))

  (it "drops a marker whose turn cannot be enqueued, with a warning"
    (helper/create-session! test-root "logbook")
    (store/record-turn-marker! (store/registered-store) "logbook"
                               {:source     :comm
                                :session-id "logbook"
                                :started-at "2026-04-21T09:59:30Z"})
    (with-redefs [queue/enqueue! (fn [_] (throw (ex-info "store unavailable" {})))]
      (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                      :root          test-root
                                      :cfg           {}
                                      :now           (Instant/parse "2026-04-21T10:00:00Z")}))
    (let [entry (first (filter #(= :resume/enqueue-failed (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= :warn (:level entry))
      (should= "logbook" (:session entry)))
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should= 0 (:requeued entry))
      (should= 1 (:dropped entry)))
    (should= nil (store/get-turn-marker (store/registered-store) "logbook")))

  (it "enqueues a suspended hail marker like any other source, writing nothing under hail/"
    (helper/create-session! test-root "isaac-verify")
    (store/record-turn-marker! (store/registered-store) "isaac-verify"
                               {:source     :hail
                                :session-id "isaac-verify"
                                :suspended  true
                                :boundary   :clean
                                :started-at "2026-07-07T16:37:09Z"})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-07-07T16:37:28Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= 1 (:markers entry))
      (should= 1 (:requeued entry))
      (should= 0 (:dropped entry)))
    (let [record (first (binding [queue/*root* test-root] (queue/list-held)))]
      (should-not-be-nil record)
      (should= "isaac-verify" (:session record))
      (should= :resume (get-in record [:origin :kind]))
      (should= :hail (get-in record [:origin :source])))
    (should-not (fs/exists? (nexus/get :fs) (str test-root "/hail")))
    (should= nil (store/get-turn-marker (store/registered-store) "isaac-verify")))

  (it "resumes an hour-old hail marker: a work order never goes stale"
    (helper/create-session! test-root "engine-room")
    (store/record-turn-marker! (store/registered-store) "engine-room"
                               {:source     :hail
                                :session-id "engine-room"
                                :started-at "2026-04-21T09:00:00Z"})
    (sut/resume-interrupted-turns! {:session-store    (store/registered-store)
                                    :root             test-root
                                    :cfg              {}
                                    :resume-window-ms 600000
                                    :now              (Instant/parse "2026-04-21T10:00:00Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should= 1 (:requeued entry))
      (should= 0 (:dropped entry))))

  (it "clears the legacy marker path after enqueueing its turn"
    (let [fs*         (nexus/get :fs)
          session-id  "isaac-verify"
          marker-path (str test-root "/sessions/turns/" session-id ".edn")
          marker      {:source     :hail
                       :session-id session-id
                       :started-at "2026-07-07T16:37:09Z"}]
      (fs/mkdirs fs* (fs/parent marker-path))
      (fs/spit fs* marker-path (pr-str marker))
      ;; The memory store represents the startup scan result while the legacy
      ;; disk file preserves the exact path that resume must remove.
      (store/record-turn-marker! (store/registered-store) session-id marker)
      (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                      :root          test-root
                                      :cfg           {}
                                      :now           (Instant/parse "2026-07-07T16:37:28Z")})
      (should-not (fs/exists? fs* marker-path))
      (should-not-be-nil (first (binding [queue/*root* test-root] (queue/list-held))))))

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

  (it "drops a cancelled hail marker the same as any other source, archiving nothing"
    (helper/create-session! test-root "engine-room")
    (store/record-turn-marker! (store/registered-store) "engine-room"
                               {:source     :hail
                                :session-id "engine-room"
                                :started-at "2026-04-21T09:59:30Z"
                                :cancelled  true})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-04-21T10:00:00Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= 1 (:markers entry))
      (should= 0 (:requeued entry))
      (should= 1 (:dropped entry)))
    (should-not (fs/exists? (nexus/get :fs) (str test-root "/hail")))
    (should= [] (vec (binding [queue/*root* test-root] (queue/list-held))))
    (should= nil (store/get-turn-marker (store/registered-store) "engine-room")))

  (it "drops a cancelled comm marker without dispatching an interruption note"
    (helper/create-session! test-root "firewatch")
    (store/record-turn-marker! (store/registered-store) "firewatch"
                               {:source     :comm
                                :session-id "firewatch"
                                :started-at "2026-04-21T09:59:30Z"
                                :cancelled  true})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-04-21T10:00:00Z")})
    (let [entry (first (filter #(= :resume/scan-complete (:event %)) @log/captured-logs))]
      (should= 1 (:dropped entry))
      (should= 0 (:requeued entry)))
    (should= nil (store/get-turn-marker (store/registered-store) "firewatch")))

  (it "defers a weather-suspended marker whose retry-at is still in the future"
    (helper/create-session! test-root "trash-can")
    (store/record-turn-marker! (store/registered-store) "trash-can"
                               {:source      :hail
                                :session-id  "trash-can"
                                :suspended   true
                                :reason      :wall
                                :retry-at    "2026-04-21T10:30:00Z"
                                :started-at  "2026-04-21T10:00:00Z"
                                :delivery-id "weather-hail"})
    (sut/resume-interrupted-turns! {:session-store (store/registered-store)
                                    :root          test-root
                                    :cfg           {}
                                    :now           (Instant/parse "2026-04-21T10:00:00Z")})
    (let [entry (first (filter #(= :resume/weather-deferred (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= "trash-can" (:session entry))
      (should= "2026-04-21T10:30:00Z" (:retry-at entry)))
    (should-not-be-nil (store/get-turn-marker (store/registered-store) "trash-can"))
    (should-not (fs/exists? (nexus/get :fs) (str test-root "/hail/deliveries/weather-hail.edn"))))
  )
