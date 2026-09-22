(ns isaac.llm.turn-instructions-spec
  (:require [speclj.core :refer :all]
            [isaac.llm.turn-instructions :as sut]))

(describe "parallel tool calls hint (isaac-pn98)"

  (it "opens with the capability statement"
    (should-contain "You can call several tools in one response." sut/parallel-tool-calls-hint))

  (it "names the cost of a single-call response"
    (should-contain "costs a full round-trip over the whole context" sut/parallel-tool-calls-hint))

  (it "defaults to parallel and names the only sequential case"
    (should-contain "Default to parallel." sut/parallel-tool-calls-hint)
    (should-contain "Run calls sequentially only when you need the output or side effect of one to decide the arguments of the next."
                    sut/parallel-tool-calls-hint))

  (it "bounds a batch"
    (should-contain "Keep a batch to about 3 to 5 calls." sut/parallel-tool-calls-hint))

  (it "keeps the locate-then-read and load-a-skill-once lines"
    (should-contain "Locate first with grep or glob" sut/parallel-tool-calls-hint)
    (should-contain "Load a skill once per turn" sut/parallel-tool-calls-hint)))
