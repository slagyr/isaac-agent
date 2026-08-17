(ns isaac.episodes.ids-spec
  (:require
    [isaac.episodes.ids :as sut]
    [speclj.core :refer :all]))

(describe "isaac.episodes.ids"

  (it "formats timestamped ids from ISO timestamps"
    (with-redefs [sut/chaos-suffix (constantly "ab12")]
      (should= "2026-01-02-0304-ab12"
               (sut/timestamped-id "2026-01-02T03:04:05"))))

  (it "accepts epoch millis"
    (with-redefs [sut/chaos-suffix (constantly "zz9")]
      (should= "1970-01-01-0000-zz9"
               (sut/timestamped-id 0))))

  (it "falls back to now when timestamp missing"
    (with-redefs [sut/chaos-suffix (constantly "x")
                  sut/now-ms (constantly 0)]
      (should= "1970-01-01-0000-x"
               (sut/timestamped-id nil))))
  )
