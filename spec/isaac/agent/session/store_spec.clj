(ns isaac.agent.session.store-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.marigold :as marigold]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.session.store.memory :as memory]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.agent.session.store.spi"

  (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

  (it "defines a SessionStore protocol"
    (should-not-be-nil store/SessionStore))

  (describe "create-store"

    (it "creates an atom containing an empty map"
      (let [s (memory/create-store)]
        (should (satisfies? store/SessionStore s)))))

  (describe "open-session!"

    (it "adds a session keyed by the given key string"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (should= "k1" (:key (store/get-session s "k1")))))

    (it "can create multiple sessions"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/open-session! s "k2" {})
        (should= 2 (count (store/list-sessions s))))))

  (describe "get-session"

    (it "returns the session for a given key"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (should= "k1" (:key (store/get-session s "k1")))))

    (it "returns nil for a missing key"
      (let [s (memory/create-store)]
        (should-be-nil (store/get-session s "missing")))))

  (describe "list-sessions"

    (it "returns an empty list when no sessions exist"
      (let [s (memory/create-store)]
        (should= [] (vec (store/list-sessions s)))))

    (it "returns all sessions"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/open-session! s "k2" {})
        (should= 2 (count (store/list-sessions s))))))

  (describe "in-flight tracking"

    (it "claims a free session once and clears it again"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {:crew "main"})
        (should= true (store/mark-in-flight! s "k1"))
        (should= true (store/in-flight? s "k1"))
        (should= false (store/mark-in-flight! s "k1"))
        (store/clear-in-flight! s "k1")
        (should= false (store/in-flight? s "k1"))))

    (it "tracks in-flight counts by crew"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {:crew "main"})
        (store/open-session! s "k2" {:crew "main"})
        (store/open-session! s "k3" {:crew "other"})
        (store/mark-in-flight! s "k1")
        (store/mark-in-flight! s "k3")
        (should= 1 (store/in-flight-count s "main"))
        (should= 1 (store/in-flight-count s "other"))))

    (it "can-dispatch? admits an idle session while another session on its crew is in flight"
      (let [s (memory/create-store)]
        (store/open-session! s "port" {:crew "main"})
        (store/open-session! s "starboard" {:crew "main"})
        (store/mark-in-flight! s "port")
        (should= true (store/can-dispatch? s "starboard"))
        (should= false (store/can-dispatch? s "port")))))

  (describe "tag helpers"

    (it "returns tags for a session"
      (should= #{:project/chess} (store/tags-of {:tags #{:project/chess}})))

    (it "normalizes nil tags to an empty set"
      (should= #{} (store/tags->set nil))
      (should= #{} (store/tags-of {})))

    (it "keeps a set of keyword tags"
      (should= #{:ops :wip} (store/tags->set #{:ops :wip})))

    (it "turns a vector of keyword tags into a set"
      (should= #{:ops :wip} (store/tags->set [:ops :wip])))

    (it "turns a list of keyword tags into a set"
      (should= #{:ops} (store/tags->set '(:ops))))

    (it "turns string tags into keywords"
      (should= #{:ops :wip} (store/tags->set ["ops" "wip"])))

    (it "turns mixed keyword and string tags into one keyword set"
      (should= #{:ops :wip} (store/tags->set [:ops "wip"])))

    (it "returns true when a session has a tag"
      (should (store/has-tag? {:tags #{:project/chess}} :project/chess)))

    (it "matches a tag on a session whose tags are a vector"
      (should (store/has-tag? {:tags [:ops]} :ops))
      (should-not (store/has-tag? {:tags [:ops]} :wip)))

    (it "stores vector tags as a keyword set when opening a session"
      (let [s (memory/create-store)]
        (store/open-session! s "ops-room" {:tags [:ops "wip"]})
        (should= #{:ops :wip} (:tags (store/get-session s "ops-room")))))

    (it "filters sessions by required tags"
      (let [s (memory/create-store)]
        (store/open-session! s "joe" {:crew "main" :tags #{:role/worker :project/chess}})
        (store/open-session! s "sue" {:crew "main" :tags #{:role/worker}})
        (should= ["joe"]
                 (mapv :id (store/by-tags s #{:role/worker :project/chess}))))))

  (describe "rename-session!"

    (it "moves an idle session to the new key, preserving crew tags and tokens"
      (let [s (memory/create-store)]
        (store/open-session! s "joe" {:crew "main" :tags #{:project/x :wip}})
        (store/update-session! s "joe" {:total-tokens 5000 :last-input-tokens 5000})
        (store/append-message! s "joe" {:role "user" :content "hello"})
        (let [result (store/rename-session! s "joe" "skipper")
              entry  (store/get-session s "skipper")]
          (should= "skipper" (:id result))
          (should= "skipper" (:key entry))
          (should= "main" (:crew entry))
          (should= #{:project/x :wip} (:tags entry))
          (should= 5000 (:total-tokens entry))
          (should= 5000 (:last-input-tokens entry))
          (should-be-nil (store/get-session s "joe"))
          (should= 2 (count (store/get-transcript s "skipper"))))))

    (it "refuses a collision without clobbering the target"
      (let [s (memory/create-store)]
        (store/open-session! s "joe" {:crew "main"})
        (store/open-session! s "skipper" {:crew "ketch"})
        (try
          (store/rename-session! s "joe" "skipper")
          (should-fail "expected collision")
          (catch clojure.lang.ExceptionInfo e
            (should= :collision (:reason (ex-data e)))
            (should= "ketch" (:crew (store/get-session s "skipper")))
            (should-not-be-nil (store/get-session s "joe"))))))

    (it "refuses renaming an in-flight session"
      (let [s (memory/create-store)]
        (store/open-session! s "joe" {:crew "main"})
        (store/mark-in-flight! s "joe")
        (try
          (store/rename-session! s "joe" "skipper")
          (should-fail "expected in-flight refusal")
          (catch clojure.lang.ExceptionInfo e
            (should= :in-flight (:reason (ex-data e)))
            (should-not-be-nil (store/get-session s "joe"))
            (should-be-nil (store/get-session s "skipper"))))))

    (it "returns nil when the source session is missing"
      (let [s (memory/create-store)]
        (should-be-nil (store/rename-session! s "missing" "skipper"))))))

  (describe "request-cancel!"

    (it "returns false and creates nothing when no marker exists"
      (let [s (memory/create-store)]
        (store/open-session! s "idle" {})
        (should-not (store/request-cancel! s "idle"))
        (should-be-nil (store/get-turn-marker s "idle"))))

    (it "stamps :cancelled true on an existing marker and returns true"
      (let [s (memory/create-store)]
        (store/open-session! s "live" {})
        (store/record-turn-marker! s "live" {:source :cli :started-at "2026-04-21T09:59:30Z"})
        (should (store/request-cancel! s "live"))
        (let [marker (store/get-turn-marker s "live")]
          (should= true (:cancelled marker))
          (should= :cli (:source marker)))))
    )
