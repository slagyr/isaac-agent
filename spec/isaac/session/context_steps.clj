(ns isaac.session.context-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.fs :as fs]
    [isaac.session.context :as session-ctx]
    [isaac.nexus :as nexus]))

(helper! isaac.session.context-steps)

(defn- build-synthetic-cfg [agents models]
  (let [crew          (into {} (map (fn [[id a]]
                                      [id (cond-> {}
                                            (:soul a) (assoc :soul (:soul a))
                                            (:model a) (assoc :model (:model a)))])
                                    agents))
        models'       (into {} (map (fn [[alias m]]
                                      [alias {:model          (:model m)
                                              :provider       (:provider m)
                                              :context-window (:context-window m)}])
                                    models))
        providers     (into {} (map (fn [[_ m]]
                                      [(:provider m) {:base-url "http://fake"}])
                                    models))
        default-crew  (or (first (keys crew)) "main")
        default-model (or (get-in crew [default-crew :model])
                          (first (keys models')))]
    {:defaults  {:crew default-crew :model default-model}
     :crew      crew
     :models    models'
     :providers providers}))

(defn- with-feature-fs [f]
  (if-let [mem-fs (g/get :mem-fs)]
    (nexus/-with-nested-nexus {:fs mem-fs}
      (f))
    (f)))

(defn- resolve-home-path [home]
  (cond
    (str/starts-with? home "/") home
    (and (g/get :root)
         (= home (subs (g/get :root) 1))) (g/get :root)
    :else (str (System/getProperty "user.dir") "/" home)))

(defn -resolve-turn-context [{:keys [agents crew models root]} crew-id]
  (with-feature-fs
    #(nexus/-with-nested-nexus (cond-> {} root (assoc :root root))
       (let [agents (or (not-empty crew) (not-empty agents))
             cfg    (if agents
                       (build-synthetic-cfg agents models)
                       (:config (loader/load-config-result {:root root :fs (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))})))
             ctx    (resolve/resolve-crew-context cfg crew-id)]
         (assoc ctx :boot-files (session-ctx/read-boot-files nil))))))

(defn turn-context-resolved [agent]
  (g/assoc! :resolved-ctx
            (-resolve-turn-context {:models    (g/get :models)
                                    :agents    (g/get :agents)
                                    :crew      (g/get :crew)
                                    :root (g/get :root)}
                                   agent)))

(defn resolved-soul-contains [expected]
  (let [soul (:soul (g/get :resolved-ctx))]
    (g/should (str/includes? (or soul "") expected))))

(defn resolved-soul-is [expected]
  (g/should= expected (:soul (g/get :resolved-ctx))))

(defn resolved-model-not-nil []
  (g/should-not-be-nil (:model (g/get :resolved-ctx))))

(defn resolved-provider-not-nil []
  (g/should-not-be-nil (:provider (g/get :resolved-ctx))))

(defwhen "turn context is resolved for crew {crew:string}" isaac.session.context-steps/turn-context-resolved
  "Resolves the turn context (soul, model, provider, provider-config)
   for the given crew id. Uses a synthetic cfg built from in-memory
   :crew/:models atoms when present; otherwise loads from disk at
   :workspace-home or :root. Stores result in :resolved-ctx.")

(defthen "the resolved soul contains {expected:string}" isaac.session.context-steps/resolved-soul-contains)

(defthen "the resolved soul is {expected:string}" isaac.session.context-steps/resolved-soul-is)

(defthen "the resolved model is not nil" isaac.session.context-steps/resolved-model-not-nil)

(defthen "the resolved provider is not nil" isaac.session.context-steps/resolved-provider-not-nil)
