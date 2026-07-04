(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.config.schema.root :as sut]
    [isaac.config.validation-lexicon :as validation-lexicon]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold-agent]
    [isaac.module.loader :as module-loader]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(def ^:private test-model-id marigold/helm-mark-iii)
(def ^:private test-provider-id marigold/helm-systems)

(defn- runtime-spec [spec]
  (sut/strip-validation-annotations spec))

(defn- with-config-schema-bindings [f]
  (binding [registered-in/*module-index*      (module-loader/builtin-index)
            validation-lexicon/*config* {:crew      {"main" {}}
                                         :models    {test-model-id {}}
                                         :providers {test-provider-id {}}}]
    (f)))

(describe "config schema"

  (marigold-agent/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (with-config-schema-bindings example))

  (describe "entity conformance"

    (it "defaults conforms keyword ids to strings"
      (should= {:crew "main" :model test-model-id}
               (lexicon/conform (runtime-spec sut/defaults)
                                {:crew :main :model (keyword test-model-id)})))

    (it "crew conforms with tools nested"
      (should= {:id    marigold/first-mate
                :model test-model-id
                :soul  "You are Cordelia."
                :tools {:allow       [:read :write]
                        :directories [:cwd "/tmp/playground"]}}
               (lexicon/conform (runtime-spec sut/crew)
                                {:id    (keyword marigold/first-mate)
                                 :model (keyword test-model-id)
                                 :soul  "You are Cordelia."
                                 :tools {:allow       [:read :write]
                                         :directories [:cwd "/tmp/playground"]}})))

    (it "crew conforms with context-mode"
      (should= {:context-mode :reset
                :model        test-model-id}
               (lexicon/conform (runtime-spec sut/crew)
                                {:context-mode :reset
                                 :model        (keyword test-model-id)})))

    (it "crew conforms with max-in-flight"
      (should= {:max-in-flight 3}
               (lexicon/conform (runtime-spec sut/crew)
                                {:max-in-flight 3})))

    (it "crew conforms with tags"
      (should= {:tags #{:project/chess :role/worker}}
               (lexicon/conform (runtime-spec sut/crew)
                                {:tags #{:role/worker :project/chess}})))

    (it "model conforms with all required + optional fields"
      (should= {:id test-model-id :model marigold/helm-mark-iii :provider test-provider-id :context-window 128000}
               (lexicon/conform (runtime-spec sut/model)
                                {:id             (keyword test-model-id)
                                 :model          marigold/helm-mark-iii
                                 :provider       (keyword test-provider-id)
                                 :context-window 128000})))

    (it "provider conforms including string-to-string headers"
      (should= {:base-url "https://api" :api marigold/helm-api :auth "oauth-device" :headers {"X-Foo" "bar"}}
               (lexicon/conform (runtime-spec sut/provider)
                                {:base-url "https://api"
                                 :api      marigold/helm-api
                                 :auth     "oauth-device"
                                 :headers  {"X-Foo" "bar"}}))))

  (describe "custom validation"

    (it "tools directories rejects a keyword other than :cwd or :role"
      (let [result (lexicon/conform sut/tools {:directories [:not-cwd]})]
        (should (schema/error? result))))

    (it "tools directories accepts :role"
      (let [result (lexicon/conform sut/tools {:directories [:role]})]
        (should-not (schema/error? result))))

    (it "tools directories rejects non-keyword non-string entries"
      (let [result (lexicon/conform sut/tools {:directories [42]})]
        (should (schema/error? result))))

    (it "crew accepts an absolute cwd path"
      (let [result (lexicon/conform (runtime-spec sut/crew) {:cwd "/lab/world-domination"})]
        (should-not (schema/error? result))
        (should= "/lab/world-domination" (:cwd result))))

    (it "crew rejects a relative cwd path"
      (let [result (lexicon/conform sut/crew {:cwd "cheese-helmets"})]
        (should (schema/error? result))
        (should= "must be an absolute path"
                 (get-in (schema/message-map result) [:cwd]))))

    (it "crew allows nil cwd"
      (let [result (lexicon/conform (runtime-spec sut/crew) {})]
        (should-not (schema/error? result))
        (should-be-nil (:cwd result))))

    (it "crew rejects unknown context-mode values"
      (let [result (lexicon/conform sut/crew {:context-mode :ponder})]
        (should (schema/error? result))
        (should= "must be one of :full, :reset"
                 (get-in (schema/message-map result) [:context-mode]))))

    (it "crew rejects non-positive max-in-flight"
      (let [result (lexicon/conform sut/crew {:max-in-flight 0})]
        (should (schema/error? result))
        (should= "must be a positive integer"
                 (get-in (schema/message-map result) [:max-in-flight]))))

    (it "crew rejects non-keyword tags"
      (let [result (lexicon/conform sut/crew {:tags #{"worker"}})]
        (should (schema/error? result))
        (should= "must be a set of keywords"
                 (get-in (schema/message-map result) [:tags]))))))
