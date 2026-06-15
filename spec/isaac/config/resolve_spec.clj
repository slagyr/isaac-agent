(ns isaac.config.resolve-spec
  (:require
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.config.resolve :as sut]
    [isaac.fs :as fs]
    [isaac.llm.provider :as llm-provider]
    [speclj.core :refer :all]))

(describe "config resolve"

  (describe "resolve-history-retention"

    (it "defaults to retain"
      (should= :retain (sut/resolve-history-retention {} "main" nil)))

    (it "prefers explicit override over crew model provider and defaults"
      (should= :retain
               (sut/resolve-history-retention {:defaults  {:history-retention :prune}
                                              :crew      {"main" {:model "gpt" :history-retention :prune}}
                                              :models    {"gpt" {:provider marigold/starcore :history-retention :prune}}
                                              :providers {marigold/starcore {:history-retention :prune}}}
                                             "main"
                                             :retain)))

    (it "falls through crew model provider defaults in order"
      (should= :prune
               (sut/resolve-history-retention {:defaults  {:history-retention :retain}
                                              :crew      {"main" {:model "gpt" :history-retention :prune}}
                                              :models    {"gpt" {:provider marigold/starcore :history-retention :retain}}
                                              :providers {marigold/starcore {:history-retention :retain}}}
                                             "main"
                                             nil))
      (should= :prune
               (sut/resolve-history-retention {:defaults  {:history-retention :retain}
                                              :crew      {"main" {:model "gpt"}}
                                              :models    {"gpt" {:model "gpt-5" :provider marigold/starcore :history-retention :prune}}
                                              :providers {marigold/starcore {:history-retention :retain}}}
                                             "main"
                                             nil))
      (should= :prune
               (sut/resolve-history-retention {:defaults  {:history-retention :retain}
                                              :crew      {"main" {:model "gpt"}}
                                              :models    {"gpt" {:model "gpt-5" :provider marigold/starcore}}
                                              :providers {marigold/starcore {:history-retention :prune}}}
                                             "main"
                                             nil))))

  (describe "resolve-crew-context model override"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

    (it "uses a named model override passed in the crew config"
      (with-redefs [llm-provider/make-provider (fn [provider-id provider-cfg]
                                                 {:id provider-id :cfg provider-cfg})]
        (let [cfg {:module-index {:source :spec}
                   :crew         {"main" {:model "grover"}}
                   :models       {"grover" {:model                  "helm-mk-3-1.0"
                                             :provider               marigold/helm-systems
                                             :context-window         200000
                                             :enforce-context-window true
                                             :thinking-budget-max    7
                                             :think-mode             :deep}}
                   :providers    {marigold/helm-systems {:api marigold/helm-api
                                                         :base-url (:base-url marigold/helm-provider)}}}
              ctx (sut/resolve-crew-context cfg "main")]
          (should= "helm-mk-3-1.0" (:model ctx))
          (should= {:model "helm-mk-3-1.0"
                    :provider marigold/helm-systems
                    :context-window 200000
                    :thinking-budget-max 7
                    :think-mode :deep}
                   (select-keys (:model-cfg ctx) [:model :provider :context-window :thinking-budget-max :think-mode]))
          (should= {:api marigold/helm-api
                    :base-url (:base-url marigold/helm-provider)}
                   (select-keys (:provider-cfg ctx) [:api :base-url]))
          (should= {:id marigold/helm-systems}
                   (select-keys (:provider ctx) [:id]))
          (should= {:api marigold/helm-api
                    :base-url (:base-url marigold/helm-provider)
                    :module-index {:source :spec}
                    :thinking-budget-max 7
                    :think-mode :deep}
                   (select-keys (get-in ctx [:provider :cfg]) [:api :base-url :module-index :thinking-budget-max :think-mode]))
          (should= 200000 (:context-window ctx)))))

    (it "accepts provider slash model refs in the crew config"
      (with-redefs [llm-provider/make-provider (fn [provider-id provider-cfg]
                                                 {:id provider-id :cfg provider-cfg})]
        (let [cfg {:module-index {:source :spec}
                   :crew         {"main" {:model (str marigold/helm-systems "/helm-mk-3-1.0")}}
                   :providers    {marigold/helm-systems {:api marigold/helm-api
                                                         :context-window 64000}}}
              ctx (sut/resolve-crew-context cfg "main")]
          (should= "helm-mk-3-1.0" (:model ctx))
          (should= {:provider marigold/helm-systems
                    :model "helm-mk-3-1.0"}
                   (:model-cfg ctx))
          (should= {:api marigold/helm-api
                    :context-window 64000}
                   (select-keys (:provider-cfg ctx) [:api :context-window]))
          (should= {:id marigold/helm-systems}
                   (select-keys (:provider ctx) [:id]))
          (should= {:api marigold/helm-api
                    :context-window 64000
                    :module-index {:source :spec}}
                   (select-keys (get-in ctx [:provider :cfg]) [:api :context-window :module-index]))
          (should= 64000 (:context-window ctx))))))

  (describe "resolve-crew-context"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

    (it "resolves crew model provider and context window from the new map-by-id shape"
      (with-redefs [llm-provider/make-provider (fn [provider-id provider-cfg]
                                                 {:id provider-id :cfg provider-cfg})]
        (let [cfg {:defaults  {:crew "main" :model "llama"}
                   :crew      {"main" {:model "grover" :soul "You are Isaac."}}
                   :models    {"grover" {:model "helm-mk-3-1.0" :provider marigold/helm-systems :context-window 200000}}
                   :providers {marigold/helm-systems {:api marigold/helm-api :base-url (:base-url marigold/helm-provider)}}}
              ctx (sut/resolve-crew-context cfg "main")]
          (should= "You are Isaac." (:soul ctx))
          (should= "helm-mk-3-1.0" (:model ctx))
          (should= marigold/helm-systems (:id (:provider ctx)))
          (should= 200000 (:context-window ctx))
          (should= (:base-url marigold/helm-provider) (get-in ctx [:provider :cfg :base-url])))))

    (it "returns crew-cfg and model-cfg for effort resolution"
      (with-redefs [llm-provider/make-provider (fn [provider-id provider-cfg]
                                                 {:id provider-id :cfg provider-cfg})]
        (let [cfg {:defaults  {:crew "main" :model "snuffy"}
                   :crew      {"main" {:model "snuffy" :effort 9}}
                   :models    {"snuffy" {:model "snuffy-codex" :provider "grover" :effort 5}}
                   :providers {"grover" {:api "responses" :effort 3}}}
              ctx (sut/resolve-crew-context cfg "main")]
          (should= 9 (get-in ctx [:crew-cfg :effort]))
          (should= 5 (get-in ctx [:model-cfg :effort]))))))

  (describe "resolve-provider"

    (it "falls back from simulated provider ids to the base provider config"
      (let [cfg {:providers {marigold/grover-api {:api marigold/grover-api :effort 3}}}]
        (should= {:api marigold/grover-api :effort 3}
                 (sut/resolve-provider cfg (str marigold/grover-api ":" marigold/quantum-anvil)))))))