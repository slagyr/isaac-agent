(ns isaac.util.ws-client-spec
  (:require
    [isaac.util.ws-client :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all])
  (:import
    (java.nio ByteBuffer)
    (java.util.concurrent LinkedBlockingQueue)))

(defn- make-listener []
  (let [incoming      (LinkedBlockingQueue.)
        closed?       (atom false)
        close-payload (atom nil)]
    {:incoming      incoming
     :closed?       closed?
     :close-payload close-payload
     :listener      (sut/ws-listener incoming closed? close-payload)}))

(describe "ACP WebSocket transport"

  (describe "loopback-pair"

    (it "delivers messages from client to server"
      (let [{:keys [client server]} (sut/loopback-pair)]
        (sut/ws-send! client "{\"id\":1}")
        (should= "{\"id\":1}" (sut/ws-receive! server 50))
        (sut/ws-close! client)
        (sut/ws-close! server)))

    (it "returns a timeout sentinel when polling while still open"
      (let [{:keys [server]} (sut/loopback-pair)]
        (should= ::sut/timeout (sut/ws-receive! server 1))))

    (it "returns nil after close when no more messages are available"
      (let [{:keys [client server]} (sut/loopback-pair)]
        (sut/ws-close! client)
        (sut/ws-close! server)
        (should= nil (sut/ws-receive! server 10))))

    (it "ws-close-payload returns nil for loopback"
      (let [{:keys [client]} (sut/loopback-pair)]
        (should-be-nil (sut/ws-close-payload client)))))

  (describe "ws-listener"

    (it "onPing calls request-ws-next! to keep backpressure flowing"
      (let [calls              (atom 0)
            {:keys [listener]} (make-listener)]
        (with-redefs [sut/request-ws-next! (fn [_] (swap! calls inc))]
          (.onPing listener nil (ByteBuffer/allocate 0)))
        (should= 1 @calls)))

    (it "onPong calls request-ws-next! to keep backpressure flowing"
      (let [calls              (atom 0)
            {:keys [listener]} (make-listener)]
        (with-redefs [sut/request-ws-next! (fn [_] (swap! calls inc))]
          (.onPong listener nil (ByteBuffer/allocate 0)))
        (should= 1 @calls)))

    (it "onClose stores status-code and reason in close-payload"
      (let [{:keys [listener close-payload]} (make-listener)]
        (.onClose listener nil 4000 "Session timed out")
        (should= {:status-code 4000 :reason "Session timed out"} @close-payload)))

    (it "onClose marks connection closed and enqueues sentinel"
      (let [{:keys [listener closed? incoming]} (make-listener)]
        (.onClose listener nil 1000 "normal")
        (should @closed?)
        (should= 1 (.size incoming))))

    (it "onError logs the throwable at error level"
      (let [{:keys [listener]} (make-listener)
            error              (ex-info "connection reset" {})]
        (log/capture-logs
          (.onError listener nil error)
          (let [entry (first (filter #(= :ws/error (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= :error (:level entry))
            (should= error (:throwable entry))))))

    (it "onError marks connection closed"
      (let [{:keys [listener closed?]} (make-listener)]
        (.onError listener nil (ex-info "boom" {}))
        (should @closed?))))

  (describe "written-lines"

    (it "returns complete non-blank lines from a writer"
      (let [writer (java.io.StringWriter.)]
        (.write writer "{\"id\":1}\n\n{\"id\":2}\n")
        (should= ["{\"id\":1}" "{\"id\":2}"] (sut/written-lines writer)))))

  )
