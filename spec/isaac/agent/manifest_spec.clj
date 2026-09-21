(ns isaac.agent.manifest-spec
  "isaac-deds: isaac-agent owns the comm berth. One name — :isaac.agent/comm
   — with instance registration wiring; the transport-named duplicates
   (:isaac.http/comm, :isaac.server/comm) are retired."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [speclj.core :refer :all]))

(def manifest
  (edn/read-string (slurp (io/resource "isaac-manifest.edn"))))

(def comm-berth
  (get-in manifest [:berths :isaac.agent/comm]))

(describe "isaac-agent declares the comm berth"

  (it "declares :isaac.agent/comm"
    (should comm-berth))

  (it "wires live comms into the comm registry"
    (should= 'isaac.comm.registry/register-instance!   (:register-fn comm-berth))
    (should= 'isaac.comm.registry/deregister-instance! (:deregister-fn comm-berth)))

  (it "requires each contribution to name its implementing namespace"
    (let [schema (get-in comm-berth [:schema :value-spec :schema])]
      (should= :symbol (get-in schema [:namespace :type]))
      (should= [:present?] (:validations (get-in schema [:namespace])))))

  (it "carries extra-schema and send-schema slots for composed config"
    (let [schema (get-in comm-berth [:schema :value-spec :schema])]
      (should= :schema-map (get-in schema [:extra-schema :type]))
      (should= :schema-map (get-in schema [:send-schema :type]))))

  (it "does not declare transport-named comm berths"
    (should-not (contains? (:berths manifest) :isaac.http/comm))
    (should-not (contains? (:berths manifest) :isaac.server/comm))))
