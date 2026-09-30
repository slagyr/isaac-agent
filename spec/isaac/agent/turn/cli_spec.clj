(ns isaac.agent.turn.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.turn.cli :as sut]
    [isaac.agent.turn.queue :as queue]
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
      (doseq [fragment ["id: berth-1" "session: harbor" "state: finished" "outcome: ok"
                        "created-at: " "finished-at: "]]
        (should (str/includes? output fragment)))))

  (it "reports an unknown turn id"
    (let [err (java.io.StringWriter.)
          output (binding [*err* err]
                   (should= 1 (sut/run-fn {:_raw-args ["show" "unknown"] :root "/test/isaac"}))
                   (str err))]
      (should (str/includes? output "unknown"))))

  (it "shows the full durable record including opaque origin and timings"
    (queue/enqueue! {:id "tide-9" :session "harbor" :input "Leave harbor"
                     :origin {:kind :hail :data {:route "quay"}} :state :queued})
    (queue/claim! "tide-9")
    (queue/update-turn! "tide-9" {:state :finished :outcome :error :reason "lamp oil spilled"})
    (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["show" "tide-9"] :root "/test/isaac"})))]
      (doseq [fragment ["input: Leave harbor" "origin.kind: hail" "origin.data: {:route \"quay\"}"
                        "started-at: " "finished-at: " "reason: lamp oil spilled"]]
        (should (str/includes? output fragment)))))

  (it "shows thread and reply-to before params so hail chains read in order"
    (queue/enqueue! {:id "turn-1" :input "Resonance climbing" :origin {:source :hail
                                                                           :thread-id "thread-7"
                                                                           :reply-to "hail-42"
                                                                           :params {:coil "secondary"}}})
    (let [output (with-out-str (sut/run-fn {:_raw-args ["show" "turn-1"] :root "/test/isaac"}))]
      (should (< (str/index-of output "origin.thread-id: thread-7")
                 (str/index-of output "origin.reply-to: hail-42")))))

  (it "drops a held turn and prints dropped"
    (queue/enqueue! {:id "berth-1" :session "harbor"})
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["drop" "berth-1"] :root "/test/isaac"})))]
      (should (str/includes? output "dropped"))
      (should= :dropped (:outcome (queue/read-held "berth-1"))))))
