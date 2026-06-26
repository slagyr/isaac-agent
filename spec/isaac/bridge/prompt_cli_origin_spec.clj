(ns isaac.bridge.prompt-cli-origin-spec
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.bridge.prompt-cli :as sut]
    [isaac.agent.config.runtime :as runtime]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.marigold :as marigold]
    [isaac.session.context :as session-ctx]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]
    [speclj.core :refer :all]))

(def crew-name marigold/captain)
(def crew-soul (:soul (marigold/crew-cfg crew-name)))

(def ^:private base-opts
  {:root "/test/prompt"})

(def ^:private synthetic-config
  {:crew   {crew-name {:name crew-name :soul crew-soul :model "grover"}}
   :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(describe "CLI Prompt origin"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (helper/with-memory-store (example)))

  (redefs-around [loader/load-config!       (fn [& _] synthetic-config)
                  loader/load-config-result (fn [& _] {:config synthetic-config})
                  runtime/install!          (fn [_] nil)
                  builtin/register-all!     (fn [] nil)])

  (it "creates prompt sessions with a cli origin"
    (with-redefs [bridge/dispatch! (fn [charge]
                                     (comm/on-text-chunk (:comm charge) (:session-key charge) "Hello")
                                     {})]
      (with-out-str
        (should= 0 (sut/run (assoc base-opts :message "Hi"))))
      (let [session (helper/get-session "/test/prompt" "prompt-default")]
        (should= {:kind :cli} (:origin session)))))

  (it "charge carries the cli origin"
    (let [captured (atom nil)]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (reset! captured charge)
                                       (comm/on-text-chunk (:comm charge) (:session-key charge) "Hello")
                                       {})]
        (with-out-str
          (should= 0 (sut/run (assoc base-opts :message "Hi")))))
      (should= {:kind :cli} (:origin @captured)))))
