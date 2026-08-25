(ns isaac.tool.names
  "Canonical tool identity: config uses namespaced keywords; the model sees ns__name."
  (:require
    [clojure.string :as str]))

(def POLICY_ALL :all)

(defn- token-parts [token]
  (cond
    (keyword? token)
    (if-let [ns (namespace token)]
      {:ns ns :name (name token)}
      {:name (name token)})

    (string? token)
    (cond
      (str/includes? token "/")
      (let [idx (str/index-of token "/")]
        {:ns (subs token 0 idx) :name (subs token (inc idx))})

      (str/includes? token "__")
      (let [idx  (str/index-of token "__")
            name (subs token (+ idx 2))]
        {:ns (subs token 0 idx) :name (if (str/blank? name) "*" name)})

      :else nil)

    :else nil))

(defn config-token
  "Wire fs__read → :fs/read. Namespace glob prefix fs__ → :fs/*. Passes namespaced keywords through."
  [token]
  (if-let [{:keys [ns name]} (token-parts token)]
    (keyword ns (if (str/blank? name) "*" name))
    (when (keyword? token) token)))

(defn config-token?
  "True when token is :all or a namespaced keyword (including ns/*)."
  [token]
  (or (= POLICY_ALL token)
      (boolean (and (keyword? token) (namespace token)))))

(defn wire-name
  "Config :fs/read → wire fs__read. Namespace glob :fs/* → prefix fs__.
   Unqualified tokens stay as their name (test fixtures / legacy)."
  [token]
  (if-let [{:keys [ns name]} (token-parts token)]
    (cond
      (nil? ns) name
      (= "*" name) (str ns "__")
      :else (str ns "__" name))
    (when (string? token) token)))

(defn glob-token? [token]
  (let [{:keys [name]} (token-parts token)]
    (= "*" name)))

(defn- family-prefix [token]
  (let [bare (cond
               (and (keyword? token) (nil? (namespace token))) (name token)
               (and (string? token)
                    (not (str/includes? token "/"))
                    (not (str/includes? token "__"))) token
               :else nil)]
    (when bare (str bare "__"))))

(defn matches?
  "True when allow-token covers wire-name. Exact token, ns/* family glob,
   or an unqualified family prefix (exec → exec__run)."
  [token wire]
  (let [canonical (wire-name token)
        prefix    (family-prefix token)]
    (cond
      (and prefix (string? wire) (str/starts-with? wire prefix)) true
      (nil? canonical) false
      (glob-token? token) (and (string? wire) (str/starts-with? wire canonical))
      :else (= canonical wire))))

(defn allowed?
  "True when any allow-token covers the wire name. Nil allow-list is deny-all.
   :all is a policy token handled by callers that implement cascade (isaac-da0r)."
  [allow-tokens wire]
  (boolean (some #(matches? % wire) allow-tokens)))

(defn policy-list
  "Normalize an :allow or :deny value. :all is the list (not a list item).
   A sequential of tokens is returned as-is. Nil is an empty list."
  [value]
  (cond
    (= POLICY_ALL value) [POLICY_ALL]
    (sequential? value)  (vec value)
    :else                []))

(defn covers?
  "True when policy covers the wire name. :all covers every name.
   A sequential of tokens uses allowed? matching (exact / ns/*)."
  [policy wire]
  (let [tokens (policy-list policy)]
    (boolean (some (fn [token]
                     (or (= POLICY_ALL token)
                         (matches? token wire)))
                   tokens))))

(defn cascade-allowed?
  "Four-step last-match-wins cascade (isaac-da0r):
     1. global :allow
     2. global :deny
     3. crew :deny
     4. crew :allow
   Empty config (missing :allow) is deny-all. Crew overlays; a crew
   :deny adds a deny and does not drop global denies. Nil crew-tools
   means the crew omitted :tools and inherits the global result."
  [global-tools crew-tools wire]
  (let [global-tools (or global-tools {})
        allowed?     (covers? (:allow global-tools) wire)
        allowed?     (if (covers? (:deny global-tools) wire) false allowed?)
        allowed?     (if (and (map? crew-tools)
                              (covers? (:deny crew-tools) wire))
                       false
                       allowed?)]
    (if (and (map? crew-tools)
             (covers? (:allow crew-tools) wire))
      true
      allowed?)))

(defn- normalize-path [path]
  (when path
    (-> path
        str
        (str/replace #"\\+" "/")
        (str/replace #"/+" "/")
        (#(if (and (> (count %) 1) (str/ends-with? % "/"))
            (subs % 0 (dec (count %)))
            %)))))

(defn- expand-directory-token [token {:keys [cwd quarters]}]
  (cond
    (#{:cwd "cwd"} token) cwd
    (#{:quarters "quarters"} token) quarters
    (#{:role "role"} token) cwd
    (string? token) token
    :else nil))

(defn- path-under? [prefix path]
  (let [prefix (normalize-path prefix)
        path   (normalize-path path)]
    (boolean
      (and prefix path
           (or (= prefix path)
               (str/starts-with? path (str prefix "/")))))))

(defn- prefix-length [prefix]
  (count (or (normalize-path prefix) "")))

(defn- matching-grants
  "Collect {:op :allow|:deny :len n :layer :global|:crew} for grants covering path."
  [layer op tokens path ctx]
  (keep (fn [token]
          (when-let [prefix (expand-directory-token token ctx)]
            (when (path-under? prefix path)
              {:op op :len (prefix-length prefix) :layer layer})))
        (or tokens [])))

(defn path-allowed?
  "Longest matching directory prefix wins. Same-length uses da0r cascade
   order (global allow, global deny, crew deny, crew allow). Empty config
   is deny-all. Crew overlays; omitting :directories inherits global."
  [global-dirs crew-dirs path ctx]
  (let [global-dirs (or global-dirs {})
        grants      (concat
                      (matching-grants :global :allow (:allow global-dirs) path ctx)
                      (matching-grants :global :deny  (:deny  global-dirs) path ctx)
                      (when (map? crew-dirs)
                        (concat
                          (matching-grants :crew :deny  (:deny  crew-dirs) path ctx)
                          (matching-grants :crew :allow (:allow crew-dirs) path ctx))))]
    (if (empty? grants)
      false
      (let [max-len (apply max (map :len grants))
            winners (filter #(= max-len (:len %)) grants)
            rank    (fn [{:keys [layer op]}]
                      (case [layer op]
                        [:global :allow] 1
                        [:global :deny]  2
                        [:crew :deny]    3
                        [:crew :allow]   4
                        0))
            winner  (apply max-key rank winners)]
        (= :allow (:op winner))))))
