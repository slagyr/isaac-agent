(ns isaac.recall.score-spec
  (:require
    [isaac.recall.score :as sut]
    [speclj.core :refer [context describe it should=]]))

(describe "isaac.recall.score"

  (context "cosine"
    (it "returns 1.0 for identical vectors"
      (should= 1.0 (sut/cosine [1.0 0.0] [1.0 0.0])))

    (it "returns 0.0 for orthogonal vectors"
      (should= 0.0 (sut/cosine [1.0 0.0] [0.0 1.0])))

    (it "returns 0.0 when either vector is empty or zero"
      (should= 0.0 (sut/cosine [] [1.0]))
      (should= 0.0 (sut/cosine [0.0 0.0] [1.0 2.0])))
    )

  (context "recency"
    (it "is 1.0 at age 0"
      (should= 1.0 (sut/recency 0.0 30.0)))

    (it "is 0.25 at one half-life of 30 days over 60 days"
      (should= 0.25 (sut/recency 60.0 30.0)))

    (it "is 0.5 at one half-life"
      (should= 0.5 (sut/recency 30.0 30.0)))
    )

  (context "lexical"
    (it "returns 1.0 when every query term appears in the scene"
      (should= 1.0 (sut/lexical "chart-7x2b" "resolved chart-7x2b by redrawing")))

    (it "returns 0.0 when no query term appears"
      (should= 0.0 (sut/lexical "chart-7x2b" "a light pinot noir")))

    (it "returns 0.5 when half the query terms appear"
      (should= 0.5 (sut/lexical "chart wine" "chart the reef")))
    )

  (context "blend"
    (it "normalizes the weighted sum by the weight total"
      (should= 0.25 (sut/blend {:text 1.0 :gist 0.0 :lex 0.0 :rec 0.0}
                               {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0})))

    (it "returns 0.0 when every weight is zero"
      (should= 0.0 (sut/blend {:text 1.0 :gist 1.0 :lex 1.0 :rec 1.0}
                              {:text 0.0 :gist 0.0 :lex 0.0 :rec 0.0})))
    )

  (context "default weights"
    (it "uses one part per channel"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0}
               sut/default-weights))
    )

  (context "resolve-weights"
    (it "starts from hardcoded defaults"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0}
               (sut/resolve-weights {} {})))

    (it "overlays :recall config weights"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 8.0}
               (sut/resolve-weights {:recall {:weights {:recency 8}}} {})))

    (it "lets CLI flags win over config"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0}
               (sut/resolve-weights {:recall {:weights {:recency 8}}}
                                    {:recency 1})))
    )
  )
