(ns isaac.config.cli.spec-support
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus])
  (:import (java.io BufferedReader StringReader StringWriter)))

(defn with-cli-env [f]
  (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
    (binding [*out*  (StringWriter.)
              *err*  (StringWriter.)
              *in*   (BufferedReader. (StringReader. ""))]
      (f))))
