(ns isaac.turn.cli
  "isaac turns list | drop — inspect and evict the turn-request waiting room."
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as cli]
    [isaac.cli.table :as table]
    [isaac.config.root :as root]
    [isaac.turn.queue :as queue]))

(def option-spec
  [["-h" "--help" "Show help"]])

(defn- derive-root [opts]
  (root/default-root opts))

(defn- format-turnstiles [refs]
  (->> refs
       (map (fn [ts-ref]
              (cond
                (sequential? ts-ref) (str (name (first ts-ref))
                                          (when (seq (rest ts-ref))
                                            (str ":" (str/join "/" (rest ts-ref)))))
                (keyword? ts-ref) (name ts-ref)
                :else (str ts-ref))))
       (str/join " ")))

(defn- held->row [record]
  {:id         (:id record)
   :session    (or (:session record) "")
   :turnstiles (format-turnstiles (:turnstiles record))
   :state      (name (or (:state record) :held))})

(defn- format-held [rows]
  (table/render {:columns [{:key :id         :header "ID"         :align :left}
                           {:key :session    :header "SESSION"    :align :left}
                           {:key :turnstiles :header "TURNSTILES" :align :left}
                           {:key :state      :header "STATE"      :align :left}]
                 :rows    rows
                 :zebra?  true
                 :color?  false}))

(defn- with-queue-root [opts f]
  (binding [queue/*root* (derive-root opts)]
    (f)))

(defn- run-list [opts]
  (with-queue-root opts
    (fn []
      (let [rows (mapv held->row (queue/list-held))]
        (when (seq rows)
          (println (format-held rows)))
        0))))

(defn- run-drop [opts id]
  (if (str/blank? id)
    (do (println "Usage: isaac turns drop <id>") 1)
    (with-queue-root opts
      (fn []
        (if (queue/read-held id)
          (do
            (queue/delete-held! id)
            (println (str "dropped " id))
            0)
          (do
            (println (str "held turn not found: " id))
            1))))))

(defn- print-help! []
  (println (cli/command-help (cli/get-command "turns")))
  0)

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [raw-args (or _raw-args [])
        subcmd   (first raw-args)]
    (cond
      (= "list" subcmd)
      (let [{:keys [options errors]} (tools-cli/parse-opts (rest raw-args) option-spec)]
        (cond
          (:help options) (print-help!)
          (seq errors)    (do (doseq [e errors] (println e)) 1)
          :else           (run-list (merge (dissoc opts :_raw-args) options))))

      (= "drop" subcmd)
      (let [{:keys [options arguments errors]} (tools-cli/parse-opts (rest raw-args) option-spec)]
        (cond
          (:help options) (print-help!)
          (seq errors)    (do (doseq [e errors] (println e)) 1)
          :else           (run-drop (merge (dissoc opts :_raw-args) options) (first arguments))))

      (and subcmd (not (str/starts-with? subcmd "-")))
      (do
        (binding [*out* *err*]
          (println (str "Unknown turns subcommand: " subcmd)))
        1)

      :else
      (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
        (cond
          (:help options) (print-help!)
          (seq errors)    (do (doseq [e errors] (println e)) 1)
          :else           (print-help!))))))

(defmethod cli-api/run :turns [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :turns [_id]
  option-spec)

(defmethod cli-api/subcommands :turns [_id]
  [{:name "list" :summary "List held turn requests"}
   {:name "drop" :summary "Drop a held turn request by id"}])
