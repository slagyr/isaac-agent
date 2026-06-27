(ns isaac.session.frequencies-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.session.frequencies :as sut]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "session frequencies"

  (around [example] (helper/with-memory-store (example)))

  (describe "frequencies schema"

    (it "validates a complete valid frequencies map"
      (should= {:session          ["bridge"]
                :session-tags     [:wip :project/chess]
                :crew             "ketch"
                :reach            :one
                :prefer           :recent
                :create           :if-missing
                :with-crew        "main"
                :with-model       "grover"
                :with-effort      3
                :with-context-mode :full}
               (sut/conform-frequencies!
                 {:session          ["bridge"]
                  :session-tags     [:wip :project/chess]
                  :crew             "ketch"
                  :reach            :one
                  :prefer           :recent
                  :create           :if-missing
                  :with-crew        "main"
                  :with-model       "grover"
                  :with-effort      3
                  :with-context-mode :full})))

    (it "rejects :create outside #{:never :if-missing :always}"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/conform-frequencies! {:create :sometimes})))

    (it "rejects :prefer outside #{:recent :oldest}"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/conform-frequencies! {:prefer :sideways})))

    (it "rejects :reach outside #{:one :all}"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/conform-frequencies! {:reach :many})))

    (it "rejects an unrecognized key"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/conform-frequencies! {:crew "ketch" :spawn-session true}))))

  (describe "matching-sessions"

    (it "filters by crew"
      (helper/create-session! "/test" "alpha" {:crew "ketch"})
      (helper/create-session! "/test" "beta" {:crew "main"})
      (let [matches (sut/matching-sessions {:crew "ketch"} (helper/list-sessions "/test"))]
        (should= ["alpha"] (mapv :id matches))))

    (it "AND-composes session-tags"
      (helper/create-session! "/test" "tagged" {:tags #{:project/chess :wip}})
      (helper/create-session! "/test" "partial" {:tags #{:project/chess}})
      (let [matches (sut/matching-sessions {:session-tags #{:project/chess :wip}}
                                            (helper/list-sessions "/test"))]
        (should= ["tagged"] (mapv :id matches))))

    (it "matches an explicit session id"
      (helper/create-session! "/test" "bridge" {:crew "main"})
      (let [matches (sut/matching-sessions {:session ["bridge"]} (helper/list-sessions "/test"))]
        (should= ["bridge"] (mapv :id matches)))))

  (describe "resolve-session-targets"

    (it "returns an existing default session without creating"
      (helper/create-session! "/test" "prompt-default" {:crew "main"})
      (let [result (sut/resolve-session-targets {:default-session-key "prompt-default"
                                                 :create              :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "prompt-default" (:session-key result))))

    (it "creates the default session key when missing"
      (let [result (sut/resolve-session-targets {:default-session-key "prompt-default"
                                                 :create              :if-missing}
                                                (store/registered-store))]
        (should= true (:create? result))
        (should= "prompt-default" (:session-key result))))

    (it "errors on :create :never when the default session is missing"
      (let [result (sut/resolve-session-targets {:default-session-key "prompt-default"
                                                 :create              :never}
                                                (store/registered-store))]
        (should= :no-match (:error result))))

    (it "selects the most recent crew session when multiple match"
      (helper/create-session! "/test" "older" {:crew "ketch" :updated-at "2026-04-10T10:00:00"})
      (helper/create-session! "/test" "recent" {:crew "ketch" :updated-at "2026-04-12T15:00:00"})
      (let [result (sut/resolve-session-targets {:crew   "ketch"
                                                 :create :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "recent" (:session-key result))))

    (it "selects the oldest crew session when :prefer :oldest"
      (helper/create-session! "/test" "older" {:crew "ketch" :updated-at "2026-04-10T10:00:00"})
      (helper/create-session! "/test" "recent" {:crew "ketch" :updated-at "2026-04-12T15:00:00"})
      (let [result (sut/resolve-session-targets {:crew    "ketch"
                                                 :prefer  :oldest
                                                 :create  :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "older" (:session-key result))))

    (it "selects the most recent crew session when :prefer :recent"
      (helper/create-session! "/test" "older" {:crew "ketch" :updated-at "2026-04-10T10:00:00"})
      (helper/create-session! "/test" "recent" {:crew "ketch" :updated-at "2026-04-12T15:00:00"})
      (let [result (sut/resolve-session-targets {:crew    "ketch"
                                                 :prefer  :recent
                                                 :create  :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "recent" (:session-key result))))

    (it "ignores :prefer when an explicit session id is unambiguous"
      (helper/create-session! "/test" "foo" {:crew "main"})
      (let [result (sut/resolve-session-targets {:session ["foo"]
                                                 :prefer  :oldest
                                                 :create  :never}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "foo" (:session-key result))))

    (it "--resume selects the most recent session across all crews by default"
      (helper/create-session! "/test" "older" {:crew "ketch" :updated-at "2026-04-10T10:00:00"})
      (helper/create-session! "/test" "recent" {:crew "main" :updated-at "2026-04-12T15:00:00"})
      (let [result (sut/resolve-session-targets {:resume true :create :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "recent" (:session-key result))))

    (it "creates a crew session when none match"
      (let [result (sut/resolve-session-targets {:crew   "ketch"
                                                 :create :if-missing}
                                                (store/registered-store))]
        (should= true (:create? result))
        (should (nil? (:session-key result)))
        (should= "ketch" (:crew (:create-identity result)))))

    (it "always creates a fresh session when :create :always"
      (helper/create-session! "/test" "existing" {:crew "ketch"})
      (let [result (sut/resolve-session-targets {:crew   "ketch"
                                                 :create :always}
                                                (store/registered-store))]
        (should= true (:create? result))
        (should (nil? (:session-key result)))
        (should= "ketch" (:crew (:create-identity result)))))

    (it "errors on :create :never when describe selectors find no match"
      (let [result (sut/resolve-session-targets {:crew   "ketch"
                                                 :create :never}
                                                (store/registered-store))]
        (should= :no-match (:error result))))

    (it "resolves an explicit session id"
      (helper/create-session! "/test" "bridge" {:crew "main"})
      (let [result (sut/resolve-session-targets {:session ["bridge"]
                                                 :create  :never}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "bridge" (:session-key result))))

    (it "creates an explicit session id when missing and :create :if-missing"
      (let [result (sut/resolve-session-targets {:session ["fresh"]
                                                 :create  :if-missing}
                                                (store/registered-store))]
        (should= true (:create? result))
        (should= "fresh" (:session-key result))))

    (it "errors when an explicit session id is missing and :create :never"
      (let [result (sut/resolve-session-targets {:session ["missing"]
                                                 :create  :never}
                                                (store/registered-store))]
        (should= :no-match (:error result))))

    (it "selects by session-tags"
      (helper/create-session! "/test" "tagged" {:tags #{:project/chess}})
      (let [result (sut/resolve-session-targets {:session-tags #{:project/chess}
                                                 :create         :if-missing}
                                                (store/registered-store))]
        (should= false (:create? result))
        (should= "tagged" (:session-key result))))))