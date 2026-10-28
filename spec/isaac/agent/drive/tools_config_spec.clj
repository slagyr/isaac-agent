(ns isaac.agent.drive.tools-config-spec
  (:require
    [isaac.agent.drive.tools-config :as sut]
    [isaac.agent.llm.tool-loop :as tool-loop]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.tool.registry :as tool-registry]
    [speclj.core :refer [describe it should=]]))

(defn lens-provider
  "Tool-provider fixture: registers the lens namespace on demand."
  [ns-str _module-index]
  (when (= "lens" ns-str)
    (tool-registry/register! {:name "lens__catalog" :description "Catalog" :parameters {} :handler (fn [_] {:result "ok"})})
    (tool-registry/register! {:name "lens__read" :description "Read" :parameters {} :handler (fn [_] {:result "ok"})})
    ["lens__catalog" "lens__read"]))

(describe "cycle resolvers"
  (it "prefers the charge cycle limit over the crew and the defaults"
    (should= 12 (#'sut/resolve-cycle-limit {:cycle    {:limit 12}
                                            :crew-cfg {:cycle {:limit 3}}
                                            :crew     "oscar"
                                            :config   {:defaults {:crew {:cycle {:limit 5}}}
                                                       :crew     {"oscar" {:cycle {:limit 3}}}}}))
    (should= tool-loop/default-max-loops
             (#'sut/resolve-cycle-limit {:crew "oscar" :config {}})))

  (it "layers a charge cycle map over the crew and the defaults"
    (let [cycle (#'sut/resolve-cycle {:cycle    {:checkpoint-every 1}
                                      :crew-cfg {:cycle {:limit 10 :checkpoint-every 5}}
                                      :crew     "oscar"
                                      :config   {:defaults {:crew {:cycle {:limit 100}}}}})]
      (should= 10 (:limit cycle))
      (should= 1 (:checkpoint-every cycle))))

  (it "defaults continuations to 2 and still layers a crew value"
    (should= 2 sut/default-continuations)
    (should= sut/default-continuations
             (:continuations (#'sut/resolve-cycle {:crew "oscar" :config {}})))
    (should= 5 (:continuations (#'sut/resolve-cycle {:crew   "oscar"
                                                     :config {:defaults {:crew {:cycle {:continuations 5}}}}})))
    (should= 4 (:continuations (#'sut/resolve-cycle {:crew   "oscar"
                                                     :config {:defaults {:crew {:cycle {:continuations "4"}}}}}))))

  (it "reads max-parallel and tool timeout from the crew, then defaults"
    (should= 3 (#'sut/resolve-max-parallel-tools {:crew-cfg {:tools {:max-parallel 3}}
                                                  :crew "oscar"
                                                  :config {}}))
    (should= 2500 (#'sut/resolve-tool-timeout-ms {:crew-cfg {:tools {:timeout-ms "2500"}}
                                                  :crew "oscar"
                                                  :config {}}))))

(describe "allowed tool names"
  (it "a ns/* allow makes the provider's tools candidates for the cascade"
    (nexus/-with-nexus {:tool-registry (atom {})}
      (let [crew-members {"main" {:tools {:allow [:lens/*]}}}
            config       {:module-index {:isaac.tool.lens {:manifest {:isaac.agent/tool-providers {:lens {:ensure! 'isaac.agent.drive.tools-config-spec/lens-provider}}}}}}]
        (should= #{"lens__catalog" "lens__read"}
                 (#'sut/allowed-tool-names crew-members "main" config)))))

  (it "a glob allow without a module index offers nothing"
    (nexus/-with-nexus {:tool-registry (atom {})}
      (should= #{} (#'sut/allowed-tool-names {"main" {:tools {:allow [:lens/*]}}} "main" {})))))
