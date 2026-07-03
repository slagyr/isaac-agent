(ns isaac.comm.render-spec
  (:require
    [isaac.comm.render :as sut]
    [speclj.core :refer :all]))

(describe "comm.render"

  (it "extracts plain text from string chunks"
    (should= "hello" (sut/chunk-text "hello")))

  (it "extracts plain text from preformatted tagged chunks"
    (should= "Session Status" (sut/chunk-text (sut/preformatted-chunk "Session Status"))))

  (it "fences preformatted blocks for markdown clients"
    (let [chunk (sut/preformatted-chunk "Crew main")]
      (should (re-find #"```text\nCrew main\n```" (sut/present-for-markdown chunk)))))

  (it "passes normal streaming text through for markdown clients"
    (should= "token" (sut/present-for-markdown "token"))))