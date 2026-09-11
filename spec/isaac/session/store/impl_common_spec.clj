(ns isaac.session.store.impl-common-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-dir "/test/impl-common")
(def ^:private session-id "sess")
(def ^:private crew-id "cordelia")

(defn- fs* [] (nexus/get :fs))

(def ^:private entries
  [{:type "message" :id "a" :role "user"      :content "first"}
   {:type "message" :id "b" :role "assistant" :content "second reported a CI regression"}
   {:type "message" :id "c" :role "user"      :content "third"}])

(describe "impl-common nested session paths"

  (it "nests a session directory under its crew"
    (should= "/test/impl-common/sessions/cordelia/sess"
             (sut/session-dir test-dir crew-id session-id)))

  (it "session.edn lives under the nested session directory"
    (should= "/test/impl-common/sessions/cordelia/sess/session.edn"
             (sut/session-edn-path test-dir crew-id session-id)))

  (it "current.ednl lives under the nested session directory"
    (should= "/test/impl-common/sessions/cordelia/sess/current.ednl"
             (sut/current-transcript-path test-dir crew-id session-id)))

  (it "index.edn lives at sessions/index.edn"
    (should= "/test/impl-common/sessions/index.edn"
             (sut/index-path test-dir)))

  (it "turn.edn lives under the nested session directory"
    (should= "/test/impl-common/sessions/cordelia/sess/turn.edn"
             (sut/turn-marker-path test-dir crew-id session-id)))
  )

(describe "impl-common sessions index"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "writes and reads an index row keyed by session id"
    (let [fs* (fs*)]
      (sut/write-index! fs* test-dir
                        {"lantern-room" {:crew "cordelia" :session-policy :episodes
                                         :updated-at "2026-03-01T10:00:00"}})
      (let [idx (sut/read-index fs* test-dir)]
        (should= "cordelia" (get-in idx ["lantern-room" :crew]))
        (should= :episodes (get-in idx ["lantern-room" :session-policy])))))

  (it "locates a nested session via the index"
    (let [fs* (fs*)]
      (sut/write-index! fs* test-dir
                        {"lantern-room" {:crew "cordelia" :session-policy :episodes}})
      (should= {:crew "cordelia" :session-policy :episodes}
               (select-keys (sut/locate-session test-dir "lantern-room" fs*)
                            [:crew :session-policy]))
      (should= (sut/session-dir test-dir "cordelia" "lantern-room")
               (:dir (sut/locate-session test-dir "lantern-room" fs*)))))

  (it "scans nested directories when the index does not know the session and repairs the index"
    (let [fs*  (fs*)
          path (sut/session-edn-path test-dir "cordelia" "lantern-room")]
      (sut/mkdirs*! fs* (sut/session-dir test-dir "cordelia" "lantern-room"))
      (sut/atomic-spit! fs* path
                        (sut/write-edn {:id "lantern-room" :name "Lantern Room"
                                        :crew "cordelia" :session-policy :chronicle}))
      (sut/write-index! fs* test-dir {"harbor-log" {:crew "main" :session-policy :chronicle}})
      (let [loc (sut/locate-session test-dir "lantern-room" fs*)]
        (should= "cordelia" (:crew loc))
        (should= :chronicle (:session-policy loc)))
      (let [idx (sut/read-index fs* test-dir)]
        (should= "main" (get-in idx ["harbor-log" :crew]))
        (should= "cordelia" (get-in idx ["lantern-room" :crew]))
        (should= :chronicle (get-in idx ["lantern-room" :session-policy])))))

  (it "refuses a create when the id already belongs to another crew"
    (let [fs* (fs*)]
      (sut/write-index! fs* test-dir
                        {"lantern-room" {:crew "cordelia" :session-policy :episodes}})
      (try
        (sut/assert-unique-session-id! test-dir "lantern-room" "main" fs*)
        (should-fail "expected collision")
        (catch clojure.lang.ExceptionInfo e
          (should (re-find #"belongs to crew cordelia" (ex-message e)))
          (should= :crew-collision (:reason (ex-data e)))))))
  )

(describe "impl-common ednl transcript"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (sut/write-transcript! test-dir session-id entries (fs*))
      (example)))

  (it "round-trips EDN maps as one object per line"
    (should= entries (sut/read-transcript-raw test-dir session-id (fs*))))

  (it "read-edn-line reads a keyword map from pr-str"
    (should= {:type "message" :id "a"}
             (sut/read-edn-line (pr-str {:type "message" :id "a"}))))

  (it "reads the last EDNL object from a tail window"
    (should= (last entries)
             (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir session-id))))

  (it "grows the tail window when the last object is larger than the first probe"
    (let [big {:type "message" :id "z" :content (apply str (repeat 5000 "x"))}
          all (conj entries big)]
      (sut/write-transcript! test-dir session-id all (fs*))
      (should= big (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir session-id)))))

  (it "still finds the last object when read-bytes returns at most 8192 bytes"
    (let [payload (apply str "690: :on-change (fn [_])\n"
                             (repeat 400 ":value (or (:title footer) \"\")\n"))
          big     {:type "message" :id "z" :content payload}
          path    (sut/current-transcript-path test-dir session-id)
          orig    fs/read-bytes]
      (sut/write-transcript! test-dir session-id (conj entries big) (fs*))
      (with-redefs [fs/read-bytes (fn [fs path off len]
                                    (orig fs path off (min (long len) 8192)))]
        (should= "z" (:id (sut/last-transcript-entry (fs*) path))))))

  (it "skips a torn trailing line and returns the previous object"
    (let [path (sut/current-transcript-path test-dir session-id)]
      (fs/spit (fs*) path "{:type \"message\", :id \"torn\", :content \"unterminated\n" :append true)
      (should= "c" (:id (sut/last-transcript-entry (fs*) path)))))

  (it "returns nil when the transcript file is missing"
    (should-be-nil (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir "missing")))))

