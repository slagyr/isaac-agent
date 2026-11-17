(ns isaac.agent.bridge.cancel-edges-spec
  "Edges of expect-turn!/settle-turn! and the other cancel! callers."
  (:require
    [isaac.agent.bridge.cancellation :as bc]
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.bridge.suspend :as suspend]
    [isaac.agent.charge :as charge]
    [isaac.agent.drive.turn :as single-turn]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.session.store.spi :as store]
    [isaac.foundation.config.api :as config]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private cfg
  {:defaults  {:frequencies {:crew "main"} :crew {:model "fast"}}
   :crew      {"main" {:soul "Main soul" :model "fast"}}
   :models    {"fast" {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}}
   :providers {marigold/quantum-anvil {:api marigold/anvil-api}}})

(defn- one-turn! [sk]
  (let [t (bc/begin-turn! sk)]
    (bc/end-turn! sk t)))

(defn- next-turn-cancelled? [sk]
  (let [t (bc/begin-turn! sk)
        c (bc/cancelled? sk)]
    (bc/end-turn! sk t)
    c))

(describe "bridge cancellation edges"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (bc/clear!)
    (example)
    (bc/clear!))

  (it "an expectation settled without a turn (prompt rejected) leaves nothing behind"
    (let [token (bc/expect-turn! "rej")]
      (bc/settle-turn! "rej" token))
    (bc/cancel! "rej")
    (should-not (next-turn-cancelled? "rej")))

  (it "a cancel kept for an expectation that is then settled without a turn is dropped"
    (let [token (bc/expect-turn! "rej2")]
      (bc/cancel! "rej2")
      (bc/settle-turn! "rej2" token))
    (should-not (next-turn-cancelled? "rej2")))

  (it "a cancel between settle-turn! and the next expect-turn! is a no-op"
    (let [t1 (bc/expect-turn! "gap")]
      (one-turn! "gap")
      (bc/settle-turn! "gap" t1))
    (bc/cancel! "gap")
    (let [t2 (bc/expect-turn! "gap")]
      (should-not (next-turn-cancelled? "gap"))
      (bc/settle-turn! "gap" t2)))

  (it "two expectations: a cancel during the first turn does not reach the second"
    (let [a (bc/expect-turn! "two")
          b (bc/expect-turn! "two")
          ta (bc/begin-turn! "two")]
      (bc/cancel! "two")
      (should (bc/cancelled? "two"))
      (bc/end-turn! "two" ta)
      (bc/settle-turn! "two" a)
      (should-not (next-turn-cancelled? "two"))
      (bc/settle-turn! "two" b)))

  (it "settling an unknown token is harmless"
    (bc/settle-turn! "nobody" (Object.))
    (bc/cancel! "nobody")
    (should-not (next-turn-cancelled? "nobody")))

  (it "concurrent sessions: another session's expectation does not keep this session's cancel"
    (let [t (bc/expect-turn! "s2")]
      (bc/cancel! "s1")
      (should-not (next-turn-cancelled? "s1"))
      (bc/settle-turn! "s2" t)))

  (it "concurrent sessions: another session in flight does not keep this session's cancel"
    (helper/with-memory-store
      (let [ss (store/registered-store)]
        (store/open-session! ss "s2" {:crew "main"})
        (store/mark-in-flight! ss "s2")
        (bc/cancel! "s1")
        (should-not (next-turn-cancelled? "s1"))
        (store/clear-in-flight! ss "s2")))))

(describe "bridge cancellation: other cancel! callers"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (bc/clear!)
    (suspend/clear!)
    (helper/with-memory-store
      (nexus/-with-nested-nexus {:root (str "/tmp/cancel-edges-spec-" (random-uuid)) :fs (fs/mem-fs)}
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "w" {:crew "main"})
        (example)))
    (suspend/clear!)
    (bc/clear!))

  (it "suspend! still cancels an admitted turn that has not begun"
    (let [ss (store/registered-store)]
      (store/mark-in-flight! ss "w")
      (let [f        (future (suspend/suspend! {:session-store ss :timeout-ms 2000}))
            deadline (+ (System/currentTimeMillis) 2000)]
        (while (and (not (bc/cancelled? "w")) (< (System/currentTimeMillis) deadline))
          (Thread/yield))
        (let [t (bc/begin-turn! "w")]
          (should (bc/cancelled? "w"))
          (bc/end-turn! "w" t))
        (store/clear-in-flight! ss "w")
        (deref f 3000 :timeout)))))
