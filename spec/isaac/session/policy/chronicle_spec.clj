(ns isaac.session.policy.chronicle-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.session.policy :as policy]
    [isaac.session.policy.chronicle :as sut]
    [isaac.session.store.sidecar :as sidecar]
    [isaac.session.store.spi :as store]
    [speclj.core :refer [describe it should= should-throw]]))

(def test-dir "/test/chronicle-policy")

(describe "ChroniclePolicy default-session"

  (it "returns nil when the crew has no sessions, even when another crew does"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [store (sidecar/create-store test-dir)
              pol   (sut/create store)]
          (store/open-session! store "helm-log" {:crew "cordelia"})
          (should= nil (policy/default-session pol "marvin" {}))))))

  (it "returns nil when crew is nil"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [store (sidecar/create-store test-dir)
              pol   (sut/create store)]
          (store/open-session! store "helm-log" {:crew "cordelia"})
          (should= nil (policy/default-session pol nil {}))))))

  (it "returns the crew's most recently updated session"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [store (sidecar/create-store test-dir)
              pol   (sut/create store)]
          (store/open-session! store "helm-log" {:crew "marvin"})
          (store/update-session! store "helm-log" {:updated-at "2026-09-24T10:00:00"})
          (store/open-session! store "watch-log" {:crew "marvin"})
          (store/update-session! store "watch-log" {:updated-at "2026-09-24T11:00:00"})
          (should= "watch-log" (policy/default-session pol "marvin" {})))))))

(describe "ChroniclePolicy open-session!"

  (it "refuses to open an id that already belongs to another crew"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [store (sidecar/create-store test-dir)
              pol   (sut/create store)]
          (store/open-session! store "helm-log" {:crew "cordelia"})
          (should-throw clojure.lang.ExceptionInfo
            (policy/open-session! pol "helm-log" {:crew "marvin"})))))))
