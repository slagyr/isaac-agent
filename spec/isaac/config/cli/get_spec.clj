(ns isaac.config.cli.get-spec
  (:require
     [c3kit.apron.env :as c3env]
     [clojure.string :as str]
     [isaac.config.cli.common :as common]
     [isaac.config.cli.command :as sut]
     [isaac.config.cli.spec-support :as support]
     [isaac.fs :as fs]
     [isaac.marigold :as marigold]
     [isaac.nexus :as nexus]
     [speclj.core :refer :all])
  (:import (java.io BufferedReader StringReader)))

(def ^:private test-home "/test/config-get")
(def ^:private test-root (str test-home "/.isaac"))
(def ^:private test-provider (keyword marigold/helm-systems))
(def ^:private test-model (keyword marigold/helm-mark-iii))
(def ^:private test-crew (keyword marigold/first-mate))

(defn- api-key-load-result []
  {:config {:providers {test-provider {:api-key "${CONFIG_TEST_API_KEY}"}}}})

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str data))))

(describe "CLI Config get"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env #(do (reset! c3env/-overrides {})
                               (example))))

  (describe "whole config"

    (redefs-around [common/load-raw-result (fn [_] (api-key-load-result))])

    (it "prints the resolved config when no path is given, redacting env values"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 0 (sut/run {:root test-root} ["get"]))
      (should-contain "<CONFIG_TEST_API_KEY:redacted>" (str *out*))
      (should-not-contain "sk-test-123" (str *out*)))

    (it "prints raw config without substitution when --raw is set"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 0 (sut/run {:root test-root} ["get" "--raw"]))
      (should-contain "${CONFIG_TEST_API_KEY}" (str *out*))
      (should-not-contain "redacted" (str *out*)))

    (it "reveals actual values only after typed confirmation"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (binding [*in* (BufferedReader. (StringReader. "REVEAL\n"))]
        (should= 0 (sut/run {:root test-root} ["get" "--reveal"])))
      (should-contain "type REVEAL to confirm:" (str *err*))
      (should-contain "sk-test-123" (str *out*)))

    (it "refuses reveal without typed confirmation"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 1 (sut/run {:root test-root} ["get" "--reveal"]))
      (should-contain "Refusing to reveal config." (str *err*))
      (should-not-contain "sk-test-123" (str *out*))))

  (describe "subtree"

    (it "prints scalar values by dotted path"
      (write-config! (str test-root "/config/isaac.edn")
                     {:crew {test-crew (marigold/crew-cfg marigold/first-mate)}})
      (should= 0 (sut/run {:root test-root} ["get" (str "crew." marigold/first-mate ".soul")]))
      (should-contain "You are Cordelia." (str *out*)))

    (it "prints scalar values by bracket keyword path"
      (write-config! (str test-root "/config/isaac.edn")
                     {:crew {test-crew (marigold/crew-cfg marigold/first-mate)}})
      (should= 0 (sut/run {:root test-root} ["get" (str "crew[:" marigold/first-mate "].soul")]))
      (should-contain "You are Cordelia." (str *out*)))

    (it "returns 1 for a missing key"
      (write-config! (str test-root "/config/isaac.edn")
                     {:crew {test-crew (marigold/crew-cfg marigold/first-mate)}})
      (should= 1 (sut/run {:root test-root} ["get" (str "crew." marigold/first-mate ".nope")]))
      (should-contain (str "not found: crew." marigold/first-mate ".nope") (str *err*)))

    (it "prints nested values across multiple lines"
      (write-config! (str test-root "/config/isaac.edn")
                     {:defaults {:crew (keyword marigold/captain) :model test-model}
                      :crew {(keyword marigold/captain) {}
                             test-crew (marigold/crew-cfg marigold/first-mate :model marigold/helm-mark-iii)}
                      :models {test-model (marigold/model-cfg test-provider "helm-mk-3-1.0")}
                      :providers {test-provider {}}})
      (should= 0 (sut/run {:root test-root} ["get" (str "crew." marigold/first-mate)]))
      (should (<= 2 (count (str/split-lines (str *out*))))))

    (it "prints provider auth when configured"
      (write-config! (str test-root "/config/isaac.edn")
                     {:providers {(keyword marigold/quantum-anvil) {:auth "oauth-device"}}})
      (should= 0 (sut/run {:root test-root} ["get" (str "providers." marigold/quantum-anvil ".auth")]))
      (should-contain "oauth-device" (str *out*)))

    (it "reveals get values after typed confirmation and prompts first"
      (write-config! (str test-root "/config/isaac.edn")
                     {:providers {test-provider {:api-key "${CONFIG_TEST_API_KEY}"}}})
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (binding [*in* (BufferedReader. (StringReader. "REVEAL\n"))]
        (should= 0 (sut/run {:root test-root} ["get" (str "providers." marigold/helm-systems ".api-key") "--reveal"])))
      (should-contain "type REVEAL to confirm:" (str *err*))
      (should-contain "sk-test-123" (str *out*)))))
