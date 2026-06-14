(ns isaac.configurator-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.runtime :as runtime]
    [isaac.config.config-steps :as config-steps]
    [isaac.config.install :as config-install]
    [isaac.cron.service :as cron-service]
    [isaac.fs :as fs]
    [isaac.hooks :as hooks]
    [isaac.module.loader :as module-loader]
    [isaac.server.app :as app]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]))

(helper! isaac.configurator-steps)

(declare isaac-edn-path)
(declare with-server-fs server-fs)

(defn- ->slot-key [name]
  (keyword name))

(defn- live-instance [slot-name]
  (nexus/get-in [:comms (->slot-key slot-name)]))

(defn- live-node [path]
  (nexus/get-in path))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value)         (parse-long value)
    (= "true" (str/lower-case value))   true
    (= "false" (str/lower-case value))  false
    (str/starts-with? value "[")        (edn/read-string value)
    (str/starts-with? value "{")        (edn/read-string value)
    (str/starts-with? value ":")        (edn/read-string value)
    (str/starts-with? value "\"")       (edn/read-string value)
    :else                                value))

(declare ensure-config-berths-installed!)

(defn- read-state [instance]
  ;; telly is a fixture module loaded via module-loader (not as a plain :require)
  ;; — soft-resolve avoids deftype-class-redefinition mismatches when the module
  ;; is reloaded between scenarios.
  (let [telly? (requiring-resolve 'isaac.comm.telly/telly?)
        state  (requiring-resolve 'isaac.comm.telly/state)]
    (cond
      (telly? instance)            (state instance)
      (some-> (:state* instance))  @(:state* instance)
      (map? instance)              instance
      :else                        {})))

(defn- get-by-dotted-path [m path]
  (let [keys (mapv keyword (str/split path #"\."))]
    (get-in m keys)))

(defn comm-is-registered [impl]
  (let [ns-sym      (symbol (str "isaac.comm." impl))
         _           (require ns-sym)
         make-factory (requiring-resolve (symbol (str ns-sym "/make")))]
    (let [module-id    (keyword (str "isaac.comm." impl))
          module-root  (str (System/getProperty "user.dir") "/modules/" (name module-id))
          module-coord {:local/root (str "modules/" (name module-id))}]
      (when (.isDirectory (java.io.File. module-root))
        (g/update! :server-config #(assoc-in (or % {}) [:modules module-id] module-coord))
        (with-server-fs
          (fn []
            (let [path    (isaac-edn-path)
                  fs*     (server-fs)
                  current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})]
              (fs/mkdirs fs* (fs/parent path))
              (fs/spit fs* path (pr-str (assoc-in current [:modules module-id] module-coord))))))))
    (comm-registry/register-factory! impl make-factory))
  (g/should (comm-registry/registered? impl)))

(defn- expectations-met? [name table]
  (when-let [instance (live-instance name)]
    (let [state (read-state instance)]
      (every? (fn [row]
                (let [row-map  (zipmap (:headers table) row)
                      path     (get row-map "path")
                      expected (parse-state-value (get row-map "value"))]
                  (= expected (get-by-dotted-path state path))))
              (:rows table)))))

(defn comm-exists-with-state [name table]
  (ensure-config-berths-installed!)
  (helper/await-condition #(expectations-met? name table))
  (let [instance (live-instance name)]
    (g/should-not-be-nil instance)
    (let [state (read-state instance)]
      (doseq [row (:rows table)]
        (let [row-map  (zipmap (:headers table) row)
              path     (get row-map "path")
              expected (parse-state-value (get row-map "value"))
              actual   (get-by-dotted-path state path)]
          (g/should= expected actual))))))

(defn- node-at-path [path-str]
  (live-node (edn/read-string path-str)))

(defn nexus-node-state-has [path-str table]
  (ensure-config-berths-installed!)
  (helper/await-condition
    #(when-let [inst (node-at-path path-str)]
       (let [state (read-state inst)]
         (every? (fn [row]
                   (let [row-map (zipmap (:headers table) row)]
                     (= (parse-state-value (get row-map "value"))
                        (get-by-dotted-path state (get row-map "path")))))
                 (:rows table)))))
  (let [state (read-state (node-at-path path-str))]
    (doseq [row (:rows table)]
      (let [row-map (zipmap (:headers table) row)]
        (g/should= (parse-state-value (get row-map "value"))
                   (get-by-dotted-path state (get row-map "path")))))))

(defn nexus-no-node-at [path-str]
  (ensure-config-berths-installed!)
  (helper/await-condition #(nil? (node-at-path path-str)))
  (g/should-be-nil (node-at-path path-str)))

(defn comm-does-not-exist [name]
  (ensure-config-berths-installed!)
  (helper/await-condition #(nil? (live-instance name)))
  (g/should-be-nil (live-instance name)))

(defn hook-registry-entry-has [name table]
  (helper/await-condition #(when-let [entry (hooks/lookup-hook name)]
                             (every? (fn [row]
                                       (let [row-map  (zipmap (:headers table) row)
                                             path     (get row-map "path")
                                             expected (parse-state-value (get row-map "value"))]
                                         (= expected (get-by-dotted-path (:entry entry) path))))
                                     (:rows table))))
  (let [entry (hooks/lookup-hook name)]
    (g/should-not-be-nil entry)
    (doseq [row (:rows table)]
      (let [row-map  (zipmap (:headers table) row)
            path     (get row-map "path")
            expected (parse-state-value (get row-map "value"))
            actual   (get-by-dotted-path (:entry entry) path)]
        (g/should= expected actual)))))

(defn cron-job-has [name table]
  (helper/await-condition #(when-let [instance (live-node [:cron])]
                             (let [state (cron-service/job-state instance name)]
                               (and state
                                    (every? (fn [row]
                                              (let [row-map  (zipmap (:headers table) row)
                                                    path     (get row-map "path")
                                                    expected (parse-state-value (get row-map "value"))]
                                                (= expected (get-by-dotted-path state path))))
                                            (:rows table))))))
  (let [instance (live-node [:cron])
        state    (cron-service/job-state instance name)]
    (g/should-not-be-nil state)
    (doseq [row (:rows table)]
      (let [row-map  (zipmap (:headers table) row)
            path     (get row-map "path")
            expected (parse-state-value (get row-map "value"))
            actual   (get-by-dotted-path state path)]
        (g/should= expected actual)))))

(defn- node-type [node]
  (cond
    (map? node) (:type node)
    (satisfies? clojure.lang.ILookup node) (:type node)
    :else nil))

(defn- ensure-config-berths-installed! []
  (when-not (g/get :config-berths-installed?)
    (when-let [loaded (g/get :loaded-config-result)]
      (let [cfg          (:config loaded)
            module-index (merge (module-loader/foundation-index) (:module-index cfg))]
        (module-loader/start-modules! module-index)
        (config-install/install-config-berths! {:config cfg :module-index module-index :registries (app/registries)})
        (g/assoc! :config-berths-installed? true)))))

(defn config-reloaded []
  (ensure-config-berths-installed!)
  (let [prev   (:config (g/get :loaded-config-result))
        result (config-steps/reload-result)]
    (let [cfg          (:config result)
          module-index (merge (module-loader/foundation-index) (:module-index cfg))]
      (config-install/install-config-berths! {:config       cfg
                                              :old-config   prev
                                              :module-index module-index}))))

(defn nexus-has-node-at [expected-type path]
  (ensure-config-berths-installed!)
  (let [path* (edn/read-string path)
        node  (live-node path*)]
    (g/should-not-be-nil node)
    (g/should= (keyword expected-type) (node-type node))))

;; --- config update step (delta-merge with #delete sentinel) -------------

(defn- isaac-edn-path []
  (let [root (or (g/get :runtime-root)
                      (g/get :root))]
    (str root "/config/isaac.edn")))

(defn- deep-merge [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b)                b
    :else                    a))

(defn- read-current-cfg []
  (let [path     (isaac-edn-path)
        fs*      (server-fs)
        on-disk  (when (fs/exists? fs* path)
                   (try (edn/read-string (fs/slurp fs* path))
                        (catch Exception _ nil)))
        in-mem   (g/get :server-config)]
    (deep-merge (or on-disk {}) (or in-mem {}))))

(defn- dissoc-in [m path]
  (cond
    (empty? path) m
    (= 1 (count path)) (dissoc m (first path))
    :else (let [parent-path (vec (butlast path))
                leaf        (last path)
                parent      (get-in m parent-path)]
            (if (map? parent)
              (assoc-in m parent-path (dissoc parent leaf))
              m))))

(defn- apply-update [cfg path-str value-str]
  (let [keys (mapv keyword (str/split path-str #"\."))]
    (if (= "#delete" (str/trim (str value-str)))
      (dissoc-in cfg keys)
      ;; Config update rows represent on-disk config values, so leave bare
      ;; words as strings instead of assertion-style keywords.
      (assoc-in cfg keys
                (cond
                  (re-matches #"-?\d+" value-str) (parse-long value-str)
                  (= "true" (str/lower-case value-str)) true
                  (= "false" (str/lower-case value-str)) false
                  (or (str/starts-with? value-str "[")
                      (str/starts-with? value-str "{")
                      (str/starts-with? value-str ":")
                      (str/starts-with? value-str "\""))
                  (edn/read-string value-str)
                  :else value-str)))))

(defn- with-server-fs [f]
  (if-let [mem (g/get :mem-fs)]
    (nexus/-with-nested-nexus {:fs mem} (f))
    (f)))

(defn- server-fs []
  (or (g/get :mem-fs) (nexus/get :fs)))

(defn- notify-change! [path]
  (when-let [source (g/get :config-change-source)]
    (runtime/notify-path! source path)))

(defn config-updated [table]
  (with-server-fs
    (fn []
      (let [path (isaac-edn-path)
            cfg  (reduce (fn [acc row]
                           (let [row-map (zipmap (:headers table) row)
                                 p       (get row-map "path")
                                 v       (get row-map "value")]
                             (apply-update acc p v)))
                         (read-current-cfg)
                         (:rows table))]
        (let [fs* (server-fs)]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit   fs* path (pr-str cfg)))
        (notify-change! path)))))

(defn server-not-running []
  (g/should-not (app/running?)))

;; --- step definitions ---------------------------------------------------

(defgiven "the {impl:string} comm is registered" isaac.configurator-steps/comm-is-registered
  "Loads the plugin namespace so its (register-factory! ...) self-registers
   the impl in isaac.comm.registry/*registry*. Test helper for comm impls
   that self-register on namespace load.")

(defthen "the comm {name:string} exists with state:" isaac.configurator-steps/comm-exists-with-state
  "Asserts that an instance lives at [:comms <name>] in the server's object
    tree and that its state map matches each row (dotted path -> value).")

(defthen "the comm {name:string} does not exist" isaac.configurator-steps/comm-does-not-exist)

(defthen "the hook {name:string} registry entry has:" isaac.configurator-steps/hook-registry-entry-has)

(defthen "the cron job {name:string} has:" isaac.configurator-steps/cron-job-has)

(defthen #"the nexus has a :([^ ]+) node at (.+)" isaac.configurator-steps/nexus-has-node-at)
(defthen #"the nexus node at (.+) has state:" isaac.configurator-steps/nexus-node-state-has)
(defthen #"the nexus has no node at (.+)" isaac.configurator-steps/nexus-no-node-at)

(defwhen "the config is reloaded" isaac.configurator-steps/config-reloaded
  "Re-loads config from the root (after the scenario rewrote isaac.edn)
   and reconciles config-berth nodes against the previous load — the
   generic hot-reload path, no server required.")

(defwhen "config is updated:" isaac.configurator-steps/config-updated
  "Delta-merges path/value rows into config/isaac.edn. A value of \"#delete\"
   removes the key from the config tree. Triggers a config reload via the
   bound change source.")

(defthen "the Isaac server is not running" isaac.configurator-steps/server-not-running)
