(ns isaac.agent.llm.followup
  (:import (java.io File)))

(defn image? [result]
  (and (map? result) (= "image" (:type result))))

(defn image-note [result]
  (format "[image: %s, %s, %d bytes]"
          (.getName (File. ^String (:path result))) (:media-type result) (:bytes result)))

(defn image-url [result]
  (str "data:" (:media-type result) ";base64," (:data result)))

(defn image-followup [result format-image]
  (when (image? result)
    {:role "user" :content (format-image result)}))

(defn map-tool-results [tool-calls tool-results f]
  (mapv f tool-calls tool-results))

(defn append-followup-messages [request assistant-msg result-msgs]
  (into (conj (vec (:messages request)) assistant-msg) result-msgs))

(defn raw-tool-call-followup-messages [request assistant-msg tool-calls tool-results]
  (let [result-msgs (map-tool-results tool-calls tool-results
                                      (fn [_tc result]
                                        {:role    "tool"
                                         :content (if (image? result) (image-note result) result)}))
        images      (keep #(image-followup % (fn [image]
                                                [{:type "image_url" :image_url {:url (image-url image)}}]))
                          tool-results)]
    (append-followup-messages request assistant-msg (concat result-msgs images))))
