(ns isaac.session.selector-cli-spec
  (:require
    [isaac.session.selector-cli :as sut]
    [speclj.core :refer :all]))

(describe "session selector CLI"

  (describe "validate-select-options"

    (it "rejects --session combined with --crew"
      (let [errors (sut/validate-select-options {:session "bridge" :crew "main"})]
        (should (pos? (count errors)))
        (should (re-find #"--session" (first errors)))))

    (it "rejects --session combined with --session-tag"
      (let [errors (sut/validate-select-options {:session "bridge" :session-tag ["wip"]})]
        (should (pos? (count errors)))))

    (it "rejects --session combined with --create"
      (let [errors (sut/validate-select-options {:session "bridge" :create :always})]
        (should (pos? (count errors)))))

    (it "accepts describe flags together"
      (should= [] (sut/validate-select-options {:crew "ketch" :session-tag ["wip"]})))

    (it "rejects --resume combined with --session"
      (let [errors (sut/validate-select-options {:resume true :session "bridge"})]
        (should (pos? (count errors)))))

    (it "rejects invalid --create values"
      (let [errors (sut/validate-select-options {:crew "ketch" :create :sometimes})]
        (should (pos? (count errors)))))

    (it "rejects invalid --prefer values"
      (let [errors (sut/validate-select-options {:crew "ketch" :prefer "sideways"})]
        (should= ["--prefer must be recent or oldest"] errors)))

    (it "accepts --prefer recent and oldest"
      (should= [] (sut/validate-select-options {:crew "ketch" :prefer "oldest"}))
      (should= [] (sut/validate-select-options {:session "foo" :prefer "recent"}))))

  (describe "build-select"

    (it "maps CLI options to a select map with prompt defaults"
      (let [select (sut/build-select {:crew "ketch"})]
        (should= "ketch" (:crew select))
        (should= :if-missing (:create select))
        (should= :one (:reach select))
        (should= "prompt-default" (:default-session-key select))))

    (it "normalizes session-tags to keywords"
      (let [select (sut/build-select {:session-tag ["project/chess" "wip"]})]
        (should= #{:project/chess :wip} (:session-tags select))))

    (it "parses --create never|if-missing|always"
      (should= :never (sut/parse-create "never"))
      (should= :if-missing (sut/parse-create "if-missing"))
      (should= :always (sut/parse-create "always")))

    (it "maps --prefer and --resume into the select map"
      (let [select (sut/build-select {:crew "ketch" :prefer "oldest" :resume true})]
        (should= :oldest (:prefer select))
        (should (true? (:resume select))))))

  (describe "build-override"

    (it "maps --with-* flags to behavioral overrides"
      (let [override (sut/build-override {:with-model "opus" :with-crew "ketch" :with-effort 3})]
        (should= "opus" (:model override))
        (should= "ketch" (:crew override))
        (should= 3 (:effort override))))

    (it "accepts legacy -M/--model as a model override"
      (should= "opus" (:model (sut/build-override {:model "opus"}))))))