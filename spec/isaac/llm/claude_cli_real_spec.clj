(ns isaac.llm.claude-cli-real-spec
  "Spec-vs-reality smoke (isaac-kn7y): executes the ACTUAL claude binary and
   asserts it returns a real, non-empty answer. Stubs prove spec-conformance;
   only this proves spec-CORRECTNESS — it is the test that would have caught the
   bad `--disallowed-tools all` flag, the process-call bug, and the silent
   empty-success.

   Opt-in and self-skipping so normal CI never shells out or hits the network:
   runs only when ISAAC_CLAUDE_REAL is set AND the binary is installed AND a
   login is present; otherwise it reports `pending` (never a failure)."
  (:require
    [babashka.process :as process]
    [clojure.string :as str]
    [isaac.llm.api.claude-cli :as sut]
    [speclj.core :refer [describe it should should-not pending tags]]))

(defn- enabled? []
  (not (str/blank? (System/getenv "ISAAC_CLAUDE_REAL"))))

(defn- claude-installed? []
  (try
    (zero? (:exit @(process/process ["claude" "--version"]
                                    {:out :string :err :string})))
    (catch Exception _ false)))

(describe "claude-cli real binary smoke (isaac-kn7y)"
  (tags :real :slow)

  (it "returns a real, non-empty answer from a logged-in claude binary"
    (cond
      (not (enabled?))
      (pending "set ISAAC_CLAUDE_REAL=1 on a logged-in box to run the @real smoke")

      (not (claude-installed?))
      (pending "claude binary not installed")

      :else
      (let [res (sut/chat {:model    "sonnet"
                           :messages [{:role "user" :content "Reply with exactly the word: pong"}]}
                          "claude-code"
                          {:command "claude"})]
        (if (:unavailable? res)
          (pending (str "no claude login present: " (:message res)))
          (do
            (should-not (:error res))
            (should (seq (str/trim (or (get-in res [:message :content]) ""))))))))))
