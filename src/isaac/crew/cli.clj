(ns isaac.crew.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as cli]
    [isaac.cli.common :as cli-common]
    [isaac.cli.table :as table]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.crew.store :as store]
    [isaac.fs :as fs]))

(def option-spec
  [[nil  "--json" "Output result as JSON"]
   [nil  "--edn"  "Output result as EDN"]
   [nil  "--tag TAG" "Filter to crews carrying this tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil  "--without-tag TAG" "Exclude crews carrying this tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil  "--untagged" "Show only crews with no tags"]
   ["-h" "--help" "Show help"]])

(defn- text-tags [tags]
  (if (seq tags)
    (->> tags (sort-by str) (map pr-str) (str/join " "))
    ""))

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options (remove (comp nil? val)) (into {}))
     :errors  errors}))

(defn- parse-with-arguments [raw-args]
  (let [{:keys [options arguments errors]}
        (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options (remove (comp nil? val)) (into {}))
     :arguments arguments
     :errors  errors}))

(defn- soul-source [crew-cfg]
  (when-let [s (:soul crew-cfg)]
    (let [oneline (-> s (str/replace #"\s+" " ") str/trim)]
      (if (> (count oneline) 40)
        (str (subs oneline 0 37) "...")
        oneline))))

(defn- derive-root [opts]
  (root/default-root opts))

(defn resolve-crew
  "Returns a seq of {:name :model :provider :soul :soul-source :tags}."
  [opts]
  (let [{:keys [crew models]} opts
        root      (derive-root opts)
        cfg       (if crew
                    (cli-common/build-cfg crew models)
                    (loader/load-config! root (fs/instance) "crew cli"))
        cfg       (loader/normalize-config cfg)
        crew-map  (cond-> (:crew cfg)
                    (not (contains? (:crew cfg) "main")) (assoc "main" {}))]
    (map (fn [[crew-id crew-member]]
           (let [model-id   (or (:model crew-member) (get-in cfg [:defaults :model]))
                 model-cfg  (get-in cfg [:models model-id])
                 model-name (or (:model model-cfg) model-id "-")
                 provider   (or (:provider model-cfg) "-")]
             {:name        crew-id
              :model       model-name
              :provider    provider
              :soul        (:soul crew-member)
              :tags        (or (:tags crew-member) #{})
              :soul-source (soul-source crew-member)}))
         crew-map)))

(defn- resolve-crew-by-name [opts crew-id]
  (some #(when (= crew-id (:name %)) %) (resolve-crew opts)))

(defn- effective-color? [opts]
  (cond
    (:no-color opts)            false
    (= "always" (:color opts))  true
    (= "never"  (:color opts))  false
    :else                       nil))

(defn format-crew
  ([rows] (format-crew rows {}))
  ([rows opts]
   (table/render {:columns [{:key :name        :header "Name"     :align :left}
                            {:key :model       :header "Model"    :align :left}
                            {:key :provider    :header "Provider" :align :left}
                            {:key :soul-source :header "Soul"     :align :left}
                            {:key :tags-text   :header "Tags"     :align :left}]
                  :rows    rows
                  :zebra?  true
                  :color?  (effective-color? opts)})))

(defn- render-list! [rows opts]
  (let [rows (vec (sort-by :name rows))]
    (cond
      (:json opts) (cli-common/print-json! rows)
      (:edn opts)  (cli-common/print-edn! rows)
      :else        (println (format-crew rows opts)))))

(defn- show-payload [row]
  (select-keys row [:name :model :provider :soul :tags]))

(defn- print-show-detail! [row]
  (println (str "Name: " (:name row)))
  (println (str "Model: " (:model row)))
  (println (str "Provider: " (:provider row)))
  (when-let [soul (:soul row)]
    (println "Soul:")
    (println soul))
  (when (seq (:tags row))
    (println (str "Tags: " (text-tags (:tags row))))))

(defn- render-show! [row opts]
  (cond
    (:json opts) (cli-common/print-json! (show-payload row))
    (:edn opts)  (cli-common/print-edn! (show-payload row))
    :else        (print-show-detail! row)))

(defn run [opts]
  (let [required-tags (set (map keyword (:tag opts)))
        excluded-tags (set (map keyword (:without-tag opts)))
        rows          (->> (resolve-crew opts)
                           (filter (fn [row]
                                     (let [tags (:tags row)]
                                       (and (every? #(store/has-tag? row %) required-tags)
                                            (not-any? #(store/has-tag? row %) excluded-tags)
                                            (if (:untagged opts) (empty? tags) true)))))
                           (map #(assoc % :tags-text (text-tags (:tags %)))))]
    (render-list! rows opts))
  0)

(defn- run-show [opts crew-id]
  (if-let [row (resolve-crew-by-name opts crew-id)]
    (do
      (render-show! row opts)
      0)
    (do
      (binding [*out* *err*]
        (println (str "crew not found: " crew-id)))
      1)))

(defn- print-help! []
  (println (cli/command-help (cli/get-command "crew")))
  0)

(defn- show-help-text []
  (let [options (-> (tools-cli/parse-opts [] option-spec)
                    :summary
                    str/trim-newline)]
    (str/join "\n"
              ["Usage: isaac crew show <name>"
               ""
               "Show one crew member"
               ""
               "Options:"
               options])))

(defn- print-show-help! []
  (println (show-help-text))
  0)

(defn- run-list [opts list-args]
  (let [{:keys [options errors]} (parse-option-map list-args)]
    (cond
      (:help options)
      (print-help!)

      (seq errors)
      (do
        (doseq [error errors] (println error))
        1)

      :else
      (run (merge (dissoc opts :_raw-args) options)))))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])
        subcmd   (first raw-args)]
    (cond
      (= "show" subcmd)
      (let [{:keys [options arguments errors]} (parse-with-arguments (rest raw-args))]
        (cond
          (:help options)
          (print-show-help!)

          (seq errors)
          (do
            (doseq [error errors] (println error))
            1)

          :else
          (let [crew-id (first arguments)]
            (if (str/blank? crew-id)
              (print-show-help!)
              (run-show (merge (dissoc opts :_raw-args) options) crew-id)))))

      (= "list" subcmd)
      (run-list opts (rest raw-args))

      (and subcmd (not (str/starts-with? subcmd "-")))
      (do
        (binding [*out* *err*]
          (println (str "Unknown crew subcommand: " subcmd)))
        1)

      :else
      (let [{:keys [options errors]} (parse-option-map raw-args)]
        (cond
          (seq errors)
          (do
            (doseq [error errors] (println error))
            1)

          :else
          (print-help!))))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :crew [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :crew [_id]
  option-spec)

(defmethod cli-api/subcommands :crew [_id]
  [{:name "list" :summary "List configured crew members"}
   {:name "show" :summary "Show one crew member"}])