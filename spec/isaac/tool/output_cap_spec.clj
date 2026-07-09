(ns isaac.tool.output-cap-spec
  (:require
    [clojure.string :as str]
    [isaac.tool.output-cap :as sut]
    [speclj.core :refer :all]))

(describe "output-cap"

  (describe "cap-result"

    (it "returns content unchanged when within both caps"
      (let [content "line 1\nline 2\nline 3"]
        (should= content (sut/cap-result content 10 1000))))

    (it "truncates by line count with head-tail and marker"
      (let [lines   (map #(str "line " %) (range 1 21))
            content (str/join "\n" lines)
            result  (sut/cap-result content 5 100000)]
        (should (str/includes? result "[ 15 lines truncated; line cap hit ]"))
        (should (str/includes? result "line 1"))
        (should (str/includes? result "line 20"))))

    (it "truncates by byte count with head-tail and marker"
      (let [content (str/join "" (repeat 200 "x"))
            result  (sut/cap-result content 10000 100)]
        (should (str/includes? result "[ 100 bytes truncated; byte cap hit ]"))))

    (it "line cap fires before byte cap when content would trip both"
      (let [lines   (map #(str "line " %) (range 1 21))
            content (str/join "\n" lines)
            result  (sut/cap-result content 5 50)]
        (should (str/includes? result "line cap hit"))
        (should-not (str/includes? result "byte cap hit"))))

    (it "reports the correct truncated byte count in the marker"
      (let [content (str/join "" (repeat 200 "x"))
            result  (sut/cap-result content 10000 100)]
        (should (re-find #"\[ 100 bytes truncated" result))))

    (it "reports the correct truncated line count in the marker"
      (let [lines   (map #(str "line " %) (range 1 21))
            content (str/join "\n" lines)
            result  (sut/cap-result content 5 100000)]
        (should (re-find #"\[ 15 lines truncated" result))))

    (it "truncates at the halved default line cap when no override is passed"
      (let [lines   (map #(str "line " %) (range 1 1021))
            content (str/join "\n" lines)
            result  (sut/cap-result content sut/default-max-output-lines sut/default-max-output-bytes)]
        (should (str/includes? result "[ 20 lines truncated; line cap hit ]"))
        (should (str/includes? result "line 1"))
        (should (str/includes? result "line 1020"))))))
