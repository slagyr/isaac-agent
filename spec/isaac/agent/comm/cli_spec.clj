(ns isaac.agent.comm.cli-spec
  (:require
    [clojure.edn :as edn]
    [isaac.agent.comm.cli :as sut]
    [isaac.agent.comm.delivery.queue :as queue]
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.tool.comm-send :as comm-send]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private root "/test/isaac")
(def ^:private slot-file (str root "/config/comms/tannoy.edn"))

(defn- write-slot! [slot]
  (fs/mkdirs (fs/instance) (str root "/config/comms"))
  (fs/spit (fs/instance) (str root "/config/isaac.edn") "{:defaults {:frequencies {:crew :main}} :crew {:main {}}}\n")
  (fs/spit (fs/instance) slot-file (pr-str slot)))

(defn- slot-on-disk []
  (edn/read-string (fs/slurp (fs/instance) slot-file)))

(defn- allow! [& args]
  (let [code (atom nil)
        out  (with-out-str (reset! code (sut/run-fn {:root root :_raw-args (vec (cons "allow" args))})))]
    {:code @code :out out}))

(def ^:private tannoy-index
  {:isaac.comm.tannoy {:manifest {:isaac.agent/comm {:tannoy {:send-schema {:tannoy/target {:type :string}}}}}}})

(defn- reload!
  "What the server's config watcher does on a file change: reload and
   commit the snapshot comm_send reads."
  []
  (let [cfg (loader/load-config! root (fs/instance) "comm cli spec reload")]
    (loader/set-snapshot! (update cfg :module-index merge tannoy-index) "comm cli spec reload")))

(describe "comms cli"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
      (example)))

  (describe "allow"

    (it "appends a target to the slot's :comm/allow-to"
      (write-slot! {:crew "main" :comm/allow-to ["bridge"]})
      (let [{:keys [code out]} (allow! "tannoy" "lookout")]
        (should= 0 code)
        (should-contain "tannoy allows lookout" out)
        (should= ["bridge" "lookout"] (:comm/allow-to (slot-on-disk)))
        (should= "main" (:crew (slot-on-disk)))))

    (it "creates the list when the slot has none"
      (write-slot! {:crew "main"})
      (should= 0 (:code (allow! "tannoy" "bridge")))
      (should= ["bridge"] (:comm/allow-to (slot-on-disk))))

    (it "is idempotent for a target already on the list"
      (write-slot! {:crew "main" :comm/allow-to ["bridge"]})
      (let [{:keys [code out]} (allow! "tannoy" "bridge")]
        (should= 0 code)
        (should-contain "already allows bridge" out)
        (should= ["bridge"] (:comm/allow-to (slot-on-disk)))))

    (it "lists the allow-list when given only a slot"
      (write-slot! {:crew "main" :comm/allow-to ["bridge" "lookout"]})
      (let [{:keys [code out]} (allow! "tannoy")]
        (should= 0 code)
        (should-contain "bridge" out)
        (should-contain "lookout" out)))

    (it "refuses an unknown slot"
      (write-slot! {:crew "main"})
      (let [{:keys [code]} (allow! "phantom" "bridge")]
        (should= 1 code)))

    (it "lets the next comm_send to the added target through, with no restart"
      (write-slot! {:comm/allow-to ["bridge"]})
      (reload!)
      (should (:isError (comm-send/comm-send-tool {"comm" "tannoy" "content" "hi" "tannoy.target" "lookout"})))
      (allow! "tannoy" "lookout")
      (reload!)
      (let [result (comm-send/comm-send-tool {"comm" "tannoy" "content" "hi" "tannoy.target" "lookout"})]
        (should-not (:isError result))
        (should= 1 (count (queue/list-pending)))))))
