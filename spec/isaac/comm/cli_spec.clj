(ns isaac.comm.cli-spec
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.cli :as sut]
    [speclj.core :refer :all]))

(describe "CLI channel"

  (it "prints text chunks directly to stdout"
    (let [output (with-out-str
                   (comm/on-text-chunk sut/channel "agent:main:cli:direct:user1" "Hello")
                   (comm/on-text-chunk sut/channel "agent:main:cli:direct:user1" " world"))]
      (should= "Hello world" output)))

  (it "prints tool call indicator"
    (let [output (with-out-str
                   (comm/on-tool-call sut/channel "agent:main:cli:direct:user1" {:name "read_file"}))]
      (should-contain "[tool call: read_file]" output)))

  (it "prints a trailing newline at turn end"
    (let [output (with-out-str
                   (comm/on-turn-end sut/channel "agent:main:cli:direct:user1" {:content "done"}))]
      (should= "\n" output))))
