(ns isaac.tool.comm-send-spec
  (:require
    [isaac.comm.delivery.queue :as queue]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.tool.comm-send :as sut]
    [speclj.core :refer :all]))

(def ^:private api-safe-property-key-re
  #"^[a-zA-Z0-9_.-]{1,64}$")

(describe "tool.comm-send"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (loader/set-snapshot!
        {:comms        {:skybeam {:type :skybeam}
                        :tannoy  {:type :telly}}
         :module-index {:isaac.comm.telly
                        {:manifest
                         {:isaac.server/comm
                          {:telly {:send-schema {:telly/target {:type :string}
                                                 :telly/loft   {:type :string}}}}}}}}
        "comm_send spec")
      (example)))

  (describe "build-parameters"

    (it "includes only comm and content when no comm declares send-schema"
      (let [params (sut/build-parameters {:module-index {}
                                           :comms        {:skybeam {:type :skybeam}}})]
        (should= #{"comm" "content"} (set (keys (:properties params))))
        (should= ["comm" "content"] (:required params))))

    (it "unions namespaced send-schema fields from configured comms"
      (let [params (sut/build-parameters {:module-index (:module-index (loader/snapshot "test"))
                                           :comms        (:comms (loader/snapshot "test"))})]
        (should= #{"comm" "content" "telly.target" "telly.loft"}
                 (set (keys (:properties params))))))

    (it "exposes only tool-API-safe JSON property keys"
      (let [params (sut/build-parameters {:module-index (:module-index (loader/snapshot "test"))
                                           :comms        (:comms (loader/snapshot "test"))})]
        (doseq [k (keys (:properties params))]
          (should (re-matches api-safe-property-key-re k))))))

  (describe "comm-send-tool"

    (it "enqueues a delivery with namespaced record keys"
      (let [result (sut/comm-send-tool {"comm"         "tannoy"
                                        "content"      "Lantern is lit."
                                        "telly.target" "bridge"
                                        "telly.loft"   "high"})]
        (should-not (:isError result))
        (let [pending (queue/list-pending)]
          (should= 1 (count pending))
          (should= {:comm         :tannoy
                    :content      "Lantern is lit."
                    :telly/target "bridge"
                    :telly/loft   "high"}
                   (select-keys (first pending)
                                [:comm :content :telly/target :telly/loft])))))

    (it "errors on unknown comm slots without enqueueing"
      (let [result (sut/comm-send-tool {"comm" "phantom" "content" "Anyone there?"})]
        (should (:isError result))
        (should= [] (queue/list-pending))))

    (it "resolves comm slots keyed as strings in config"
      (loader/set-snapshot!
        (assoc (loader/snapshot "comm_send spec")
               :comms {"tannoy" {:type :telly}})
        "comm_send spec string keys")
      (let [result (sut/comm-send-tool {"comm"         "tannoy"
                                        "content"      "Lantern is lit."
                                        "telly.target" "bridge"
                                        "telly.loft"   "high"})]
        (should-not (:isError result))
        (should= 1 (count (queue/list-pending)))))))