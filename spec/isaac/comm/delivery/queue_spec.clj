(ns isaac.comm.delivery.queue-spec
  (:require
    [isaac.comm.delivery.queue :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "comm.delivery.queue"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "stores a queued delivery under comm/delivery/pending"
    (sut/enqueue! {:id      "7f3a"
                   :comm    (keyword marigold/longwave)
                   :target  "C999"
                   :content "Hello"})
    (should= {:id      "7f3a"
              :comm    (keyword marigold/longwave)
              :target  "C999"
              :content "Hello"
              :attempts 0}
             (select-keys (sut/read-pending "7f3a") [:id :comm :target :content :attempts])))

  (it "stores the pending file at comm/delivery/pending/<id>.edn"
    (sut/enqueue! {:id "7f3a" :comm (keyword marigold/longwave) :target "C999" :content "Hi"})
    (should (fs/exists? (nexus/get :fs) "/test/isaac/comm/delivery/pending/7f3a.edn")))

  (it "moves a pending delivery to comm/delivery/failed"
    (sut/enqueue! {:id      "7f3a"
                   :comm    (keyword marigold/longwave)
                   :target  "C999"
                   :content "Hello"})
    (sut/move-to-failed! "7f3a" {:attempts 5})
    (should-be-nil (sut/read-pending "7f3a"))
    (should= 5 (:attempts (sut/read-failed "7f3a")))))