(describe "impl-common write-edn"

  (it "serializes a session header with pr-str"
    (should= "{:type \"session\", :id \"abc12345\", :cwd \"/tmp\"}\n"
             (sut/write-edn {:type "session" :id "abc12345" :cwd "/tmp"})))

  (it "write-transcript! persists EDN lines"
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (sut/write-transcript! test-dir session-id [{:type "session" :id "abc12345"}] (fs*))
      (should= ["{:type \"session\", :id \"abc12345\"}"]
               (str/split-lines (fs/slurp (fs*) (sut/current-transcript-path test-dir session-id)))))))

(describe "impl-common concurrent append-entry!"

  (it "keeps one complete EDN object per line when two threads append at the same instant"
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (let [fs*     (fs*)
            orig    sut/spit*!
            done    (java.util.concurrent.CountDownLatch. 2)
            errors  (atom [])
            payload (apply str (repeat 80 "See [the TDD skill] "))
            port    {:type "message" :id "port" :content payload}
            star    {:type "message" :id "starboard" :content payload}]
        (sut/write-transcript! test-dir session-id [{:type "session" :id "hdr"}] fs*)
        (with-redefs [sut/spit*! (fn [fs path content & options]
                                   (doseq [ch (str content)]
                                     (apply orig fs path (str ch) options)))]
          (future
            (try (sut/append-entry! test-dir session-id port fs*)
                 (catch Throwable t (swap! errors conj t))
                 (finally (.countDown done))))
          (future
            (try (sut/append-entry! test-dir session-id star fs*)
                 (catch Throwable t (swap! errors conj t))
                 (finally (.countDown done))))
          (.await done))
        (should= [] (map str @errors))
        (let [text  (fs/slurp fs* (sut/current-transcript-path test-dir session-id))
              lines (vec (remove str/blank? (str/split-lines text)))]
          (should= 3 (count lines))
          (doseq [line lines]
            (let [parsed (try (sut/read-edn-line line) (catch Exception e e))]
              (should (map? parsed)))))))))

(describe "impl-common turn markers"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "reads a legacy sessions/turns/<id>.edn marker when the product path is absent"
    (let [fs*    (fs*)
          marker {:source :comm :started-at "2026-04-21T09:59:30Z"}]
      (fs/mkdirs fs* (str test-dir "/sessions/turns"))
      (fs/spit fs* (sut/legacy-turn-marker-path test-dir session-id) (pr-str marker))
      (let [found (sut/turn-markers* test-dir fs*)]
        (should= 1 (count found))
        (should= :comm (:source (first found)))
        (should= session-id (:session-id (first found))))))

  (it "prefers the product sessions/<id>/turn.edn marker over the legacy path"
    (let [fs*     (fs*)
          product {:source :hail :started-at "2026-04-21T10:00:00Z"}
          legacy  {:source :comm :started-at "2026-04-21T09:00:00Z"}]
      (fs/mkdirs fs* (str test-dir "/sessions/" session-id))
      (fs/mkdirs fs* (str test-dir "/sessions/turns"))
      (fs/spit fs* (sut/turn-marker-path test-dir session-id) (pr-str product))
      (fs/spit fs* (sut/legacy-turn-marker-path test-dir session-id) (pr-str legacy))
      (let [found (sut/turn-markers* test-dir fs*)]
        (should= 1 (count found))
        (should= :hail (:source (first found)))))))

(describe "impl-common chronicle-transcript"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "concatenates the frozen compacted prefix then compacted current"
    (let [fs*     (fs*)
          header  {:type "session" :id "hdr"}
          m1      {:type "message" :id "m1" :message {:role "user" :content "First"}}
          m2      {:type "message" :id "m2" :message {:role "assistant" :content "Second"}}
          m3      {:type "message" :id "m3" :message {:role "user" :content "Third"}}
          compact {:type "compaction" :id "c1" :summary "Summary"}]
      (sut/write-ednl! fs* (sut/frozen-transcript-path test-dir session-id 0)
                       [header m1 m2])
      (sut/write-transcript! test-dir session-id [compact m3] fs*)
      (let [chronicle (sut/read-chronicle test-dir session-id fs*)
            types     (mapv :type chronicle)]
        ;; retain freezes only the compacted prefix; kept tail lives only
        ;; in the new current.
        (should= ["session" "message" "message" "compaction" "message"] types)
        (should= 5 (count chronicle))
        (should= "m3" (:id (last chronicle)))))))

