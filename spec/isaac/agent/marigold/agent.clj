(ns isaac.agent.marigold.agent
  "Agent half of the Marigold test world: themed LLM/tool/comm manifests,
   api alias registration, and foundation+agent manifest rebinding. Themed
   names and aboard helpers live in foundation's `isaac.foundation.marigold`."
  (:require
    [clojure.edn :as edn]
    [isaac.agent.config.check-contributions :as check-contributions]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.agent.config.schema.root :as config-schema]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.llm.api.grover]
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.module.loader :as module-loader]
    [isaac.agent.slash.registry :as slash-registry]
    [isaac.agent.tool.registry :as tool-registry]
    [speclj.core :as speclj]))

(def ^:private agent-schema-keys
  #{:command-paths :comms :crew :defaults :models :prefer-entity-files
    :prompt-dir-names :prompt-paths :providers :sessions :skill-menu-threshold
    :skill-paths :tools})

(def baseline-agent-manifest
  {:id       :isaac.agent
   :version  "0.1.0"
   :builtin? true
   :factory  'isaac.agent.module/create-module

   :berths  {:isaac.agent/tools             {:description "LLM tool factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.agent.tool.registry/register-tool-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/llm-api           {:description "LLM API factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.agent.llm.api.protocol/register-api-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/slash-commands    {:description "Slash commands."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.agent.slash.registry/register-slash-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/provider-template {:description "Provider templates."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type   :map
                                                                         :schema {:template {:type :map}}}}}
             :isaac.agent/provider          {:description "Materialized providers."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type :map}}}
             :isaac.agent/resource-pool-types        {:description "Named resource-pool factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.agent.pool/register-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}}

   :isaac.agent/llm-api {(keyword marigold/helm-api)   {:factory 'isaac.agent.llm.api.grover/make}
                          (keyword marigold/sky-api)    {:factory 'isaac.agent.llm.api.grover/make}
                          (keyword marigold/groves-api) {:factory 'isaac.agent.llm.api.grover/make}
                          (keyword marigold/anvil-api)  {:factory 'isaac.agent.llm.api.grover/make}
                          (keyword marigold/grover-api) {:factory 'isaac.agent.llm.api.grover/make}}

   :isaac.agent/provider-template {(keyword marigold/helm-systems)  {:template (dissoc marigold/helm-provider :api-key)}
                                    (keyword marigold/starcore)      {:template (dissoc marigold/starcore-provider :api-key)}
                                    (keyword marigold/flicker-labs)  {:template marigold/flicker-provider}
                                    (keyword marigold/quantum-anvil) {:template marigold/quantum-provider}
                                    (keyword marigold/grover-stub)   {:template {:api marigold/grover-api :auth "none"}}}

   :isaac.agent/tools {(keyword marigold/spyglass-tool) {:factory 'isaac.agent.tool.builtin/read-tool-factory}
                        (keyword marigold/sextant-tool)  {:factory 'isaac.agent.tool.builtin/grep-tool-factory}
                        (keyword marigold/signal-flare)  {:factory 'isaac.agent.tool.builtin/web-search-tool-factory}}

   :isaac.agent/slash-commands {(keyword marigold/heading-command) {:factory 'isaac.foundation.marigold/heading-slash-factory}
                                 (keyword marigold/bearing-command) {:factory 'isaac.foundation.marigold/bearing-slash-factory}
                                 (keyword marigold/muster-command)  {:factory 'isaac.foundation.marigold/muster-slash-factory}}

   :isaac.agent/comm {(keyword marigold/longwave) {:namespace 'isaac.agent.marigold-comms}
                        (keyword marigold/skybeam)  {:namespace 'isaac.agent.marigold-comms}
                        (keyword marigold/logbook)  {:namespace 'isaac.agent.marigold-comms}}

   :isaac.config/schema
   (update-in (select-keys config-schema/contributions agent-schema-keys)
              [:crew :schema :value-spec :schema :max-in-flight]
              (constantly {:type :ignore
                           :validations [[:retired? "the crew-wide in-flight cap is gone (isaac-ximd); turns serialize per session only"]]}))
   :isaac.config/check  check-contributions/server
   :isaac.config/validation-ref
   {:crew-exists? {:known 'isaac.agent.config.checks/known-crew-ids
                   :message "references undefined crew"}
    :model-exists? {:known 'isaac.agent.config.checks/known-model-ids+aliases
                    :message "references undefined model"}}})

(def baseline-manifest baseline-agent-manifest)

(def ^:private baseline-foundation-index
  {:isaac.foundation {:coord    {}
                      :manifest (assoc-in marigold/baseline-foundation-manifest
                                          [:berths :isaac/component]
                                          (get-in (some-> (discovery/manifest-resource :isaac.foundation)
                                                          slurp
                                                          edn/read-string)
                                                  [:berths :isaac/component]))
                      :path     nil}
   :isaac.agent      {:coord {} :manifest baseline-agent-manifest :path nil}})

(defn register-grover-test-fixture!
  []
  (isaac.agent.llm.api.grover/install-test-fixture!))

(defn register-apis!
  []
  (register-grover-test-fixture!)
  (let [grover-factory (api/factory-for :grover)]
    (api/register! (keyword marigold/helm-api)   grover-factory)
    (api/register! (keyword marigold/sky-api)    grover-factory)
    (api/register! (keyword marigold/groves-api) grover-factory)
    (api/register! (keyword marigold/anvil-api)  grover-factory)))

(defn with-apis
  []
  (speclj/before-all (register-apis!)))

(defn- reset-extension-registries! []
  (slash-registry/clear!)
  (tool-registry/clear!)
  (module-loader/clear-activations!))

(defn with-manifest
  []
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-foundation-index
              discovery/*foundation-index-override*     baseline-foundation-index]
      (schema-compose/clear-cache!)
      (reset-extension-registries!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!)
          (reset-extension-registries!))))))

(defn with-real-manifest*
  [thunk]
  (binding [module-loader/*foundation-index-override* nil
            discovery/*foundation-index-override*     nil]
    (reset-extension-registries!)
    (module-loader/activate-foundation!)
    (register-grover-test-fixture!)
    (thunk)))

(defmacro with-real-manifest
  [& body]
  `(with-real-manifest* (fn [] ~@body)))