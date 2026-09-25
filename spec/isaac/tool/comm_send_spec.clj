(ns isaac.tool.comm-send-spec
  (:require
    [isaac.comm.delivery.queue :as queue]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as store-helper]
    [isaac.tool.comm-send :as sut]
    [speclj.core :refer :all]))

(def ^:private api-safe-property-key-re
  #"^[a-zA-Z0-9_.-]{1,64}$")

(def ^:private crew-name marigold/captain)
(def ^:private session-key "dawn-watch")
(def ^:private workdir "/test/isaac/workspace")

(defn- attach-config! []
  (loader/set-snapshot!
    (assoc (loader/snapshot "comm_send spec")
           :crew {crew-name {:tools {:directories {:allow [:cwd]}}}}
           :module-index {:isaac.comm.telly
                          {:manifest
                           {:isaac.agent/comm
                            {:telly {:send-attachments? true
                                     :send-schema       {:telly/target {:type :string}}}}}}})
    "comm_send spec attachments"))

(defn- write-workdir-file! [name content]
  (fs/mkdirs (fs/instance) workdir)
  (fs/spit (fs/instance) (str workdir "/" name) content))

(describe "tool.comm-send"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (loader/set-snapshot!
        {:comms        {:skybeam {:type :skybeam}
                        :tannoy  {:type :telly}}
         :module-index {:isaac.comm.telly
                        {:manifest
                         {:isaac.agent/comm
                          {:telly {:send-schema {:telly/target {:type :string}
                                                 :telly/loft   {:type :string}}}}}}}}
        "comm_send spec")
      (example)))

  (describe "build-parameters"

    (it "includes only the common fields when no comm declares send-schema"
      (let [params (sut/build-parameters {:module-index {}
                                           :comms        {:skybeam {:type :skybeam}}})]
        (should= #{"comm" "content" "attachments"} (set (keys (:properties params))))
        (should= ["comm" "content"] (:required params))))

    (it "offers attachments as an optional array of file paths"
      (let [params (sut/build-parameters {:module-index {}
                                           :comms        {:skybeam {:type :skybeam}}})
            spec   (get-in params [:properties "attachments"])]
        (should= "array" (:type spec))
        (should= {:type "string"} (:items spec))
        (should= "Local file paths to attach. Only comms that accept attachments take them."
                 (:description spec))))

    (it "unions namespaced send-schema fields from configured comms"
      (let [params (sut/build-parameters {:module-index (:module-index (loader/snapshot "test"))
                                           :comms        (:comms (loader/snapshot "test"))})]
        (should= #{"comm" "content" "attachments" "telly.target" "telly.loft"}
                 (set (keys (:properties params))))))

    (it "takes a slot with no :type to be the impl its slot id names, as the comm factory does (isaac-baf1)"
      (let [params (sut/build-parameters {:module-index (:module-index (loader/snapshot "test"))
                                           :comms        {:telly {:telly/loft "high"}}})]
        (should= #{"comm" "content" "attachments" "telly.target" "telly.loft"}
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
        (should= 1 (count (queue/list-pending)))))

    (describe "attachments"

      #_{:clj-kondo/ignore [:unresolved-symbol]}
      (around [example]
        (store-helper/with-memory-store
          (store-helper/create-session! "/test/isaac" session-key {:crew crew-name :cwd workdir})
          (attach-config!)
          (example)))

      (it "queues resolved attachment paths for a comm that accepts them"
        (write-workdir-file! "report.pdf" "%PDF-1.4 stub")
        (let [result (sut/comm-send-tool {"comm"        "tannoy"
                                          "content"     "Report attached."
                                          "attachments" ["report.pdf"]
                                          "session_key" session-key})]
          (should-not (:isError result))
          (should= [(str workdir "/report.pdf")]
                   (:attachments (first (queue/list-pending))))))

      (it "keeps absolute attachment paths as given"
        (write-workdir-file! "log.txt" "entry")
        (sut/comm-send-tool {"comm"        "tannoy"
                             "content"     "Log attached."
                             "attachments" [(str workdir "/log.txt")]
                             "session_key" session-key})
        (should= [(str workdir "/log.txt")]
                 (:attachments (first (queue/list-pending)))))

      (it "leaves :attachments off the record when none are given"
        (sut/comm-send-tool {"comm"        "tannoy"
                             "content"     "No files."
                             "session_key" session-key})
        (should-not (contains? (first (queue/list-pending)) :attachments)))

      (it "refuses attachments for a comm that does not accept them"
        (write-workdir-file! "report.pdf" "%PDF-1.4 stub")
        (let [result (sut/comm-send-tool {"comm"        "skybeam"
                                          "content"     "Here."
                                          "attachments" ["report.pdf"]
                                          "session_key" session-key})]
          (should (:isError result))
          (should= "comm skybeam does not accept attachments" (:error result))
          (should= [] (queue/list-pending))))

      (it "refuses an attachment outside the allowed directories"
        (let [result (sut/comm-send-tool {"comm"        "tannoy"
                                          "content"     "Here."
                                          "attachments" ["/etc/passwd"]
                                          "session_key" session-key})]
          (should (:isError result))
          (should= "path outside allowed directories: /etc/passwd" (:error result))
          (should= [] (queue/list-pending))))

      (it "refuses an attachment that does not exist"
        (let [result (sut/comm-send-tool {"comm"        "tannoy"
                                          "content"     "Here."
                                          "attachments" ["missing.pdf"]
                                          "session_key" session-key})]
          (should (:isError result))
          (should= (str "attachment not found: " workdir "/missing.pdf") (:error result))
          (should= [] (queue/list-pending))))

      (it "refuses an attachment that is a directory"
        (write-workdir-file! "reports/q3.pdf" "stub")
        (let [result (sut/comm-send-tool {"comm"        "tannoy"
                                          "content"     "Here."
                                          "attachments" ["reports"]
                                          "session_key" session-key})]
          (should (:isError result))
          (should= (str "attachment is not a regular file: " workdir "/reports") (:error result))
          (should= [] (queue/list-pending))))

      (it "refuses attachments that are not a list of paths"
        (let [result (sut/comm-send-tool {"comm"        "tannoy"
                                          "content"     "Here."
                                          "attachments" "report.pdf"
                                          "session_key" session-key})]
          (should (:isError result))
          (should= "attachments must be an array of file paths" (:error result))
          (should= [] (queue/list-pending)))))))
