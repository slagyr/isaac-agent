;; mutation-tested: pending
(ns isaac.tool.tools-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.tool.tools-steps :as sut]
    [speclj.core :refer :all]))

(describe "tool feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (it)
    (g/reset!))

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
