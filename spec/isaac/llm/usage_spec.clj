(ns isaac.llm.usage-spec
  (:require
    [isaac.llm.usage :as sut]
    [speclj.core :refer [describe it should should-not should=]]))

(describe "usage/normalize"

  (it "fills the required counts a provider left out"
    (should= {:prompt-tokens 10 :output-tokens 0}
             (sut/normalize {:prompt-tokens 10})))

  (it "keeps the optional counts a provider reported"
    (should= {:prompt-tokens 10 :output-tokens 2 :cache-read-tokens 7 :cache-write-tokens 3}
             (sut/normalize {:prompt-tokens 10 :output-tokens 2
                             :cache-read-tokens 7 :cache-write-tokens 3})))

  (it "drops :prompt-scope — it says how to read a figure, it is not one"
    (should= {:prompt-tokens 10 :output-tokens 2}
             (sut/normalize {:prompt-tokens 10 :output-tokens 2 :prompt-scope :request})))

  (it "reports an absent usage map as unmeasured, not as zero"
    (should-not (sut/supported? (sut/normalize nil)))
    (should= :absent (:unsupported-reason (sut/normalize nil))))

  (it "reports an empty usage map as unmeasured"
    (should-not (sut/supported? (sut/normalize {}))))

  (it "treats a reported zero as measured — a refusal really did cost nothing"
    (should (sut/supported? (sut/normalize {:prompt-tokens 0 :output-tokens 0}))))

  (it "keeps an explicit unsupported declaration"
    (should-not (sut/supported? (sut/normalize (sut/unsupported :no-wire-usage))))
    (should= :no-wire-usage (:unsupported-reason (sut/normalize (sut/unsupported :no-wire-usage))))))

(describe "usage/add"

  (it "counts the request and sums its tokens"
    (should= {:requests 1 :prompt-tokens 10 :output-tokens 2}
             (sut/add sut/empty-turn {:prompt-tokens 10 :output-tokens 2})))

  (it "sums optional counts across requests"
    (should= {:requests 2 :prompt-tokens 14 :output-tokens 6 :cache-read-tokens 9}
             (-> sut/empty-turn
                 (sut/add {:prompt-tokens 10 :output-tokens 2 :cache-read-tokens 7})
                 (sut/add {:prompt-tokens 4 :output-tokens 4 :cache-read-tokens 2}))))

  (it "counts an unmeasured request as a request and names it, never as a silent zero"
    (should= {:requests 2 :prompt-tokens 10 :output-tokens 2 :unsupported-requests 1}
             (-> sut/empty-turn
                 (sut/add {:prompt-tokens 10 :output-tokens 2})
                 (sut/add nil))))

  (it "never lets a declaration keyword into the arithmetic"
    (should= {:requests 1 :prompt-tokens 10 :output-tokens 2}
             (sut/add sut/empty-turn {:prompt-tokens 10 :output-tokens 2 :prompt-scope :request}))))

(describe "usage/total"

  (it "totals a sequence of per-request usages"
    (should= {:requests 3 :prompt-tokens 30 :output-tokens 6}
             (sut/total [{:prompt-tokens 10 :output-tokens 2}
                         {:prompt-tokens 10 :output-tokens 2}
                         {:prompt-tokens 10 :output-tokens 2}])))

  (it "totals nothing to an empty turn"
    (should= {:requests 0 :prompt-tokens 0 :output-tokens 0} (sut/total []))))

(describe "usage/reported?"

  (it "a turn that measured something reported something"
    (should (sut/reported? {:requests 1 :prompt-tokens 100 :output-tokens 0}))
    (should (sut/reported? {:requests 1 :prompt-tokens 0 :output-tokens 4})))

  (it "a turn of nothing but zeros did not report — it was not free"
    (should-not (sut/reported? {:requests 3 :prompt-tokens 0 :output-tokens 0}))
    (should-not (sut/reported? sut/empty-turn))
    (should-not (sut/reported? nil))))
