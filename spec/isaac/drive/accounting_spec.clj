(ns isaac.drive.accounting-spec
  (:require
    [isaac.drive.accounting :as sut]
    [speclj.core :refer [describe it should-be-nil should=]]))

(def ^:private one-message-request
  {:messages [{:role "system" :content "You are Atticus."}
              {:role "user" :content "hello"}]
   :tools    [{:name "fs__read"}]})

(describe "accounting/compose"

  (it "measures the system prefix, the tool schemas and the transcript apart"
    (let [c (sut/compose one-message-request {:soul "You are Atticus."})]
      (should= 4 (:system-tokens c))
      (should= 4 (:soul-tokens c))
      (should= 6 (:tools-tokens c))
      (should= 9 (:transcript-tokens c))
      (should= 19 (:total-tokens c))))

  (it "counts what it measured"
    (let [c (sut/compose one-message-request {:soul "You are Atticus."})]
      (should= 2 (:messages c))
      (should= 1 (:tool-count c))))

  (it "attributes the system prefix to the parts that built it"
    (let [c (sut/compose {:messages [{:role "system" :content "SOUL\n\nBOOT\n\nRULES\n\nSKILLS"}]}
                         {:soul "SOUL" :boot-files "BOOT" :rules-text "RULES" :skill-menu-text "SKILLS"})]
      (should= 1 (:soul-tokens c))
      (should= 1 (:boot-files-tokens c))
      (should= 2 (:rules-tokens c))
      (should= 2 (:skill-menu-tokens c))))

  (it "names the leftover system text framing, so the parts add up"
    (let [c (sut/compose {:messages [{:role "system" :content "You are Atticus.\n\nNo tricks"}]}
                         {:soul "You are Atticus."})]
      (should= 7 (:system-tokens c))
      (should= 4 (:soul-tokens c))
      (should= 3 (:framing-tokens c))))

  (it "measures a top-level :system the same as a system message"
    (let [c (sut/compose {:system "You are Atticus." :messages [{:role "user" :content "hello"}]}
                         {:soul "You are Atticus."})]
      (should= 4 (:system-tokens c))
      (should= 9 (:transcript-tokens c))))

  (it "measures an empty request as nothing, never as one token"
    (let [c (sut/compose {:messages []} {})]
      (should= 0 (:total-tokens c))
      (should= 0 (:tools-tokens c))
      (should= 0 (:transcript-tokens c)))))

(describe "accounting/reconcile"

  (it "says how much of the request the provider charged for that Isaac never assembled"
    (should= {:estimated-tokens      100
              :reported-prompt-tokens 358
              :unaccounted-tokens    258
              :ratio                 3.58}
             (sut/reconcile {:total-tokens 100} {:prompt-tokens 358 :output-tokens 4})))

  (it "says nothing when the provider reported no prompt size"
    (should-be-nil (sut/reconcile {:total-tokens 100} nil))
    (should-be-nil (sut/reconcile {:total-tokens 100} {:prompt-tokens 0 :output-tokens 0})))

  (it "reports a request smaller than Isaac estimated without pretending it is a gap"
    (should= {:estimated-tokens      100
              :reported-prompt-tokens 50
              :unaccounted-tokens    -50
              :ratio                 0.5}
             (sut/reconcile {:total-tokens 100} {:prompt-tokens 50}))))
