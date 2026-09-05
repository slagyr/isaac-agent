;; mutation-tested: 2026-05-06
(ns isaac.llm.http
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.llm.api.grover :as grover]
    [isaac.logger :as log]
    [isaac.bridge.cancellation :as bridge])
  (:import (java.net ConnectException)))

(def ^:private pending ::pending)
(def ^:private default-stream-idle-timeout-ms 90000)
(defonce ^:private outbound-requests* (atom []))

(defn clear-outbound-requests! []
  (reset! outbound-requests* []))

(defn outbound-requests []
  @outbound-requests*)

(defn- simulated-provider? [opts]
  (boolean (:simulate-provider opts)))

(defn- body-chars [body]
  (count (pr-str body)))

(defn- body-keys [body]
  (when (map? body)
    (sort (keys body))))

(defn- header-keys [headers]
  (when (seq headers)
    (sort (keys headers))))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- resolve-idle-timeout-ms [opts default-ms]
  (or (:stream-idle-timeout-ms opts) default-ms))

(defn- stream-stalled-result [elapsed-ms bytes-received retry-after-ms]
  (log/warn :llm/stream-stalled
            :elapsed-ms elapsed-ms
            :bytes-received bytes-received)
  {:error          :stream-stalled
   :unavailable?   true
   :reason         :stream-stalled
   :retry-after-ms (or retry-after-ms elapsed-ms)
   :elapsed-ms     elapsed-ms
   :bytes-received bytes-received})

(defn- idle-stalled? [activity]
  (when-let [idle-ms (:idle-timeout-ms activity)]
    (let [last-ms (or (some-> (:last-activity-ms activity) deref)
                      (:started-ms activity)
                      (now-ms))]
      (>= (- (now-ms) last-ms) idle-ms))))

(defn- activity-bytes [activity]
  (or (some-> (:bytes-received activity) deref) 0))

(defn- mark-stalled! [activity]
  (some-> (:stalled? activity) (reset! true)))

(defn- close-activity! [activity]
  (when-let [close! (:close! activity)]
    (close!)))

(defn- stalled-result [activity]
  (let [last-ms (or (some-> (:last-activity-ms activity) deref)
                    (:started-ms activity)
                    (now-ms))
        elapsed (- (now-ms) last-ms)]
    (stream-stalled-result elapsed
                           (activity-bytes activity)
                           (or (:retry-after-ms activity)
                               (:idle-timeout-ms activity)))))

(defn- cancellable-call
  ([session-key f]
   (cancellable-call session-key f nil))
  ([session-key f activity]
   (let [runner (future (f))]
     (loop []
       (let [result (deref runner 50 pending)]
         (cond
           (not= pending result)
           (if (or (some-> (:stalled? activity) deref)
                   (idle-stalled? activity)
                   (= :stream-stalled (:error result)))
             (or (when (= :stream-stalled (:error result)) result)
                 (stalled-result activity))
             result)

           (bridge/cancelled? session-key)
           (do
             (future-cancel runner)
             (close-activity! activity)
             {:error :cancelled})

           (idle-stalled? activity)
           (do
             (mark-stalled! activity)
             (close-activity! activity)
             (future-cancel runner)
             (stalled-result activity))

           :else (recur)))))))

(defn- cancelled-result [session-key]
  (when (bridge/cancelled? session-key)
    {:error :cancelled}))

(defn- close-body! [body]
  (try
    (.close body)
    (catch Exception _ nil)))

(defn- register-cancel-close! [session-key body]
  (let [closed? (atom false)
        close!  #(when (compare-and-set! closed? false true)
                   (close-body! body))]
    (bridge/on-cancel! session-key close!)
    close!))

(defn- log-http-request! [url headers body opts stream?]
  (swap! outbound-requests* conj {:body body :headers headers :stream stream? :url url})
  (log/debug :llm/http-request
             :body-chars (body-chars body)
             :body-keys  (body-keys body)
             :header-keys (header-keys headers)
             :session-key (:session-key opts)
             :simulate-provider (:simulate-provider opts)
             :stream stream?
             :timeout (:timeout opts)
             :url url))

