;; mutation-tested: pending
(ns isaac.tool.tools-steps-spec
  (:require
    [clojure.java.io :as io]
    [gherclj.core :as g]
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]
    [isaac.tool.registry :as registry]
    [isaac.tool.tools-steps :as sut]
    [speclj.core :refer [around describe it should should-be-nil should-not= should=]]))

(defn- delete-tree! [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

(describe "tool feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (registry/clear!)
    (bridge-cancel/clear!)
    (it)
    (registry/clear!)
    (bridge-cancel/clear!)
    (g/reset!))

  (describe "clean test directory"

    (it "forgets a leftover session store from a prior feature so sessions land in the new root"
      (let [dir      "target/test-clean-dir-spec"
            leftover (memory/create-store "/target/test-state")]
        (try
          (store/register-store! leftover)
          (sut/clean-test-dir dir)
          (should-not= leftover (store/registered-store))
          (should-be-nil (store/registered-store))
          (should-be-nil (nexus/get :sessions))
          (finally
            (delete-tree! dir)
            (nexus/deregister! [:sessions]))))))

  (describe "tool result lines match"

    (it "preserves substring matching for plain text rows"
      (g/assoc! :tool-result {:result "alpha\nbeta"})
      (sut/tool-result-lines-match {:headers ["line"]
                                    :rows    [["alpha"]
                                              ["beta"]]}))

    (it "supports regex literal rows"
      (g/assoc! :tool-result {:result "resource path escapes the skill directory: ../../auth.json"})
      (sut/tool-result-lines-match {:headers ["line"]
                                    :rows    [["#\"(?s).*resource path escapes the skill directory.*\""]]})))

  (describe "mock tool helpers"

    (it "allows an unqualified mock tool family by its wire prefix"
      (let [captured (atom nil)]
        (with-redefs [session-steps/crew-tool-allow (fn [_ tools-str]
                                                      (reset! captured tools-str))]
          (#'sut/allow-mock-tool! "test__slow"))
        (should= "test" @captured)))

    (it "accepts a Gherkin string when registering a rendezvous tool"
      (with-redefs [session-steps/crew-tool-allow (fn [_ _] nil)]
        (sut/rendezvous-tool-registered "test__handshake" "met" "2"))
      (should= "test__handshake" (:name (registry/lookup "test__handshake"))))

    (it "returns cancelled when the blocking tool sees an already-cancelled turn"
      (let [session-key "anchor"]
        (bridge-cancel/begin-turn! session-key)
        (bridge-cancel/cancel! session-key)
        (with-redefs [session-steps/crew-tool-allow (fn [_ _] nil)]
          (sut/blocking-tool-registered "test__anchor"))
        (should= {:error :cancelled}
                 ((:handler (registry/lookup "test__anchor")) {"session_key" session-key}))))

    (it "keeps built-in tools registered when a mock tool is added"
      (with-redefs [session-steps/crew-tool-allow (fn [_ _] nil)]
        (registry/clear!)
        (builtin/register-all!)
        (let [before (registry/lookup "fs__read")]
          (should before)
          (sut/streaming-tool-registered "test__quick" "[]" "quick done")
          (should= before (registry/lookup "fs__read")))))))
