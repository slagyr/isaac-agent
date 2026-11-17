(ns isaac.agent.log-steps
  "Assertions on the full captured structured log entry, complementing
   foundation's field-based log assertions."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defthen helper!]]
    [isaac.foundation.logger :as log]))

(helper! isaac.agent.log-steps)

(defn log-entries-containing-count [expected text]
  (g/should= (parse-long expected)
             (count (filter #(str/includes? (pr-str %) text) (log/get-entries)))))

(defthen #"the log has (\d+) entries matching \"([^\"]+)\"" isaac.agent.log-steps/log-entries-containing-count)
