(ns isaac.agent.log-steps-spec
  (:require
    [isaac.agent.log-steps :as sut]
    [isaac.foundation.logger :as log]
    [speclj.core :refer :all]))

(describe "full log entry text count"
  (it "counts text found in nested fields, not just the message"
    (let [output (log/output)]
      (try
        (log/set-output! :memory)
        (log/clear-entries!)
        (log/warn :comm/outbound-refused :comm :tannoy :target "stowaway")
        (sut/log-entries-containing-count "1" "stowaway")
        (sut/log-entries-containing-count "0" "Lantern is lit")
        (should-throw (sut/log-entries-containing-count "0" "stowaway"))
        (finally
          (log/clear-entries!)
          (log/set-output! output))))))
