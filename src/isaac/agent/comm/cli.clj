(ns isaac.agent.comm.cli
  "isaac comms — manage comm slots. `allow <slot> <target>` adds one
   outbound recipient to the slot's :comm/allow-to; `allow <slot>` lists
   it. The write lands in the file that already holds the slot, and the
   server's config watcher hot-reloads it."
  (:require
    [clojure.string :as str]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.mutate :as mutate]
    [isaac.foundation.config.root :as root]))

(def ^:private usage
  (str "Usage: isaac comms allow <slot> [<target>]\n\n"
       "  allow <slot> <target>  Add <target> to the slot's outbound allow-list\n"
       "  allow <slot>           List the slot's outbound allow-list\n\n"
       "The first allow on a slot turns its allow-list on: from then on comm_send\n"
       "reaches only listed targets.\n\n"
       "Examples:\n"
       "  isaac comms allow imessage +15555550100\n"
       "  isaac comms allow discord general"))

(defn- fail [message]
  (binding [*out* *err*] (println message))
  1)

(defn- slot-config [root-dir slot]
  (let [comms (:comms (:config (loader/load-config-result {:root root-dir})))]
    (or (get comms (keyword slot)) (get comms slot))))

(defn- list-targets [slot cfg]
  (if-let [targets (:comm/allow-to cfg)]
    (doseq [target targets] (println target))
    (println (str slot " has no outbound allow-list: every target is allowed")))
  0)

(defn- add-target [root-dir slot cfg target]
  (let [current (vec (:comm/allow-to cfg))]
    (if (some #{target} current)
      (do (println (str slot " already allows " target)) 0)
      (let [targets (conj current target)
            result  (mutate/set-config root-dir (str "comms." slot ".comm/allow-to") targets
                                       :skip-ref-validation? true)]
        (if (= :ok (:status result))
          (do (println (str slot " allows " target " (" (count targets) " on the list, " (:file result) ")"))
              0)
          (fail (str "error: " (str/join "; " (map #(str (:key %) " - " (:value %)) (:errors result))))))))))

(defn- allow [opts [slot target]]
  (let [root-dir (root/default-root opts)
        cfg      (when-not (str/blank? slot) (slot-config root-dir slot))]
    (cond
      (str/blank? slot) (fail usage)
      (nil? cfg)        (fail (str "unknown comm slot: " slot))
      (nil? target)     (list-targets slot cfg)
      :else             (add-target root-dir slot cfg target))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [[sub & args] _raw-args]
    (case sub
      "allow" (allow opts args)
      (fail usage))))

(defmethod cli-api/run :comms [_id opts] (run-fn opts))

(defmethod cli-api/subcommands :comms [_id]
  [{:name "allow" :summary "Add or list a comm slot's outbound recipients"}])
