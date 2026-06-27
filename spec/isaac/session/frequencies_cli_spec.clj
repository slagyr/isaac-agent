(ns isaac.session.frequencies-cli-spec
  (:require
    [isaac.session.frequencies-cli :as sut]
    [speclj.core :refer :all]))

(describe "session frequencies CLI"

  (describe "validate-frequencies-options"

    (it "rejects --session combined with --crew"
      (let [errors (sut/validate-frequencies-options {:session "bridge" :crew "main"})]
        (should (pos? (count errors)))
        (should (re-find #"--session" (first errors)))))

    (it "rejects --session combined with --session-tag"
      (let [errors (sut/validate-frequencies-options {:session "bridge" :session-tag ["wip"]})]
        (should (pos? (count errors)))))

    (it "rejects --session combined with --create"
      (let [errors (sut/validate-frequencies-options {:session "bridge" :create :always})]
        (should (pos? (count errors)))))

    (it "accepts describe flags together"
      (should= [] (sut/validate-frequencies-options {:crew "ketch" :session-tag ["wip"]})))

    (it "rejects --resume combined with --session"
      (let [errors (sut/validate-frequencies-options {:resume true :session "bridge"})]
        (should (pos? (count errors)))))

    (it "rejects invalid --create values"
      (let [errors (sut/validate-frequencies-options {:crew "ketch" :create :sometimes})]
        (should (pos? (count errors)))))

    (it "rejects invalid --prefer values"
      (let [errors (sut/validate-frequencies-options {:crew "ketch" :prefer "sideways"})]
        (should= ["--prefer must be recent or oldest"] errors)))

    (it "accepts --prefer recent and oldest"
      (should= [] (sut/validate-frequencies-options {:crew "ketch" :prefer "oldest"}))
      (should= [] (sut/validate-frequencies-options {:session "foo" :prefer "recent"}))))

  (describe "build-frequencies"

    (it "maps CLI options to a frequencies map with prompt defaults"
      (let [frequencies (sut/build-frequencies {:crew "ketch"})]
        (should= "ketch" (:crew frequencies))
        (should= :if-missing (:create frequencies))
        (should= :one (:reach frequencies))
        (should= "prompt-default" (:default-session-key frequencies))))

    (it "normalizes session-tags to keywords"
      (let [frequencies (sut/build-frequencies {:session-tag ["project/chess" "wip"]})]
        (should= #{:project/chess :wip} (:session-tags frequencies))))

    (it "parses --create never|if-missing|always"
      (should= :never (sut/parse-create "never"))
      (should= :if-missing (sut/parse-create "if-missing"))
      (should= :always (sut/parse-create "always")))

    (it "maps --prefer and --resume into the frequencies map"
      (let [frequencies (sut/build-frequencies {:crew "ketch" :prefer "oldest" :resume true})]
        (should= :oldest (:prefer frequencies))
        (should (true? (:resume frequencies))))))

  (describe "build-override"

    (it "maps --with-* flags to :with-* keys on the frequencies map"
      (let [override (sut/build-override {:with-model "opus" :with-crew "ketch" :with-effort 3})]
        (should= "opus" (:with-model override))
        (should= "ketch" (:with-crew override))
        (should= 3 (:with-effort override))))

    (it "accepts legacy -M/--model as a model override"
      (should= "opus" (:with-model (sut/build-override {:model "opus"}))))))