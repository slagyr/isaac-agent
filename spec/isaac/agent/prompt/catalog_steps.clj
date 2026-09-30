(ns isaac.agent.prompt.catalog-steps
  (:require
    [gherclj.core :as g :refer [defthen defwhen helper!]]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.prompt.catalog :as catalog]
    [isaac.agent.session.store.spi :as store]))

(helper! isaac.agent.prompt.catalog-steps)

(defn- root []
  (or (g/get :runtime-root)
      (g/get :root)))

(defn- feature-fs []
  (or (g/get :mem-fs)
      (nexus/get :fs)
      (fs/real-fs)))

(defn- current-config []
  (:config (loader/load-config-result {:root (root)
                                       :fs        (feature-fs)})))

(defn- session-cwd [session-key]
  (let [store* (store/registered-store)]
    (some-> store*
            (store/get-session session-key)
            :cwd)))

(defn- resolve-catalog! [opts]
  (g/assoc! :prompt-catalog
            (catalog/resolve-catalog (merge {:config    (current-config)
                                             :fs        (feature-fs)
                                             :root (root)}
                                            opts))))

(defn prompt-catalog-resolved []
  (resolve-catalog! {}))

(defn prompt-catalog-for-session-resolved [session-key]
  (resolve-catalog! {:cwd (session-cwd session-key)}))

(defn- row-map [table row]
  (zipmap (:headers table) row))

(defn- entry-for [catalog type name]
  (get-in catalog [(keyword (str type "s")) name]))

(defn prompt-catalog-contains [table]
  (let [catalog (g/get :prompt-catalog)]
    (doseq [row (:rows table)]
      (let [{:strs [description name type]} (row-map table row)
            prompt-name                    name
            entry                          (entry-for catalog type prompt-name)]
        (g/should-not-be-nil entry)
        (g/should= prompt-name (:name entry))
        (g/should= type (clojure.core/name (:type entry)))
        (when description
          (g/should= description (:description entry)))))))

(defwhen "the prompt catalog is resolved" isaac.agent.prompt.catalog-steps/prompt-catalog-resolved)

(defwhen "the prompt catalog for session {string} is resolved" isaac.agent.prompt.catalog-steps/prompt-catalog-for-session-resolved)

(defthen "the prompt catalog contains:" isaac.agent.prompt.catalog-steps/prompt-catalog-contains)
