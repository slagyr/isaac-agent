(ns isaac.agent.comm.registry-spec
  (:require
    [isaac.agent.comm.registry :as sut]
    [speclj.core :refer :all]))

(describe "comm registry"

  (around [it]
    (binding [sut/*registry* (atom (sut/fresh-registry))]
      (it)))

  (it "registers and returns a factory"
    (let [factory (fn [_] ::instance)]
      (sut/register-factory! "telly" factory)
      (should= factory (sut/factory-for :telly)))))

(describe "session attachments"
  (around [it]
    (binding [sut/*registry* (atom (sut/fresh-registry))]
      (it)))

  (it "keeps an attachment until its own client detaches"
    (let [first-client (Object.)
          second-client (Object.)]
      (sut/attach-session! "scout" first-client)
      (sut/attach-session! "scout" second-client)
      (sut/detach-session! "scout" first-client)
      (should= second-client (sut/attached-comm "scout"))
      (sut/detach-session! "scout" second-client)
      (should-be-nil (sut/attached-comm "scout"))))

  (it "does not leak attachments between registries"
    (sut/attach-session! "scout" :client)
    (should= :client (sut/attached-comm "scout"))))
