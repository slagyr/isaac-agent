(ns isaac.turn.queue-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.spec-helper :as helper]
    [isaac.turn.queue :as sut]
    [speclj.core :refer :all]))

(describe "turn.queue"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "stores a held turn under turns/held"
    (sut/enqueue! {:id         "berth-1"
                   :session    "harbor"
                   :turnstiles [[:tide "22:00-06:00"]]
                   :input      "Leave harbor"
                   :state      :held})
    (should= {:id         "berth-1"
              :session    "harbor"
              :turnstiles [[:tide "22:00-06:00"]]
              :input      "Leave harbor"
              :state      :held}
             (select-keys (sut/read-held "berth-1")
                          [:id :session :turnstiles :input :state])))

  (it "stores the held file at turns/held/<id>.edn"
    (sut/enqueue! {:id "berth-1" :session "harbor" :state :held})
    (should (fs/exists? (nexus/get :fs) "/test/isaac/turns/held/berth-1.edn")))

  (it "lists held turns in submit order"
    (sut/enqueue! {:id "later" :session "quay" :created-at "2026-03-01T14:00:02Z"})
    (sut/enqueue! {:id "first" :session "jetty" :created-at "2026-03-01T14:00:01Z"})
    (should= ["first" "later"] (mapv :id (sut/list-held))))

  (it "drops a held turn so it is no longer listed"
    (sut/enqueue! {:id "berth-1" :session "harbor"})
    (sut/delete-held! "berth-1")
    (should-be-nil (sut/read-held "berth-1"))
    (should= [] (sut/list-held)))

  (it "groups consecutive waiting records with the same coalesce key"
    (doseq [record [{:id "one" :session "harbor" :input "one" :state :waiting-session :coalesce-key "T1" :created-at "2026-03-01T14:00:01Z"}
                    {:id "two" :session "harbor" :input "two" :state :waiting-session :coalesce-key "T1" :created-at "2026-03-01T14:00:02Z"}
                    {:id "three" :session "harbor" :input "three" :state :waiting-session :created-at "2026-03-01T14:00:03Z"}]]
      (sut/enqueue! record))
    (should= [["one" "two"] ["three"]]
             (mapv (comp (partial mapv :id)) (sut/waiting-groups "harbor"))))
  )
