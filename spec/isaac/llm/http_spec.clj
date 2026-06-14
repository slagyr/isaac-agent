(ns isaac.llm.http-spec
  (:require
     [babashka.http-client :as http]
     [cheshire.core :as json]
     [isaac.llm.api.grover :as grover]
     [isaac.logger :as log]
     [isaac.bridge.cancellation :as bridge]
     [isaac.llm.http :as sut]
     [speclj.core :refer :all])
  (:import (java.io ByteArrayInputStream)
           (java.net ConnectException)))

(defn- mock-response [status body]
  {:status status :body (json/generate-string body)})

(defn- mock-stream-response [status lines]
  {:status status
   :body   (ByteArrayInputStream. (.getBytes (apply str (map #(str % "\n") lines))))})

(describe "LLM HTTP"

  (before (grover/reset-queue!))

  (describe "post-json!"

    (it "returns parsed response on success"
      (with-redefs [http/post (fn [_ _] (mock-response 200 {:result "ok"}))]
        (let [result (sut/post-json! "http://test" {} {})]
          (should= "ok" (:result result)))))

    (it "returns :auth-failed on 401"
      (with-redefs [http/post (fn [_ _] (mock-response 401 {:error {:message "bad key"}}))]
        (let [result (sut/post-json! "http://test" {} {})]
          (should= :auth-failed (:error result))
          (should= 401 (:status result)))))

    (it "returns :api-error on other 4xx/5xx"
      (with-redefs [http/post (fn [_ _] (mock-response 500 {:error {:message "server down"}}))]
        (let [result (sut/post-json! "http://test" {} {})]
          (should= :api-error (:error result))
          (should= 500 (:status result)))))

    (it "returns :connection-refused on ConnectException"
      (with-redefs [http/post (fn [_ _] (throw (ConnectException.)))]
        (let [result (sut/post-json! "http://test" {} {})]
          (should= :connection-refused (:error result)))))

    (it "returns :connection-refused on invalid port (IllegalArgumentException)"
      (let [result (sut/post-json! "http://localhost:99999" {} {})]
        (should= :connection-refused (:error result))))

    (it "returns :unknown on other exceptions"
      (with-redefs [http/post (fn [_ _] (throw (Exception. "boom")))]
        (let [result (sut/post-json! "http://test" {} {})]
          (should= :unknown (:error result))
          (should= "boom" (:message result)))))

    (it "passes headers and serialized body"
      (let [captured (atom nil)]
        (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts}) (mock-response 200 {}))]
          (sut/post-json! "http://test/api" {"x-key" "abc"} {:model "m"})
          (should= "http://test/api" (:url @captured))
          (should= {"x-key" "abc"} (:headers (:opts @captured)))
          (should= {:model "m"} (json/parse-string (:body (:opts @captured)) true)))))

    (it "includes request headers in error response"
      (with-redefs [http/post (fn [_ _] (mock-response 401 {:error "bad key"}))]
        (let [result (sut/post-json! "http://test" {"x-api-key" "sk-test"} {})]
          (should= :auth-failed (:error result))
          (should= {"x-api-key" "sk-test"} (:_headers result)))))

    (it "includes request headers in 5xx error response"
      (with-redefs [http/post (fn [_ _] (mock-response 500 {:error "down"}))]
        (let [result (sut/post-json! "http://test" {"Authorization" "Bearer tok"} {})]
          (should= :api-error (:error result))
          (should= {"Authorization" "Bearer tok"} (:_headers result)))))

    (it "returns cancelled when the session is cancelled during the request"
      (let [turn    (bridge/begin-turn! "http-cancel")
            started (promise)
            release (promise)]
        (try
          (with-redefs [http/post (fn [_ _]
                                    (deliver started true)
                                    @release
                                    (mock-response 200 {:result "late"}))]
            (let [result (future (sut/post-json! "http://test" {} {} {:session-key "http-cancel"}))]
              (should= true (deref started 1000 nil))
              (bridge/cancel! "http-cancel")
              (deliver release true)
              (deref result 1000 nil)
              (should= true (bridge/cancelled? "http-cancel"))))
          (finally
            (deliver release true)
            (bridge/end-turn! "http-cancel" turn)))))

    (it "routes simulated provider JSON calls through grover"
      (grover/enqueue! [{:model "gpt-5" :type "text" :content "ok"}])
      (let [result (sut/post-json! "https://api.openai.com/v1/chat/completions"
                                   {"content-type" "application/json"}
                                   {:model "gpt-5" :messages []}
                                   {:simulate-provider "openai"})]
        (should= "ok" (get-in result [:choices 0 :message :content]))
        (should= "https://api.openai.com/v1/chat/completions" (:url (grover/last-provider-request))))))

    (it "logs compact JSON request and error details without secrets"
      (log/capture-logs
        (with-redefs [http/post (fn [_ _] (mock-response 400 {:error {:message "too big"}}))]
          (let [result (sut/post-json! "http://test/api" {"Authorization" "Bearer tok"} {:model "m" :messages []})
                request-entry (first (filter #(= :llm/http-request (:event %)) @log/captured-logs))
                error-entry   (first (filter #(= :llm/http-error (:event %)) @log/captured-logs))]
            (should= :api-error (:error result))
            (should= "http://test/api" (:url request-entry))
            (should= ["Authorization"] (:header-keys request-entry))
            (should-not-contain "Bearer tok" (pr-str request-entry))
            (should= [:messages :model] (:body-keys request-entry))
            (should (> (:body-chars request-entry) 0))
            (should= 400 (:status error-entry))
            (should= [:error] (:response-body-keys error-entry))
            (should (> (:response-body-chars error-entry) 0))
            (should-not-contain "too big" (pr-str error-entry))
            (should-not-contain "Bearer tok" (pr-str error-entry))))))

  (describe "process-sse-lines"

    (it "accumulates SSE data lines via process-event"
      (let [chunks (atom [])
            result (sut/process-sse-lines
                     ["data: {\"text\":\"Hello\"}" "data: {\"text\":\" world\"}"]
                     (fn [data] (swap! chunks conj data))
                     (fn [data acc] (str acc (:text data)))
                     "")]
        (should= "Hello world" result)
        (should= 2 (count @chunks))))

    (it "stops at [DONE] marker"
      (let [chunks (atom [])
            result (sut/process-sse-lines
                     ["data: {\"text\":\"Hi\"}" "data: [DONE]" "data: {\"text\":\"ignored\"}"]
                     (fn [data] (swap! chunks conj data))
                     (fn [data acc] (str acc (:text data)))
                     "")]
        (should= "Hi" result)
        (should= 1 (count @chunks))))

    (it "skips non-data lines"
      (let [result (sut/process-sse-lines
                     ["event: ping" "data: {\"n\":1}" ": comment" "data: {\"n\":2}"]
                     (fn [_])
                     (fn [data acc] (+ acc (:n data)))
                     0)]
        (should= 3 result))))

  (describe "post-sse!"

    (it "processes SSE stream and returns accumulated result"
      (with-redefs [http/post (fn [_ _] (mock-stream-response 200
                                           ["data: {\"text\":\"A\"}"
                                            "data: {\"text\":\"B\"}"
                                            "data: [DONE]"]))]
        (let [chunks (atom [])
              result (sut/post-sse! "http://test" {} {} (fn [d] (swap! chunks conj d))
                       (fn [data acc] (str acc (:text data))) "")]
          (should= "AB" result)
          (should= 2 (count @chunks)))))

    (it "returns :auth-failed on 401"
      (with-redefs [http/post (fn [_ _] (mock-stream-response 401 ["{\"error\":\"bad\"}"]))]
        ;; Need a proper error body for the slurp path
        (with-redefs [http/post (fn [_ _] {:status 401
                                            :body   (ByteArrayInputStream.
                                                      (.getBytes (json/generate-string {:error "bad"})))})]
          (let [result (sut/post-sse! "http://test" {} {} identity (fn [_ a] a) nil)]
            (should= :auth-failed (:error result))))))

    (it "returns :connection-refused on ConnectException"
      (with-redefs [http/post (fn [_ _] (throw (ConnectException.)))]
        (let [result (sut/post-sse! "http://test" {} {} identity (fn [_ a] a) nil)]
          (should= :connection-refused (:error result)))))

    (it "includes request headers in error response"
      (with-redefs [http/post (fn [_ _] {:status 401
                                          :body   (ByteArrayInputStream.
                                                    (.getBytes (json/generate-string {:error "bad"})))})]
        (let [result (sut/post-sse! "http://test" {"Authorization" "Bearer tok"} {}
                       identity (fn [_ a] a) nil)]
          (should= :auth-failed (:error result))
          (should= {"Authorization" "Bearer tok"} (:_headers result)))))

    (it "routes simulated provider SSE calls through grover"
      (grover/enqueue! [{:model "gpt-5.4" :type "text" :content "Hello"}])
      (let [chunks (atom [])
            result (sut/post-sse! "https://api.openai.com/v1/responses"
                                  {"content-type" "application/json"}
                                  {:model "gpt-5.4" :stream true :input [{:role "user" :content "hi"}]}
                                  (fn [chunk] (swap! chunks conj chunk))
                                  (fn [data acc]
                                    (case (:type data)
                                      "response.output_text.delta" (update acc :content str (:delta data))
                                      acc))
                                  {:content ""}
                                   {:simulate-provider "chatgpt"})]
        (should= "Hello" (:content result))
        (should= "https://api.openai.com/v1/responses" (:url (grover/last-provider-request)))
        (should= 2 (count @chunks)))))

    (it "closes an in-flight SSE stream and returns cancelled when the session is cancelled"
      (let [turn         (bridge/begin-turn! "sse-cancel")
            started      (promise)
            body-closed? (atom false)
            output       (doto (java.io.PipedOutputStream.))
            input        (java.io.PipedInputStream. output)
            chunks       (atom [])]
        (try
          (with-redefs [http/post (fn [_ _]
                                    (deliver started true)
                                    (future
                                      (.write output (.getBytes "data: {\"text\":\"Hello\"}\n"))
                                      (.flush output))
                                    {:status 200
                                     :body   (proxy [java.io.InputStream] []
                                               (read
                                                 ([] (.read input))
                                                 ([b] (.read input b))
                                                 ([b off len] (.read input b off len)))
                                               (close []
                                                 (reset! body-closed? true)
                                                 (.close input)))})]
            (let [result (future (sut/post-sse! "http://test" {} {}
                                                (fn [d]
                                                  (swap! chunks conj d))
                                                (fn [data acc] (str acc (:text data)))
                                                ""
                                                {:session-key "sse-cancel"}))]
              (should= true (deref started 1000 nil))
              (bridge/cancel! "sse-cancel")
              (should= :cancelled (:error (deref result 1000 nil)))
              (should= true @body-closed?)
              (should (<= (count @chunks) 1))))
          (finally
            (try (.close output) (catch Exception _ nil))
            (bridge/end-turn! "sse-cancel" turn)))))

    (it "logs compact SSE request and response details without secrets"
      (log/capture-logs
        (with-redefs [http/post (fn [_ _] (mock-stream-response 200
                                             ["data: {\"text\":\"A\"}"
                                              "data: [DONE]"]))]
          (let [result         (sut/post-sse! "http://test/sse" {"Authorization" "Bearer tok" "x-trace" "1"} {:model "m"}
                                identity (fn [data acc] (str acc (:text data))) "")
                request-entry  (first (filter #(= :llm/http-request (:event %)) @log/captured-logs))
                response-entry (first (filter #(= :llm/http-response (:event %)) @log/captured-logs))]
            (should= "A" result)
            (should= true (:stream request-entry))
            (should= "http://test/sse" (:url request-entry))
            (should= ["Authorization" "x-trace"] (:header-keys request-entry))
            (should-not-contain "Bearer tok" (pr-str request-entry))
            (should= [:model] (:body-keys request-entry))
            (should= 200 (:status response-entry))
            (should= [:response-body-chars :response-body-keys] (sort (keys (select-keys response-entry [:response-body-chars :response-body-keys]))))
            (should (> (:response-body-chars response-entry) 0))
            (should-not-contain "Bearer tok" (pr-str response-entry))))))

  (describe "post-ndjson-stream!"

    (it "processes newline-delimited JSON stream"
      (with-redefs [http/post (fn [_ _] (mock-stream-response 200
                                           ["{\"message\":{\"content\":\"Hi\"},\"done\":false}"
                                            "{\"message\":{\"content\":\"!\"},\"done\":true}"]))]
        (let [chunks (atom [])
              result (sut/post-ndjson-stream! "http://test" {} {} (fn [c] (swap! chunks conj c)))]
          (should= true (:done result))
          (should= 2 (count @chunks)))))

    (it "skips blank lines"
      (with-redefs [http/post (fn [_ _] (mock-stream-response 200
                                           ["{\"n\":1}" "" "{\"n\":2}"]))]
        (let [chunks (atom [])
              result (sut/post-ndjson-stream! "http://test" {} {} (fn [c] (swap! chunks conj c)))]
          (should= 2 (:n result))
          (should= 2 (count @chunks)))))

    (it "returns :connection-refused on ConnectException"
      (with-redefs [http/post (fn [_ _] (throw (ConnectException.)))]
        (let [result (sut/post-ndjson-stream! "http://test" {} {} identity)]
          (should= :connection-refused (:error result)))))

    (it "returns :auth-failed on 401"
      (with-redefs [http/post (fn [_ _] {:status 401
                                          :body   (ByteArrayInputStream.
                                                    (.getBytes (json/generate-string {:error "unauthorized"})))})]
        (let [result (sut/post-ndjson-stream! "http://test" {} {} identity)]
          (should= :auth-failed (:error result))
          (should= 401 (:status result)))))

    (it "returns :api-error on other 4xx"
      (with-redefs [http/post (fn [_ _] {:status 404
                                          :body   (ByteArrayInputStream.
                                                    (.getBytes (json/generate-string {:error "not found"})))})]
        (let [result (sut/post-ndjson-stream! "http://test" {} {} identity)]
          (should= :api-error (:error result))
          (should= 404 (:status result)))))

    (it "returns :connection-refused on IllegalArgumentException (invalid port)"
      (with-redefs [http/post (fn [_ _] (throw (IllegalArgumentException. "port out of range:99999")))]
        (let [result (sut/post-ndjson-stream! "http://test:99999" {} {} identity)]
          (should= :connection-refused (:error result)))))))
