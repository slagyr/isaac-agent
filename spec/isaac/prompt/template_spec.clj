(ns isaac.prompt.template-spec
  (:require
    [isaac.prompt.template :as sut]
    [speclj.core :refer :all]))

(describe "isaac.prompt.template"

  (it "returns empty string for a nil template"
    (should= "" (sut/render nil {})))

  (it "substitutes a known string-key var"
    (should= "Hello Zane."
             (sut/render "Hello {{name}}." {"name" "Zane"})))

  (it "substitutes a known keyword-key var"
    (should= "Hello Zane."
             (sut/render "Hello {{name}}." {:name "Zane"})))

  (it "leaves unknown placeholders when on-missing is :keep"
    (should= "Hello {{other}}."
             (sut/render "Hello {{other}}." {:name "Zane"} {:on-missing :keep})))

  (it "replaces unknown placeholders with empty string when on-missing is :empty"
    (should= "Hello ."
             (sut/render "Hello {{other}}." {:name "Zane"} {:on-missing :empty})))

  (it "replaces unknown placeholders with a marker when on-missing is :marker"
    (should= "Hello (missing)."
             (sut/render "Hello {{other}}." {:name "Zane"} {:on-missing :marker})))

  (it "replaces a bound empty string with empty text"
    (should= "Start work on ."
             (sut/render "Start work on {{bean}}." {"bean" ""} {:on-missing :keep})))

  (it "substitutes multiple placeholders in one template"
    (should= "Hello Zane, you have 3 items."
             (sut/render "Hello {{name}}, you have {{count}} items."
                         {:name "Zane" :count 3}
                         {:on-missing :marker})))

  (it "marks only absent vars when on-missing is :marker"
    (should= "Hello Zane, you have (missing) items."
             (sut/render "Hello {{name}}, you have {{count}} items."
                         {:name "Zane"}
                         {:on-missing :marker}))))