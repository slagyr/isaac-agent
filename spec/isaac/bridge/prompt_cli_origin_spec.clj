(ns isaac.bridge.prompt-cli-origin-spec
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.bridge.prompt-cli :as sut]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as single-turn]
    [isaac.marigold :as marigold]
    [isaac.session.spec-helper :as helper]
    [speclj.core :refer :all]))

(def crew-name marigold/captain)
(def crew-soul (:soul (marigold/crew-cfg crew-name)))

(def ^:private base-opts
  {:root "/test/prompt"
   :crew      crew-name})

(def ^:private synthetic-config
  {:crew   {crew-name {:name crew-name :soul crew-soul :model "grover"}}
   :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(describe "CLI Prompt origin"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (helper/with-memory-store (example)))

  (redefs-around [loader/load-config!       (fn [& _] synthetic-config)
                  loader/load-config-result (fn [& _] {:config synthetic-config})])

  (it "creates prompt sessions with a cli origin"
    (with-redefs [single-turn/run-turn! (fn [charge]
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
                                       {:content "Hello"})]
        (with-out-str
          (should= 0 (sut/run (assoc base-opts :message "Hi")))))
      (should= {:kind :cli} (:origin @captured)))))
