(ns isaac.crew.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [isaac.config.loader :as loader]
    [isaac.crew.cli :as sut]
    [speclj.core :refer :all]))

(def crew-cfg
  {:crew   {"main" {:model "grover"}
            "joe"  {:model "grover" :soul "You are Joe." :tags #{:role/worker :project/chess}}
            "sue"  {:model "grover" :tags #{:role/verify}}}
   :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(def crew-opts {:root "/test/crew" :home "/test/crew-home"})

(describe "crew cli"

  (it "renders list output as JSON with sorted tags"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run (assoc crew-opts :json true))))
            rows   (json/parse-string output true)
            joe    (some #(when (= "joe" (:name %)) %) rows)]
        (should= "echo" (:model joe))
        (should= "grover" (:provider joe))
        (should= ["project/chess" "role/worker"] (:tags joe)))))

  (it "renders list output as EDN with tags as a set"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run (assoc crew-opts :edn true))))
            rows   (edn/read-string output)
            joe    (some #(when (= "joe" (:name %)) %) rows)]
        (should= "echo" (:model joe))
        (should= "grover" (:provider joe))
        (should= #{:project/chess :role/worker} (:tags joe)))))

  (it "renders show output as JSON"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run-fn (assoc crew-opts :_raw-args ["show" "joe" "--json"]))))
            row    (json/parse-string output true)]
        (should= "joe" (:name row))
        (should= "echo" (:model row))
        (should= "grover" (:provider row))
        (should= ["project/chess" "role/worker"] (:tags row)))))

  (it "filters crews by tag"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run (assoc crew-opts :tag ["role/worker"]))))]
        (should-contain "joe" output)
        (should-not-contain "sue" output))))

  (it "filters crews by repeated tags with and semantics"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run (assoc crew-opts :tag ["role/worker" "project/chess"]))))]
        (should-contain "joe" output)
        (should-not-contain "sue" output))))

  (it "excludes crews by without-tag"
    (with-redefs [loader/load-config! (fn [& _] crew-cfg)]
      (let [output (with-out-str (should= 0 (sut/run (assoc crew-opts :without-tag ["role/worker"]))))]
        (should-contain "sue" output)
        (should-not-contain "joe" output)))))
