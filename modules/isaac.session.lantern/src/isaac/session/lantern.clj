(ns isaac.session.lantern
  (:require
    [clojure.edn :as edn]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]))

(defn logbook [cfg]
  (fn [event]
    (when (get-in cfg [:lantern :logbook :fail])
      (throw (ex-info "logbook failed" {:event event})))
    (let [root  (or (loader/root) (:root cfg))
          path  (str root "/lantern/logbook.edn")
          fs*   (fs/instance)
          prior (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {:events []})
          event (cond-> (dissoc event :config)
                  (keyword? (:event event)) (update :event name))]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path (pr-str (update prior :events conj event))))))

(defn porthole [transcript]
  (let [entries (filter #(= "message" (:type %)) transcript)
        current (last entries)
        previous (last (filter #(= "assistant" (get-in % [:message :role])) (butlast entries)))]
    (vec (remove nil? [previous current]))))
