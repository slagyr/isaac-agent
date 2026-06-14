(ns isaac.comm.registry-spec
  (:require
    [isaac.comm.registry :as sut]
    [speclj.core :refer :all]))

(describe "comm registry"

  (around [it]
    (binding [sut/*registry* (atom (sut/fresh-registry))]
      (it)))

  (it "registers and returns a factory"
    (let [factory (fn [_] ::instance)]
      (sut/register-factory! "telly" factory)
      (should= factory (sut/factory-for :telly)))))
