(ns isaac.config.mutate-spec
  (:require
    [clojure.edn :as edn]
    [isaac.config.mutate :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private config-root (str marigold/root "/config"))
(def ^:private test-crew-id (keyword marigold/first-mate))
(def ^:private test-crew-path marigold/first-mate)


(defn- read-edn [relative]
  (let [fs* (nexus/get :fs)]
    (when (fs/exists? fs* (str config-root "/" relative))
      (edn/read-string (fs/slurp fs* (str config-root "/" relative))))))

(defn- slurp-file [relative]
  (fs/slurp (nexus/get :fs) (str config-root "/" relative)))

(defn- file-exists? [relative]
  (fs/exists? (nexus/get :fs) (str config-root "/" relative)))

(def ^:private telly-module-root
  (str (System/getProperty "user.dir") "/modules/isaac.comm.telly"))

(describe "isaac.config.mutate"

  (marigold/aboard)

  (describe "set-config"

    (it "writes a new entity to isaac.edn by default"
      (marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".model") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= "isaac.edn" (:file result))
        (should= :helm-mark-iii (get-in (read-edn "isaac.edn") [:crew test-crew-id :model]))))

    (it "writes to an existing entity file when the entity lives there"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii})
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".model") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".edn") (:file result))
        (should-not-contain test-crew-id (:crew (read-edn "isaac.edn")))
        (should= :helm-mark-iii (:model (read-edn (str "crew/" test-crew-path ".edn"))))))

    (it "writes to isaac.edn when the entity is already defined inline"
      (marigold/write-config! (assoc-in marigold/baseline-config [:crew test-crew-id] {:model :helm-mark-iii}))
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".model") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= "isaac.edn" (:file result))
        (should-not (file-exists? (str "crew/" test-crew-path ".edn")))))

    (it "routes new entities to entity files when :prefer-entity-files is true"
      (marigold/write-config! (assoc marigold/baseline-config :prefer-entity-files true))
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".model") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".edn") (:file result))
        (should-not-contain test-crew-id (:crew (read-edn "isaac.edn")))))

    (it "writes soul to the companion .md when one already exists"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii} :soul "Old soul.")
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".soul") "New soul.")]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".md") (:file result))
        (should= "New soul." (slurp-file (str "crew/" test-crew-path ".md")))
        (should-not (contains? (read-edn (str "crew/" test-crew-path ".edn")) :soul))))

    (it "creates a companion .md when a new soul exceeds 64 characters"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii})
      (let [long-soul "You are Cordelia, first mate of the Marigold. Calm command is your default."
            result    (sut/set-config marigold/root (str "crew." test-crew-path ".soul") long-soul)]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".md") (:file result))
        (should= long-soul (slurp-file (str "crew/" test-crew-path ".md")))
        (should-not (contains? (read-edn (str "crew/" test-crew-path ".edn")) :soul))))

    (it "writes a short new soul inline"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii})
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".soul") "Steady.")]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".edn") (:file result))
        (should= "Steady." (:soul (read-edn (str "crew/" test-crew-path ".edn"))))
        (should-not (file-exists? (str "crew/" test-crew-path ".md")))))

    (it "refuses to write a value that fails schema validation"
      (marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "crew." test-crew-path ".model") :nonexistent)]
        (should= :invalid (:status result))
        (should (seq (:errors result)))
        (should-not-contain test-crew-id (:crew (read-edn "isaac.edn")))))

    (it "validates staged changes against the installed runtime fs"
      (marigold/write-baseline!)
      (let [result (nexus/-with-nested-nexus {:fs (nexus/get :fs)}
                     (sut/set-config marigold/root (str "crew." test-crew-path ".model") :nonexistent))]
        (should= :invalid (:status result))
        (should (seq (:errors result)))
        (should-not-contain test-crew-id (:crew (read-edn "isaac.edn")))))

    (it "warns on an unknown key but still writes"
      (marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "crew." marigold/captain ".experimental") true)]
        (should= :ok (:status result))
        (should (seq (:warnings result)))
        (should= true (get-in (read-edn "isaac.edn") [:crew (keyword marigold/captain) :experimental]))))

    (it "does not warn on a module-provided comm field"
      (marigold/write-config! (merge marigold/baseline-config
                                     {:modules {:isaac.comm.telly {:local/root telly-module-root}}
                                      :comms   {:bert {:type :telly :crew :atticus}}}))
      (let [result (sut/set-config marigold/root "comms.bert.loft" "rooftop")]
        (should= :ok (:status result))
        (should-not (some #(= {:key "comms.bert.loft" :value "unknown key"}
                              (select-keys % [:key :value]))
                          (:warnings result)))
        (should= "rooftop" (get-in (read-edn "isaac.edn") [:comms :bert :loft]))))

    (it "still warns on an unknown comm field via the loader"
      (marigold/write-config! (merge marigold/baseline-config
                                     {:modules {:isaac.comm.telly {:local/root telly-module-root}}
                                      :comms   {:bert {:type :telly :crew :atticus}}}))
      (let [result (sut/set-config marigold/root "comms.bert.bogus" 42)]
        (should= :ok (:status result))
        (should-contain {:key "comms[:bert].bogus" :value "unknown key"}
                        (mapv #(select-keys % [:key :value]) (:warnings result))))))

    (it "accepts a whole-entity value and replaces the target"
      (marigold/write-baseline!)
      (marigold/write-provider! :starcore {:base-url "https://old" :api-key "${OLD}"})
      (let [result (sut/set-config marigold/root "providers.starcore"
                                   {:base-url "https://api.starcore.test/v1" :api-key "${STARCORE_API_KEY}"})]
        (should= :ok (:status result))
        (should= {:base-url "https://api.starcore.test/v1" :api-key "${STARCORE_API_KEY}"}
                 (read-edn "providers/starcore.edn"))))

    (it "rejects paths the grammar refuses to parse"
      (marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "crew.*.model" :helm-mark-iii)]
        (should= :invalid-path (:status result))))

    (it "applies a mutation that fixes an existing error without being blocked by unrelated pre-existing errors"
      (marigold/write-config! {:defaults  {:crew :main :model :sparky}
                               :crew      {:main {}}
                               :models    {:sparky {:model "spark-1" :provider :bogus}
                                           :embery {:model "embers" :provider :bogus}}
                               :providers {:helm-systems {}}})
      (let [result (sut/set-config marigold/root "models.sparky.provider" :helm-systems)]
        (should= :ok (:status result))
        (should= :helm-systems (get-in (read-edn "isaac.edn") [:models :sparky :provider]))
        (should-contain {:key "models.embery.provider" :value "pre-existing: must be one of [\"helm-systems\"]"}
                        (mapv #(select-keys % [:key :value]) (:warnings result)))))

    (it "rejects a mutation that introduces a new error even when other errors already exist"
      (marigold/write-config! {:defaults  {:crew :main :model :sparky}
                               :crew      {:main {}}
                               :models    {:sparky {:model "spark-1" :provider :helm-systems}
                                           :embery {:model "embers" :provider :bogus}}
                               :providers {:helm-systems {}}})
      (let [result (sut/set-config marigold/root "models.sparky.provider" :nonexistent)]
        (should= :invalid (:status result))
        (should= [{:key "models.sparky.provider" :value "must be one of [\"helm-systems\"]" :bad-value "nonexistent" :valid-values ["helm-systems"]}]
                 (mapv #(select-keys % [:key :value :bad-value :valid-values]) (:errors result)))))

  (describe "unset-config"

    (marigold/aboard)

    (it "removes a key from the file where it lives"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii :soul "Steady."})
      (let [result (sut/unset-config marigold/root (str "crew." test-crew-path ".soul"))]
        (should= :ok (:status result))
        (should= (str "crew/" test-crew-path ".edn") (:file result))
        (should= {:model :helm-mark-iii} (read-edn (str "crew/" test-crew-path ".edn")))))

    (it "deletes the entity file when the removal empties it"
      (marigold/write-baseline!)
      (marigold/write-crew! test-crew-id {:model :helm-mark-iii})
      (let [result (sut/unset-config marigold/root (str "crew." test-crew-path ".model"))]
        (should= :ok (:status result))
        (should-not (file-exists? (str "crew/" test-crew-path ".edn"))))))

    (it "rejects paths the grammar refuses to parse"
      (let [result (sut/unset-config marigold/root "crew.*.model")]
        (should= :invalid-path (:status result)))))
