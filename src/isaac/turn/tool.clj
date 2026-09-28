(ns isaac.turn.tool
  "Inspect a durable turn by id for an allowed crew."
  (:require
    [cheshire.core :as json]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.tool.fs-bounds :as bounds]
    [isaac.turn.store :as store]))

(defn get-tool [args]
  (let [id     (get (bounds/string-key-map args) "id")
        root   (or (nexus/get :root) (loader/root))
        record (when (and (string? id) (seq id))
                 (store/read-turn (store/file-store (or (nexus/get :fs) (fs/instance)) root) id))]
    (if record
      {:result (json/generate-string record)}
      {:isError true :error (str "turn not found: " id)})))