(defn- log-http-response! [url headers _body stream? status response-body]
  (log/debug :llm/http-response
             :header-keys (header-keys headers)
             :response-body-chars (body-chars response-body)
             :response-body-keys  (body-keys response-body)
             :status status
             :stream stream?
             :url url))

(defn- log-http-error! [url headers body stream? result]
  (log/error :llm/http-error
             :body-chars (body-chars body)
             :body-keys  (body-keys body)
             :error (:error result)
             :header-keys (header-keys headers)
             :message (:message result)
             :response-body-chars (body-chars (:body result))
             :response-body-keys  (body-keys (:body result))
             :status (:status result)
             :stream stream?
             :url url))

(defn post-json!
  "POST JSON to a URL with headers. Returns parsed response or error map.
   Checks HTTP status codes: 401 -> :auth-failed, 4xx/5xx -> :api-error."
  [url headers body & [{:keys [session-key simulate-provider timeout]
                        :or   {timeout 120000} :as opts}]]
  (if (simulated-provider? {:simulate-provider simulate-provider})
    (do
      (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} false)
      (grover/post-json! simulate-provider url headers body))
    (cancellable-call session-key
                      #(try
                         (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} false)
                         (let [resp   (http/post url {:body    (json/generate-string body)
                                                      :headers headers
                                                      :timeout timeout
                                                      :throw   false})
                               parsed (json/parse-string (:body resp) true)]
                           (if (>= (:status resp) 400)
                             (let [result {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                                           :status   (:status resp)
                                           :body     parsed
                                           :_headers headers}]
                               (log-http-error! url headers body false result)
                               result)
                             (do
                               (log-http-response! url headers body false (:status resp) parsed)
                               parsed)))
                        (catch ConnectException _
                          (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                            (log-http-error! url headers body false result)
                            result))
                        (catch IllegalArgumentException _
                          (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                            (log-http-error! url headers body false result)
                            result))
                        (catch Exception e
                          (let [result {:error :unknown :message (.getMessage e)}]
                            (log-http-error! url headers body false result)
                            result)))
                      (when-let [idle-ms (resolve-idle-timeout-ms opts nil)]
                        {:idle-timeout-ms idle-ms
                         :started-ms      (now-ms)
                         :retry-after-ms  (:retry-after-ms opts)}))))

