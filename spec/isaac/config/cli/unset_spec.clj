(ns isaac.config.cli.unset-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.marigold :as marigold]
    [isaac.config.mutate :as mutate]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:private test-home "/test/config-unset")
(def ^:private test-root (str test-home "/.isaac"))

(describe "CLI Config unset"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "prints help and returns 0 with unset --help"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["unset" "--help"])))]
      (should-contain "Usage: isaac config" output)))

  (it "returns 1 when unset is missing a path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["unset"])))
      (should-contain "missing path" (str err))))

  (it "treats trailing tokens after the path as arguments, not help options"
    (let [captured (atom nil)]
      (with-redefs [mutate/unset-config (fn [_home path]
                                          (reset! captured path)
                                          {:status :ok :warnings [] :file "isaac.edn"})]
        (should= 0 (sut/run {:root test-root} ["unset" (str "crew." marigold/first-mate ".soul") "--help"])))
      (should= (str "crew." marigold/first-mate ".soul") @captured))))
