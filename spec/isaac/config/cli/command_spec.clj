(ns isaac.config.cli.command-spec
  (:require
    [isaac.cli.registry :as registry]
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.module.loader :as module-loader]
    [speclj.core :refer :all]))

(def ^:private test-home "/test/config-cli")
(def ^:private test-root (str test-home "/.isaac"))

(describe "CLI Config dispatcher"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (describe "dispatch"

    (it "prints help and returns 0 with --help"
      (should= 0 (sut/run {:root test-root} ["--help"])))

    (it "prints help and returns 0 when no arguments are given"
      (should= 0 (sut/run {:root test-root} []))
      (should-contain "Usage: isaac config" (str *out*)))

    (it "returns 1 for an unknown subcommand"
      (should= 1 (sut/run {:root test-root} ["mystery"]))
      (should-contain "Unknown config subcommand: mystery" (str *err*)))

    (it "rejects the old --sources flag"
      (should= 1 (sut/run {:root test-root} ["--sources"]))
      (should-contain "Unknown option: \"--sources\"" (str *err*)))

    (it "routes 'help <subcommand>' to the subcommand's own help page"
      (should= 0 (sut/run {:root test-root} ["help" "validate"]))
      (should-contain "Usage: isaac config validate" (str *out*))))

  (describe "help text"

    (it "lists set and unset subcommands"
      (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["--help"])))]
        (should-contain "set <config-path> <value> Set a value at a config path" output)
        (should-contain "unset <config-path>       Remove a value at a config path" output)))

    (it "documents both config and schema path vocabularies"
      (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["--help"])))]
        (should-contain "config path" output)
        (should-contain "schema path" output)
        (should-contain "slash-mode" output)
        (should-contain "/crew/Almighty Bob/model" output))))

  (describe "registry integration"

    (it "registers the config command"
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should-not-be-nil (registry/get-command "config")))))