(defn process-sse-lines
  "Process SSE lines, calling on-chunk and accumulating via process-event.
   Returns the final accumulated value. Pure data transformation over lines."
  [lines on-chunk process-event initial]
  (reduce
    (fn [accumulated line]
      (cond
        (= "[DONE]" (str/trim (subs line 6)))
        (reduced accumulated)

        :else
        (let [data (json/parse-string (subs line 6) true)]
          (on-chunk data)
          (process-event data accumulated))))
    initial
    (filter #(str/starts-with? % "data: ") lines)))

(defn post-sse!
  "POST and process SSE stream. Calls on-chunk for each parsed event.
   process-event is (fn [data accumulated] -> accumulated) for custom accumulation."
  [url headers body on-chunk process-event initial & [{:keys [session-key simulate-provider timeout retry-after-ms]
                                                       :or   {timeout 120000} :as opts}]]
  (if (simulated-provider? {:simulate-provider simulate-provider})
    (do
      (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} true)
      (grover/post-sse! simulate-provider url headers body on-chunk process-event initial))
    (let [last-activity-ms (atom (now-ms))
          bytes-received   (atom 0)
          close!*          (atom nil)
          stalled?         (atom false)
          idle-ms          (resolve-idle-timeout-ms opts default-stream-idle-timeout-ms)
          activity         {:idle-timeout-ms  idle-ms
                            :last-activity-ms last-activity-ms
                            :bytes-received   bytes-received
                            :started-ms       (now-ms)
                            :retry-after-ms   (or retry-after-ms idle-ms)
                            :stalled?         stalled?
                            :close!           #(when-let [c @close!*] (c))}
          touch!           (fn [data]
                             (reset! last-activity-ms (now-ms))
                             (swap! bytes-received + (count (pr-str data)))
                             (on-chunk data))]
      (cancellable-call session-key
                        #(try
                           (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} true)
                           (let [resp (http/post url {:body    (json/generate-string body)
                                                      :headers headers
                                                      :timeout timeout
                                                      :as      :stream
                                                      :throw   false})]
                             (if (>= (:status resp) 400)
                               (let [result {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                                             :status   (:status resp)
                                             :body     (try (json/parse-string (slurp (:body resp)) true)
                                                            (catch Exception _ nil))
                                             :_headers headers}]
                                 (log-http-error! url headers body true result)
                                 result)
                               (let [body-stream (:body resp)
                                     close!      (register-cancel-close! session-key body-stream)]
                                 (reset! close!* close!)
                                 (with-open [rdr (io/reader body-stream)]
                                   (let [result (process-sse-lines (line-seq rdr) touch! process-event initial)]
                                     (close!)
                                     (or (cancelled-result session-key)
                                         (do
                                           (log-http-response! url headers body true (:status resp) result)
                                           result)))))))
                           (catch ConnectException _
                             (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                               (log-http-error! url headers body true result)
                               result))
                           (catch Exception e
                             (cond
                               (cancelled-result session-key)
                               (cancelled-result session-key)

                               @stalled?
                               (stalled-result activity)

                               :else
                               (let [result {:error :unknown :message (.getMessage e)}]
                                 (log-http-error! url headers body true result)
                                 result))))
                        activity))))

(defn post-ndjson-stream!
  "POST and process newline-delimited JSON stream (Ollama-style).
   Calls on-chunk for each parsed line. Returns the final chunk."
  [url headers body on-chunk & [{:keys [session-key timeout retry-after-ms]
                                 :or   {timeout 120000} :as opts}]]
  (let [last-activity-ms (atom (now-ms))
        bytes-received   (atom 0)
        close!*          (atom nil)
        stalled?         (atom false)
        idle-ms          (resolve-idle-timeout-ms opts default-stream-idle-timeout-ms)
        activity         {:idle-timeout-ms  idle-ms
                          :last-activity-ms last-activity-ms
                          :bytes-received   bytes-received
                          :started-ms       (now-ms)
                          :retry-after-ms   (or retry-after-ms idle-ms)
                          :stalled?         stalled?
                          :close!           #(when-let [c @close!*] (c))}
        touch!           (fn [chunk]
                           (reset! last-activity-ms (now-ms))
                           (swap! bytes-received + (count (pr-str chunk)))
                           (on-chunk chunk))]
    (cancellable-call session-key
                      #(try
                         (let [resp (http/post url {:body    (json/generate-string body)
                                                    :headers headers
                                                    :timeout timeout
                                                    :as      :stream
                                                    :throw   false})]
                           (if (>= (:status resp) 400)
                             {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                              :status   (:status resp)
                              :body     (try (json/parse-string (slurp (:body resp)) true)
                                             (catch Exception _ nil))
                              :_headers headers}
                             (let [body-stream (:body resp)
                                   close!      (register-cancel-close! session-key body-stream)]
                               (reset! close!* close!)
                               (with-open [rdr (io/reader body-stream)]
                                 (let [result (loop [last-chunk nil]
                                                (if-let [line (.readLine rdr)]
                                                  (if (str/blank? line)
                                                    (recur last-chunk)
                                                    (let [chunk (json/parse-string line true)]
                                                      (touch! chunk)
                                                      (recur chunk)))
                                                  last-chunk))]
                                   (close!)
                                   (or (cancelled-result session-key)
                                       result))))))
                         (catch ConnectException _
                           {:error :connection-refused :message (str "Could not connect to " url)})
                         (catch IllegalArgumentException _
                           {:error :connection-refused :message (str "Could not connect to " url)})
                         (catch Exception e
                           (cond
                             (cancelled-result session-key)
                             (cancelled-result session-key)

                             @stalled?
                             (stalled-result activity)

                             :else {:error :unknown :message (.getMessage e)})))
                      activity)))
