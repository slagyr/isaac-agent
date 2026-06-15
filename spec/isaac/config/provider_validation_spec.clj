(ns isaac.config.provider-validation-spec
  (:require
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "config provider validation"

  (marigold.agent/with-manifest)
  (marigold.agent/with-apis)

  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "rejects providers with an unknown api"
    (marigold/write-config!
      {:providers {:bogus {:api      "carrier-pigeon"
                           :base-url "https://example.com"
                           :auth     "api-key"
                           :api-key  "test"}}})
    (let [result      (marigold/load-config)
          known-apis  (->> marigold.agent/baseline-manifest :isaac.agent/llm-api keys (map name) sort vec)
          expected-msg (str "must be one of " known-apis)]
      (should= [{:key          "providers.bogus.api"
                 :value        expected-msg
                 :bad-value    "carrier-pigeon"
                 :valid-values known-apis}]
               (mapv #(select-keys % [:key :value :bad-value :valid-values])
                     (filter #(= "providers.bogus.api" (:key %)) (:errors result))))))

  (it "rejects providers with an unknown :type target"
    (marigold/write-config!
      {:providers {:dreamy {:type :ghost-provider :api-key "test"}}})
    (let [result           (marigold/load-config)
          known-providers  (->> marigold.agent/baseline-manifest :isaac.agent/provider-template keys (map name) sort vec)
          expected-msg     (str "must be one of " known-providers)]
      (should= [{:key          "providers.dreamy.type"
                 :value        expected-msg
                 :bad-value    "ghost-provider"
                 :valid-values known-providers}]
               (mapv #(select-keys % [:key :value :bad-value :valid-values])
                     (filter #(= "providers.dreamy.type" (:key %)) (:errors result)))))))
