(ns isaac.marigold.agent
  "Agent half of the Marigold test world: themed LLM/tool/comm manifests,
   api alias registration, and foundation+agent manifest rebinding. Themed
   names and aboard helpers live in foundation's `isaac.marigold`."
  (:require
    [isaac.config.check-contributions :as check-contributions]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.root :as config-schema]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.grover]
    [isaac.marigold :as marigold]
    [isaac.module.loader :as module-loader]
    [isaac.slash.registry :as slash-registry]
    [isaac.tool.registry :as tool-registry]
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
                                                                         :factory 'isaac.tool.registry/register-tool-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/llm-api           {:description "LLM API factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.llm.api.protocol/register-api-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/slash-commands    {:description "Slash commands."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.slash.registry/register-slash-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/provider-template {:description "Provider templates."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type   :map
                                                                         :schema {:template {:type :map}}}}}
             :isaac.agent/provider          {:description "Materialized providers."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type :map}}}}

   :isaac.agent/llm-api {(keyword marigold/helm-api)   {:factory 'isaac.llm.api.grover/make}
                          (keyword marigold/sky-api)    {:factory 'isaac.llm.api.grover/make}
                          (keyword marigold/groves-api) {:factory 'isaac.llm.api.grover/make}
                          (keyword marigold/anvil-api)  {:factory 'isaac.llm.api.grover/make}
                          (keyword marigold/grover-api) {:factory 'isaac.llm.api.grover/make}}

   :isaac.agent/provider-template {(keyword marigold/helm-systems)  {:template (dissoc marigold/helm-provider :api-key)}
                                    (keyword marigold/starcore)      {:template (dissoc marigold/starcore-provider :api-key)}
                                    (keyword marigold/flicker-labs)  {:template marigold/flicker-provider}
                                    (keyword marigold/quantum-anvil) {:template marigold/quantum-provider}
                                    (keyword marigold/grover-stub)   {:template {:api marigold/grover-api :auth "none"}}}

   :isaac.agent/tools {(keyword marigold/spyglass-tool) {:factory 'isaac.tool.builtin/read-tool-factory}
                        (keyword marigold/sextant-tool)  {:factory 'isaac.tool.builtin/grep-tool-factory}
                        (keyword marigold/signal-flare)  {:factory 'isaac.tool.builtin/web-search-tool-factory}}

   :isaac.agent/slash-commands {(keyword marigold/heading-command) {:factory 'isaac.marigold/heading-slash-factory}
                                 (keyword marigold/bearing-command) {:factory 'isaac.marigold/bearing-slash-factory}
                                 (keyword marigold/muster-command)  {:factory 'isaac.marigold/muster-slash-factory}}

   :isaac.server/comm {(keyword marigold/longwave) {:namespace 'isaac.marigold-comms}
                        (keyword marigold/skybeam)  {:namespace 'isaac.marigold-comms}
                        (keyword marigold/logbook)  {:namespace 'isaac.marigold-comms}}

   :isaac.config/schema (select-keys config-schema/contributions agent-schema-keys)
   :isaac.config/check  check-contributions/server})

(def baseline-manifest baseline-agent-manifest)

(def ^:private baseline-foundation-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :isaac.agent      {:coord {} :manifest baseline-agent-manifest :path nil}})

(defn register-grover-test-fixture!
  []
  (isaac.llm.api.grover/install-test-fixture!))

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
    (binding [module-loader/*foundation-index-override* baseline-foundation-index]
      (schema-compose/clear-cache!)
      (reset-extension-registries!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!)
          (reset-extension-registries!))))))

(defn with-real-manifest*
  [thunk]
  (binding [module-loader/*foundation-index-override* nil]
    (reset-extension-registries!)
    (module-loader/activate-foundation!)
    (register-grover-test-fixture!)
    (thunk)))

(defmacro with-real-manifest
  [& body]
  `(with-real-manifest* (fn [] ~@body)))