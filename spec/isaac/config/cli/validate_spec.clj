(ns isaac.config.cli.validate-spec
  (:require
     [isaac.config.cli.command :as sut]
     [isaac.config.cli.spec-support :as support]
     [isaac.fs :as fs]
     [isaac.marigold :as marigold]
     [isaac.nexus :as nexus]
     [speclj.core :refer :all])
  (:import (java.io BufferedReader StringReader)))

(def ^:private test-home "/test/config-validate")
(def ^:private test-root (str test-home "/.isaac"))
(def ^:private test-crew (keyword marigold/captain))

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str data))))

(describe "CLI Config validate"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "fails clearly when no config exists"
    (should= 1 (sut/run {:root test-root} ["validate"]))
    (should-contain "no config found" (str *err*)))

  (it "prints OK and returns 0 when validation passes"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults {:crew test-crew :model :llama}
                    :crew {test-crew {:soul "You are Atticus."}}
                    :models {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["validate"]))
    (should-contain "OK" (str *out*)))

  (it "returns 1 and prints errors when validation fails"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults {:crew :ghost :model :llama}})
    (should= 1 (sut/run {:root test-root} ["validate"]))
    (should-contain "defaults.crew" (str *err*)))

  (it "overlays stdin content at a data path when validating"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults  {:crew test-crew :model :llama}
                    :crew      {}
                    :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (binding [*in* (BufferedReader. (StringReader. "{:soul \"You are Atticus.\"}"))]
      (let [result (sut/run {:root test-root} ["validate" "--as" (str "crew." marigold/captain) "-"])]
        (should= 0 result))
      (should-contain "OK" (str *out*)))))
