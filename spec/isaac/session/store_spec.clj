(ns isaac.session.store-spec
  (:require
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.session.store.spi"

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

  (describe "append-message!"

    (it "updates last-channel from message"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/append-message! s "k1" {:channel marigold/longwave})
        (should= marigold/longwave (:last-channel (store/get-session s "k1")))))

    (it "updates last-to from message"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/append-message! s "k1" {:to marigold/captain})
        (should= marigold/captain (:last-to (store/get-session s "k1")))))

    (it "updates both last-channel and last-to when both present"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/append-message! s "k1" {:channel marigold/skybeam :to marigold/first-mate})
        (let [session (store/get-session s "k1")]
          (should= marigold/skybeam (:last-channel session))
          (should= marigold/first-mate (:last-to session)))))

    (it "handles string keys in message map"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/append-message! s "k1" {"channel" marigold/logbook "to" marigold/navigator})
        (let [session (store/get-session s "k1")]
          (should= marigold/logbook (:last-channel session))
          (should= marigold/navigator (:last-to session)))))

    (it "does not alter session when message has no channel or to"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {})
        (store/append-message! s "k1" {:text "hello"})
        (let [session (store/get-session s "k1")]
          (should-be-nil (:last-channel session))
          (should-be-nil (:last-to session))))))

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

    (it "can-dispatch? defaults crew capacity to one"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {:crew "main"})
        (should= true (store/can-dispatch? s "main"))
        (store/mark-in-flight! s "k1")
        (should= false (store/can-dispatch? s "main"))))

    (it "can-dispatch? respects configured max-in-flight"
      (let [s (memory/create-store)]
        (store/open-session! s "k1" {:crew "main"})
        (store/open-session! s "k2" {:crew "main"})
        (nexus/-with-nexus {:config (atom {:crew {"main" {:max-in-flight 2}}})}
          (should= true (store/mark-in-flight! s "k1"))
          (should= true (store/can-dispatch? s "main"))
          (should= true (store/mark-in-flight! s "k2"))
          (should= false (store/can-dispatch? s "main"))))))

  (describe "tag helpers"

    (it "returns tags for a session"
      (should= #{:project/chess} (store/tags-of {:tags #{:project/chess}})))

    (it "returns true when a session has a tag"
      (should (store/has-tag? {:tags #{:project/chess}} :project/chess)))

    (it "filters sessions by required tags"
      (let [s (memory/create-store)]
        (store/open-session! s "joe" {:crew "main" :tags #{:role/worker :project/chess}})
        (store/open-session! s "sue" {:crew "main" :tags #{:role/worker}})
        (should= ["joe"]
                 (mapv :id (store/by-tags s #{:role/worker :project/chess})))))))
