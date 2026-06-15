(ns isaac.slash.echo
  (:require
    [clojure.string :as str]))

(declare handle-echo)

(defn- parse-args [input]
  (second (str/split (str/trim input) #"\s+" 2)))

(defn echo-command []
  {:description "Echo the input back unchanged"
   :handler     handle-echo})

(defn handle-echo [_session-key input _ctx]
  {:type    :command
   :command :echo
   :message (or (parse-args input) "")})
