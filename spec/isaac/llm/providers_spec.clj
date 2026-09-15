(ns isaac.llm.providers-spec
  (:require
    [isaac.fs :as fs]
    [isaac.llm.providers :as sut]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.nexus :as nexus]
    [speclj.core :refer [after around describe it should-be-nil should-contain should=]]))

(describe "isaac.llm.providers"

  (marigold.agent/with-apis)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (describe "template"

    (it "returns nil for unknown providers"
      (should-be-nil (sut/template "mystery"))
      (should-be-nil (sut/template "")))

    (it "returns messages config for anthropic"
      (let [d (sut/template "anthropic")]
        (should= "messages" (:api d))
        (should= "https://api.anthropic.com" (:base-url d))
        (should= "api-key" (:auth d))))

    (it "returns ollama config with default base-url and no auth"
      (let [d (sut/template "ollama")]
        (should= "ollama" (:api d))
        (should= "http://localhost:11434" (:base-url d))
        (should= "none" (:auth d))
        (should= nil (:models d))))

    (it "returns chat-completions config for openai with api-key auth"
      (let [d (sut/template "openai")]
        (should= "chat-completions" (:api d))
        (should= "https://api.openai.com/v1" (:base-url d))
        (should= "api-key" (:auth d))
        (should-be-nil (:models d))))

    (it "returns chat-completions config for grok with api-key auth"
      (let [d (sut/template "xai")]
        (should= "chat-completions" (:api d))
        (should= "https://api.x.ai/v1" (:base-url d))
        (should= "api-key" (:auth d))))

    (it "returns responses config for chatgpt with oauth-device"
      (let [d (sut/template "chatgpt")]
        (should= "responses" (:api d))
        (should= "oauth-device" (:auth d))
        (should= "https://chatgpt.com/backend-api/codex" (:base-url d))
        (should= "https://auth.openai.com" (get-in d [:oauth :issuer]))
        (should= "/api/accounts/deviceauth/usercode" (get-in d [:oauth :device-path]))
        (should-be-nil (:models d))))

    (it "returns chat-completions config for grok with oauth-device"
      (let [d (sut/template "grok")]
        (should= "chat-completions" (:api d))
        (should= "oauth-device" (:auth d))
        (should= "https://api.x.ai/v1" (:base-url d))
        (should= "https://auth.x.ai" (get-in d [:oauth :issuer]))
        (should= "/oauth2/device/code" (get-in d [:oauth :device-path]))))

    (it "returns grover config with none auth and empty models"
      (let [d (sut/template "grover")]
        (should= "grover" (:api d))
        (should= "none" (:auth d))
        (should= nil (:models d)))))

  (describe "defaults"

    (it "returns nil for manifest-only providers"
      (should-be-nil (sut/defaults "openai"))))

  (describe "grover-defaults"

    (it "returns nil for unknown providers"
      (should-be-nil (sut/grover-defaults "mystery")))

    (it "adds :simulate-provider and :api-key grover for api-key providers"
      (let [d (sut/grover-defaults "openai")]
        (should= "openai" (:simulate-provider d))
        (should= "grover" (:api-key d))
        (should= "chat-completions" (:api d))
        (should= "https://api.openai.com/v1" (:base-url d))))

    (it "adds :simulate-provider and :api-key grover for grok"
      (let [d (sut/grover-defaults "xai")]
        (should= "xai" (:simulate-provider d))
        (should= "grover" (:api-key d))
        (should= "chat-completions" (:api d))))

    (it "adds :simulate-provider but no :api-key for oauth-device providers"
      (let [d (sut/grover-defaults "chatgpt")]
        (should= "chatgpt" (:simulate-provider d))
        (should-be-nil (:api-key d))
        (should= "responses" (:api d))
        (should= "oauth-device" (:auth d))))

    )

  (describe "known-providers"

    (it "includes all built-in provider names"
      (let [known (sut/known-providers)]
        (should-contain "anthropic" known)
        (should-contain "grover" known)
        (should-contain "ollama" known)
        (should-contain "openai" known)
        (should-contain "chatgpt" known)
        (should-contain "xai" known))))

  (describe "registry"

    (after (sut/unregister! marigold/starcore))

    (it "registers and exposes a provider template"
      (sut/register! marigold/starcore marigold/starcore-provider)
      (should= (select-keys marigold/starcore-provider [:api :base-url])
               (select-keys (sut/template marigold/starcore) [:api :base-url])))

    (it "resolves a user-defined provider override on top of a built-in provider"
      (let [cfg {:providers {:anthropic {:api-key "corp-secret"}}}
            p   (sut/lookup cfg nil "anthropic")]
        (should= "messages" (:api p))
        (should= "https://api.anthropic.com" (:base-url p))
        (should= "corp-secret" (:api-key p))))

    (it "resolves :type inheritance from a built-in provider"
      (let [cfg {:providers {:corp-anthropic {:type     :anthropic
                                              :base-url "https://corp.example"
                                              :api-key  "corp-secret"}}}
            p   (sut/lookup cfg nil "corp-anthropic")]
        (should= "messages" (:api p))
        (should= "https://corp.example" (:base-url p))
        (should= "corp-secret" (:api-key p))))

    (it "resolves a user provider inheriting from a module-declared provider"
      (let [cfg          {:providers {:fizzy-staging {:type :kombucha :api-key "staging-key"}}}
            module-index {:isaac.providers.kombucha {:manifest {:isaac.agent/provider-template {:kombucha {:template {:api      "chat-completions"
                                                                                                                       :base-url "https://api.kombucha.test/v1"
                                                                                                                       :auth     "api-key"
                                                                                                                       :models   ["kombucha-large" "kombucha-small"]}}}}}}
            p            (sut/lookup cfg module-index "fizzy-staging")]
        (should= "chat-completions" (:api p))
        (should= "https://api.kombucha.test/v1" (:base-url p))
        (should= "staging-key" (:api-key p))))))