(defn- valid-sidecar [id]
  {:id         id
   :key        id
   :name       id
   :origin     {:kind :cli}
   :created-at "2026-09-05T04:00:00"
   :updated-at "2026-09-05T04:00:00"
   :tags       #{}})

(describe "impl-common conversation persist lock"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "serializes persist and read of the same session-key so a reader never sees a blank sidecar"
    (let [fs*       (fs*)
          path      (sut/session-edn-path test-dir session-id)
          orig-spit sut/spit*!
          errors    (atom [])
          blanks    (atom [])
          done      (java.util.concurrent.CountDownLatch. 2)
          identity-defaults (fn [entry] entry)]
      (sut/mkdirs*! fs* (sut/session-dir test-dir session-id))
      (sut/atomic-spit! fs* path (sut/write-edn (valid-sidecar session-id)))
      (with-redefs [sut/spit*! (fn [fs p content & options]
                                 (when-not (:append (apply hash-map options))
                                   (apply orig-spit fs p "" options))
                                 (apply orig-spit fs p content options))]
        (future
          (try
            (dotimes [_ 8]
              (sut/with-persist-lock session-id
                (fn []
                  (sut/spit*! fs* path (sut/write-edn (valid-sidecar session-id))))))
            (catch Throwable t (swap! errors conj t))
            (finally (.countDown done))))
        (future
          (try
            (dotimes [_ 20]
              (let [[_ entry] (sut/read-session-entry identity-defaults test-dir session-id fs*)]
                (when (or (nil? (:name entry)) (str/blank? (:name entry)))
                  (swap! blanks conj :skeleton))))
            (catch Throwable t (swap! errors conj t))
            (finally (.countDown done))))
        (.await done))
      (should= [] (map str @errors))
      (should= [] @blanks))))

(describe "impl-common crash-safe whole-file writes"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "leaves the previous ednl intact when a rewrite crashes after writing the temp file"
    (let [fs*     (fs*)
          path    (sut/current-transcript-path test-dir session-id)
          orig    sut/spit*!
          crashed (atom false)]
      (sut/write-ednl! fs* path entries)
      (with-redefs [sut/spit*! (fn [fs p content & options]
                                 (apply orig fs p content options)
                                 (when (str/includes? (str p) ".tmp")
                                   (reset! crashed true)
                                   (throw (ex-info "crash mid-write" {:path p}))))]
        (try
          (sut/write-ednl! fs* path [{:type "message" :id "z"}])
          (catch clojure.lang.ExceptionInfo e
            (should= "crash mid-write" (ex-message e)))))
      (should @crashed)
      (should= entries (sut/read-ednl fs* path))))

  (it "leaves the previous session.edn intact when a sidecar rewrite crashes after writing the temp file"
    (let [fs*  (fs*)
          path (sut/session-edn-path test-dir session-id)
          orig sut/spit*!
          prior (valid-sidecar session-id)]
      (sut/mkdirs*! fs* (sut/session-dir test-dir session-id))
      (sut/atomic-spit! fs* path (sut/write-edn prior))
      (with-redefs [sut/spit*! (fn [fs p content & options]
                                 (apply orig fs p content options)
                                 (when (str/includes? (str p) ".tmp")
                                   (throw (ex-info "crash mid-write" {:path p}))))]
        (try
          (sut/atomic-spit! fs* path (sut/write-edn (assoc prior :name "mutated")))
          (catch clojure.lang.ExceptionInfo _)))
      (should= prior (edn/read-string (fs/slurp fs* path))))))

(describe "impl-common unreadable existing session"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "throws :session/unreadable when an existing session.edn is blank"
    (let [fs*  (fs*)
          path (sut/session-edn-path test-dir session-id)]
      (sut/mkdirs*! fs* (sut/session-dir test-dir session-id))
      (sut/spit*! fs* path "")
      (try
        (sut/read-session-entry identity test-dir session-id fs*)
        (should-fail "expected unreadable")
        (catch clojure.lang.ExceptionInfo e
          (should= :session/unreadable (:reason (ex-data e)))))))

  (it "throws :session/unreadable when an existing session.edn is unparseable"
    (let [fs*  (fs*)
          path (sut/session-edn-path test-dir session-id)]
      (sut/mkdirs*! fs* (sut/session-dir test-dir session-id))
      (sut/spit*! fs* path "{:id \"sess\" :name")
      (try
        (sut/read-session-entry identity test-dir session-id fs*)
        (should-fail "expected unreadable")
        (catch clojure.lang.ExceptionInfo e
          (should= :session/unreadable (:reason (ex-data e)))))))
  )
