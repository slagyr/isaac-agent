;; mutation-tested: pending
(ns isaac.tool.tools-steps-spec
  (:require
    [clojure.java.io :as io]
    [gherclj.core :as g]
    [isaac.nexus :as nexus]
    [isaac.session.store.memory :as memory]
    [isaac.session.store.spi :as store]
    [isaac.tool.tools-steps :as sut]
    [speclj.core :refer [around describe it should-be-nil should-not=]]))

(defn- delete-tree! [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

(describe "tool feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (it)
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
                                    :rows    [["#\"(?s).*resource path escapes the skill directory.*\""]]}))))
