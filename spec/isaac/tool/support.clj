(ns isaac.tool.support
  (:require
    [clojure.java.io :as io]))

(def test-dir (str (System/getProperty "user.dir") "/target/test-tools"))

(defn clean! []
  (let [dir (io/file test-dir)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f))))
  (.mkdirs (io/file test-dir)))

(defn write-file! [name content]
  (let [path (str test-dir "/" name)]
    (.mkdirs (.getParentFile (io/file path)))
    (spit path content)))

(defn read-file [name]
  (slurp (str test-dir "/" name)))

(defn set-mtime! [name iso-instant]
  (.setLastModified (io/file (str test-dir "/" name))
                    (.toEpochMilli (java.time.Instant/parse iso-instant))))
