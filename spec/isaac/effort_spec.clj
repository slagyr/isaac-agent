(ns isaac.effort-spec
  (:require
    [isaac.effort :as sut]
    [speclj.core :refer :all]))

(describe "Effort"

  (describe "effort->string"
    (it "maps 0 to nil (omit)"
      (should-be-nil (sut/effort->string 0)))

    (it "maps 1 to low"
      (should= "low" (sut/effort->string 1)))

    (it "maps 3 to low"
      (should= "low" (sut/effort->string 3)))

    (it "maps 4 to medium"
      (should= "medium" (sut/effort->string 4)))

    (it "maps 6 to medium"
      (should= "medium" (sut/effort->string 6)))

    (it "maps 7 to high"
      (should= "high" (sut/effort->string 7)))

    (it "maps 10 to high"
      (should= "high" (sut/effort->string 10)))

    (it "maps nil to nil"
      (should-be-nil (sut/effort->string nil))))

  (describe "resolve-effort"
    (it "returns default 7 when nothing is configured"
      (should= 7 (sut/resolve-effort {} {} {} {} {})))

    (it "uses session effort over all others"
      (should= 3 (sut/resolve-effort {:effort 3}
                                      {:effort 5}
                                      {:effort 8}
                                      {:effort 9}
                                      {:effort 6})))

    (it "uses crew effort when session has none"
      (should= 5 (sut/resolve-effort {}
                                      {:effort 5}
                                      {:effort 8}
                                      {:effort 9}
                                      {:effort 6})))

    (it "uses model effort when session and crew have none"
      (should= 8 (sut/resolve-effort {}
                                      {}
                                      {:effort 8}
                                      {:effort 9}
                                      {:effort 6})))

    (it "uses provider effort when session, crew, model have none"
      (should= 9 (sut/resolve-effort {}
                                      {}
                                      {}
                                      {:effort 9}
                                      {:effort 6})))

    (it "uses defaults effort when no higher override"
      (should= 6 (sut/resolve-effort {}
                                      {}
                                      {}
                                      {}
                                      {:effort 6})))

    (it "allows effort 0 to suppress the field"
      (should= 0 (sut/resolve-effort {:effort 0} {:effort 5} {} {} {})))))
