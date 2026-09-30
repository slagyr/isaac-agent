(ns isaac.agent.manifest-spec
  "isaac-deds: isaac-agent owns the comm berth. One name — :isaac.agent/comm
   — with instance registration wiring; the transport-named duplicates
   (:isaac.http/comm, :isaac.server/comm) are retired."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [speclj.core :refer :all]))

;; Read this repo's manifest by path: on the JVM classpath another
;; module's isaac-manifest.edn can shadow io/resource.
(def manifest
  (edn/read-string (slurp (io/file "resources/isaac-manifest.edn"))))

(def comm-berth
  (get-in manifest [:berths :isaac.agent/comm]))

(def comms-table
  (get-in manifest [:isaac.config/schema :comms]))

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

  (it "lets a comm opt in to attachments with :send-attachments?"
    (let [schema (get-in comm-berth [:schema :value-spec :schema])]
      (should= :boolean (get-in schema [:send-attachments? :type]))))

  (it "does not declare transport-named comm berths"
    (should-not (contains? (:berths manifest) :isaac.http/comm))
    (should-not (contains? (:berths manifest) :isaac.server/comm))))

(describe "isaac-agent declares the :comms config table"

  ;; isaac-6pqo: moved off isaac-http/isaac-server, which only ever
  ;; mirrored agent's own factory/berth.
  (it "declares :comms"
    (should comms-table))

  (it "instantiates slots through its own comm factory"
    (should= 'isaac.comm.factory/create!
             (get-in comms-table [:schema :value-spec :factory])))

  (it "composes extra-schema from its own comm berth"
    (should= {:berth :isaac.agent/comm :path [:extra-schema]}
             (get-in comms-table [:schema :value-spec :dynamic-schema])))

  (it "routes a comm slot into a crew that must exist"
    (should= [:crew-exists?]
             (get-in comms-table [:schema :value-spec :schema :crew :validations]))))
