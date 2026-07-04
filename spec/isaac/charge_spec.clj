(ns isaac.charge-spec
  (:require
    [isaac.charge :as sut]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.session.context :as session-ctx]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(def stub-comm (reify Object))
(def test-model-id marigold/helm-spark)
(def test-provider-id marigold/helm-systems)

(defn- stub-behavior [crew soul model ctx-window]
  {:crew           crew
   :soul           soul
   :nonce          "N0NCE-stubbed"
   :model          model
   :model-cfg      {:model model}
   :context-window ctx-window
   :provider       nil
   :provider-cfg   nil})

(defn- model-cfg [model-id ctx-window]
  (marigold/model-cfg test-provider-id model-id :context-window ctx-window))

(defn- crew-cfg [crew-id model-id soul]
  (marigold/crew-cfg crew-id :model model-id :soul soul))

(def base-cfg
  {:defaults {:crew "main"}
   :crew     {"main" (crew-cfg marigold/captain test-model-id "You are Atticus.")}
   :models   {test-model-id (model-cfg test-model-id 4096)}})

(describe "charge"

  (describe "charge?"

    (it "true for a charge map"
      (should (sut/charge? {:charge/type :charge})))

    (it "false for a plain map"
      (should-not (sut/charge? {:session-key "s1" :input "hi"})))

    (it "false for nil"
      (should-not (sut/charge? nil))))

  (describe "slash?"

    (it "true when input starts with /"
      (should (sut/slash? {:charge/type :charge :input "/status"})))

    (it "false for normal input"
      (should-not (sut/slash? {:charge/type :charge :input "hello"})))

    (it "false for nil input"
      (should-not (sut/slash? {:charge/type :charge :input nil}))))

  (describe "unresolved?"

    (it "true when :charge/unresolved is true"
      (should (sut/unresolved? {:charge/type :charge :charge/unresolved true})))

    (it "false for a resolved charge"
      (should-not (sut/unresolved? {:charge/type :charge :input "hi" :model "m"})))

    (it "false for a plain map"
      (should-not (sut/unresolved? {:input "hi"}))))

  (describe "channel"

    (it "returns the :comm value"
      (should= stub-comm (sut/channel {:charge/type :charge :comm stub-comm})))

    (it "nil when :comm absent"
      (should-be-nil (sut/channel {:charge/type :charge}))))

  (describe "agent"

    (it "returns the resolved crew id"
      (should= "ketch" (sut/agent {:charge/type :charge :crew "ketch"})))

    (it "nil when crew not set"
      (should-be-nil (sut/agent {:charge/type :charge}))))

  (describe "transcript"

    (it "calls active-transcript on the session store from nexus"
      (let [messages [{:role "user" :content "hi"}]
            store    (reify store/SessionStore
                       (active-transcript [_ _] messages))]
        (with-redefs [nexus/get-in (fn [path] (when (= [:sessions :store] path) store))]
          (should= messages (sut/transcript {:charge/type :charge :session-key "s1"})))))

    (it "returns nil when no session store is registered"
      (with-redefs [nexus/get-in (fn [_] nil)]
        (should-be-nil (sut/transcript {:charge/type :charge :session-key "s1"})))))

  (describe "build"

    (it "stamps :charge/type on the returned map"
      (with-redefs [loader/snapshot             (fn [_] base-cfg)
                    session-ctx/resolve-behavior (fn [_ _] (stub-behavior "main" "You are Atticus." test-model-id 4096))]
        (should (sut/charge? (sut/build {:session-key "s1" :input "hi"})))))

    (it "builds a resolved charge from session-key and input"
      (with-redefs [loader/snapshot             (fn [_] base-cfg)
                    session-ctx/resolve-behavior (fn [_ _] (stub-behavior "main" "You are Atticus." test-model-id 4096))]
        (let [ch     stub-comm
              charge (sut/build {:session-key "s1" :input "hello there" :comm ch})]
          (should-not (sut/unresolved? charge))
          (should= "s1" (:session-key charge))
          (should= "hello there" (:input charge))
          (should= ch (sut/channel charge))
          (should= "main" (sut/agent charge))
          (should= test-model-id (:model charge))
          (should= "You are Atticus." (:soul charge))
          (should= "N0NCE-stubbed" (:nonce charge))
          (should= 4096 (:context-window charge)))))

    (it "preserves explicit guidance alongside origin"
      (with-redefs [loader/snapshot              (fn [_] base-cfg)
                    session-ctx/resolve-behavior (fn [_ _] (stub-behavior "main" "You are Atticus." test-model-id 4096))]
        (let [charge (sut/build {:session-key "s1"
                                 :input       "hello there"
                                 :origin      {:kind :hail :hail-id "hail-7"}
                                 :guidance    "Autonomous hail; the user may not see your reply."})]
          (should= {:kind :hail :hail-id "hail-7"} (:origin charge))
          (should= "Autonomous hail; the user may not see your reply." (:guidance charge)))))

    (it "uses explicit crew override when provided"
      (let [first-mate-model marigold/starcore-7]
        (with-redefs [loader/snapshot             (fn [_] {:defaults {:crew "main"}
                                                         :crew     {"main"                 (crew-cfg marigold/captain test-model-id "Main bridge orders")
                                                                    marigold/first-mate (crew-cfg marigold/first-mate first-mate-model "Cordelia has the watch")}
                                                         :models   {test-model-id      (model-cfg test-model-id 4096)
                                                                    first-mate-model (marigold/model-cfg marigold/starcore first-mate-model :context-window 8192)}})
                      session-ctx/resolve-behavior (fn [_ opts]
                                                     (stub-behavior (:crew opts) "Cordelia has the watch" first-mate-model 8192))]
          (let [charge (sut/build {:session-key "s1" :input "hi" :crew marigold/first-mate})]
            (should= marigold/first-mate (sut/agent charge))
            (should= "Cordelia has the watch" (:soul charge))
            (should= first-mate-model (:model charge))))))

    (it "appends soul-prepend when provided"
      (with-redefs [loader/snapshot             (fn [_] {:defaults {:crew "main"}
                                                         :crew     {"main" (crew-cfg marigold/captain test-model-id "Base.")}
                                                         :models   {test-model-id (model-cfg test-model-id 4096)}})
                    session-ctx/resolve-behavior (fn [_ _] (stub-behavior "main" "Base." test-model-id 4096))]
        (let [charge (sut/build {:session-key "s1" :input "hi" :soul-prepend "Addendum."})]
          (should= "Base.\n\nAddendum." (:soul charge)))))

    (it "forwards crew and explicit model overrides to resolve-behavior without pinning config"
      (let [seen (atom nil)]
        (with-redefs [loader/snapshot             (fn [_] {:defaults  {:crew "main"}
                                                         :crew      {"main" (crew-cfg marigold/captain test-model-id "Base.")}
                                                         :models    {test-model-id (model-cfg test-model-id 4096)}
                                                         :root "/tmp/isaac/.isaac"})
                      session-ctx/resolve-behavior (fn [_ opts]
                                                     (reset! seen opts)
                                                     (stub-behavior "main" "Base." test-model-id 4096))]
          (let [charge (sut/build {:session-key    "s1"
                                   :input          "hi"
                                   :model-override "beta"})]
            (should= "/tmp/isaac/.isaac" (get-in charge [:config :root]))
            (should= "main" (:crew @seen))
            (should= "beta" (:model @seen))
            (should-be-nil (:config @seen))))))

    (it "re-resolves crew model from live config when the caller passes a stale config snapshot"
      (let [test-root "/test/charge-reload"
            alpha-cfg {:defaults {:crew "flipper"}
                         :crew     {"flipper" {:model "alpha" :soul "flip"}}
                         :models   {"alpha" {:model "alpha-1" :provider "grover"}
                                    "beta"  {:model "beta-1" :provider "grover"}}
                         :providers {"grover" {:api "grover"}}}
            beta-cfg  (assoc-in alpha-cfg [:crew "flipper" :model] "beta")
            stale-cfg alpha-cfg]
        (helper/with-memory-store
          (nexus/-with-nested-nexus {:root test-root}
            (config/dangerously-install-config! beta-cfg "spec")
            (helper/create-session! test-root "flip-sess" {:crew "flipper"})
            (let [charge (sut/build {:session-key "flip-sess"
                                     :input       "second"
                                     :config      stale-cfg
                                     :crew        "flipper"})]
              (should= "beta-1" (:model charge)))
            (config/dangerously-install-config! nil "spec")))))

    (it "returns an unresolved charge with :no-model when no model is configured"
      (with-redefs [loader/snapshot             (fn [_] {:defaults {:crew "main"}
                                                         :crew     {"main" {:soul "You are Atticus."}}})
                    session-ctx/resolve-behavior (fn [_ _] {:crew "main" :soul "You are Atticus."})]
        (let [charge (sut/build {:session-key "s1" :input "hi"})]
          (should (sut/unresolved? charge))
          (should= :no-model (:charge/reason charge)))))

    (it "returns an unresolved charge when dispatch-error is set"
      (let [charge (sut/build {:session-key "s1" :input "hi"
                               :dispatch-error {:error :unknown-crew}})]
        (should (sut/unresolved? charge))
        (should= :unknown-crew (:charge/reason charge))))))
