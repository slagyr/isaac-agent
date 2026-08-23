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

(defn matches?
  "True when allow-token covers wire-name. Exact token or ns/* family glob."
  [token wire]
  (let [canonical (wire-name token)]
    (cond
      (nil? canonical) false
      (glob-token? token) (and (string? wire) (str/starts-with? wire canonical))
      :else (= canonical wire))))

(defn allowed?
  "True when any allow-token covers the wire name. Nil allow-list is deny-all.
   :all is a policy token handled by callers that implement cascade (isaac-da0r)."
  [allow-tokens wire]
  (boolean (some #(matches? % wire) allow-tokens)))
