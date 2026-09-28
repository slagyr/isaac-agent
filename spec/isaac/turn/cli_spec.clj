(ns isaac.turn.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.spec-helper :as helper]
    [isaac.turn.cli :as sut]
    [isaac.turn.queue :as queue]
    [speclj.core :refer :all]))

(describe "turns cli"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "lists held turns with session, resource-pools, and state"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :resource-pools [:night-watch]
                     :state      :held
                     :created-at "2026-03-01T14:00:00Z"})
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["list"] :root "/test/isaac"})))]
      (should (str/includes? output "harbor"))
      (should (str/includes? output "night-watch"))
      (should (str/includes? output "held"))))

  (it "lists held turns in submit order"
    (queue/enqueue! {:id "later" :session "quay" :created-at "2026-03-01T14:00:02Z"})
    (queue/enqueue! {:id "first" :session "jetty" :created-at "2026-03-01T14:00:01Z"})
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["list"] :root "/test/isaac"})))]
      (should (< (str/index-of output "jetty")
                 (str/index-of output "quay")))))

  (it "shows the durable id, session, state and outcome after completion"
    (queue/enqueue! {:id "berth-1" :session "harbor" :state :queued})
    (queue/update-turn! "berth-1" {:state :finished :outcome :ok})
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["show" "berth-1"] :root "/test/isaac"})))]
      (should= "id: berth-1\nsession: harbor\nstate: finished\noutcome: ok\n" output)))

  (it "reports an unknown turn id"
    (let [output (with-out-str
                   (should= 1 (sut/run-fn {:_raw-args ["show" "unknown"] :root "/test/isaac"})))]
      (should (str/includes? output "unknown"))))

  (it "drops a held turn and prints dropped"
    (queue/enqueue! {:id "berth-1" :session "harbor"})
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["drop" "berth-1"] :root "/test/isaac"})))]
      (should (str/includes? output "dropped"))
      (should= :dropped (:outcome (queue/read-held "berth-1"))))))
