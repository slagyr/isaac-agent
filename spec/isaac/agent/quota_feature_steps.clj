(ns isaac.agent.quota-feature-steps
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.agent.llm.auth.store :as auth-store]
    [isaac.agent.llm.http :as llm-http]
    [isaac.agent.quota :as quota]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(helper! isaac.agent.quota-feature-steps)

(defn oauth-logins-exist [table]
  (quota/clear-cache!)
  (llm-http/clear-outbound-requests!)
  (let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
        root (g/get :root)]
    (nexus/register! [:root] root)
    (doseq [row (:rows table)]
      (let [[provider access account] row]
        (auth-store/save-tokens! root provider
                                 {:access_token access :expires_in 36000} fs*)
        (when (seq account)
          (let [path (str root "/auth.json")
                data (json/parse-string (fs/slurp fs* path) true)]
            (fs/spit fs* path (json/generate-string
                                 (assoc-in data [(keyword provider) :account-id] account)))))))))

(defgiven "oauth logins exist:" isaac.agent.quota-feature-steps/oauth-logins-exist)

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [stubs (g/get :url-stubs)]
      (with-redefs [http/get (fn [url opts]
                               (llm-http/record-outbound-request! url (:headers opts))
                               (or (get stubs url) (throw (ex-info "No stubbed URL" {:url url}))))]
        (binding [quota/*now* (g/get :current-time)] (thunk)))
      (binding [quota/*now* (g/get :current-time)] (thunk)))))
