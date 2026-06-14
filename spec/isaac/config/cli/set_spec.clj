(ns isaac.config.cli.set-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.marigold :as marigold]
    [isaac.config.mutate :as mutate]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:private test-home "/test/config-set")
(def ^:private test-root (str test-home "/.isaac"))

(describe "CLI Config set"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "prints help and returns 0 with set --help"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["set" "--help"])))]
      (should-contain "Usage: isaac config" output)))

  (it "returns 1 when set is missing a path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["set"])))
      (should-contain "missing path" (str err))))

  (it "returns 1 when set is missing a value"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["set" "defaults.crew"])))
      (should-contain "missing value" (str err))))

  (it "treats a hyphen-prefixed token as the set value after the path"
    (let [captured (atom nil)]
      (with-redefs [mutate/set-config (fn [_home path value & _]
                                        (reset! captured [path value])
                                        {:status :ok :warnings [] :file "isaac.edn"})]
        (should= 0 (sut/run {:root test-root} ["set" (str "crew." marigold/first-mate ".soul") "--raw"])))
      (should= [(str "crew." marigold/first-mate ".soul") "--raw"] @captured))))
