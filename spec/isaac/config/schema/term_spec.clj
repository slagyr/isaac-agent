(ns isaac.config.schema.term-spec
  (:require [clojure.string :as s]
            [isaac.config.schema.root :as config-schema]
            [isaac.config.schema.term :as sut]
            [speclj.core :refer [context describe it should should-contain should-not-contain should=]]))

(def ^:private plain {:color? false :width 80})
(def ^:private plain-no-paths {:color? false :paths? false :width 80})

(describe "schema.term"

  (context "base-type"

    (it "renders a map with only key-spec as 'map of K'"
      (should= "map of keyword"
               (#'sut/base-type {:type :map :key-spec {:type :keyword}})))

    (it "renders seq specs recursively"
      (should= "seq of string"
               (#'sut/base-type {:type :seq :spec {:type :string}})))

    (it "renders one-of specs as a comma-separated list"
      (should= "one of: string, int"
               (#'sut/base-type {:type :one-of :specs [{:type :string} {:type :int}]}))))

  (context "plain (no color) output"

    (it "renders a leaf type using the apron type name verbatim"
      (should-contain "string" (sut/spec->term {:type :string} plain))
      (should-contain "float" (sut/spec->term {:type :float} plain))
      (should-contain "long" (sut/spec->term {:type :long} plain)))

    (it "leaf includes description and example when present"
      (let [out (sut/spec->term {:type :int :description "a count" :example 42} plain)]
        (should-contain "int" out)
        (should-contain "a count" out)
        (should-contain "example: 42" out)))

    (it "renders a map as a header with one line per field"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string}
                                       :age  {:type :int}}}
                  plain)]
        (should-contain "age" out)
        (should-contain "int" out)
        (should-contain "name" out)
        (should-contain "string" out)))

    (it "sorts fields alphabetically"
      (let [out   (sut/spec->term
                    {:type :map :schema {:zeta {:type :string}
                                         :alpha {:type :string}}}
                    plain)
            alpha (s/index-of out "alpha")
            zeta  (s/index-of out "zeta")]
        (should (< alpha zeta))))

    (it "includes field description on its own line"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string
                                              :description "User's name."}}}
                  plain)]
        (should-contain "User's name." out)))

    (it "uses :description from config specs as the field description"
      (let [out (sut/spec->term config-schema/defaults plain)]
        (should-contain "Default crew member id" out)
        (should-contain "Default model alias" out)))

    (it "renders field paths as absolute using the path-prefix option"
      (let [out (sut/spec->term config-schema/root (assoc plain :path-prefix []))]
        (should-contain "defaults" out)
        (should-contain "crew" out)
        (should-contain "providers" out)))

    (it "prefixes paths with the configured path-prefix and wraps them in brackets"
      (let [out (sut/spec->term config-schema/crew (assoc plain :path-prefix ["crew" "value"]))]
        (should-contain "[crew.value.id]" out)
        (should-contain "[crew.value.model]" out)))

    (it ":paths? false suppresses field paths"
      (let [out (sut/spec->term config-schema/crew (assoc plain-no-paths :path-prefix ["crew" "value"]))]
        (should-not-contain "[crew.value.id]" out)))

    (it "marks required fields"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string :required? true}}}
                  plain)]
        (should-contain "required" out)))

    (it "shows example on its own line"
      (let [out (sut/spec->term
                  {:type :map :schema {:age {:type :int :example 30}}}
                  plain)]
        (should-contain "example: 30" out)))

    (it "shows default on its own line when present"
      (let [out (sut/spec->term config-schema/defaults plain)]
        (should-contain "default: \"main\"" out)
        (should-contain "default: \"llama\"" out)))

    (it "shows named ref with an arrow"
      (let [pet {:type :map :name :pet :schema {:name {:type :string}}}
            out (sut/spec->term
                  {:type :map :schema {:pet pet}}
                  plain)]
        (should-contain "map → pet" out)))

    (it "map with key-spec + value-spec renders 'map of K → V'"
      (let [spec {:type :map :schema {:crew {:type :map
                                             :key-spec   {:type :keyword}
                                             :value-spec {:type :map :name :crew-entity
                                                          :schema {:name {:type :string}}}}}}
            out  (sut/spec->term spec plain)]
        (should-contain "map of keyword → crew-entity" out)))

    (it "map with only value-spec renders 'map → V'"
      (let [spec {:type :map :schema {:counts {:type :map :value-spec {:type :int}}}}
            out  (sut/spec->term spec plain)]
        (should-contain "map → int" out)))

    (it "does not recurse into named sub-schemas when :deep? is not set"
      (let [pet  {:type :map :name :pet :schema {:species {:type :string}}}
            spec {:type :map :schema {:pet pet}}
            out  (sut/spec->term spec plain)]
        (should-contain "pet" out)
        (should-not-contain "species" out)))

    (it "renders every named sub-schema in its own section when :deep? is true"
      (let [pet  {:type :map :name :pet :schema {:species {:type :string :description "the kind"}}}
            spec {:type :map :schema {:pet pet}}
            out  (sut/spec->term spec (assoc plain :deep? true))]
        (should-contain "pet" out)
        (should-contain "species" out)
        (should-contain "the kind" out))))

  (context "map-with-value-spec rendering"

    (it "renders map-of-id as title, description, and key/value rows"
      (let [out (sut/spec->term (get-in config-schema/root [:schema :crew])
                                (assoc plain :path-prefix ["crew"]))]
        (should-contain "[crew] crew table schema" out)
        (should-contain "Crew member configurations" out)
        (should-contain "key" out)
        (should-contain "value" out)
        (should-contain "[crew.key]" out)
        (should-contain "[crew.value]" out)))

    (it "renders description after the key/value rows"
      (let [out  (sut/spec->term (get-in config-schema/root [:schema :crew])
                                 (assoc plain :path-prefix ["crew"]))
            desc (s/index-of out "Crew member configurations")
            row  (s/index-of out "[crew.key]")]
        (should (< row desc))))

    (it "does not render the crew entity fields when showing the collection wrapper"
      (let [out (sut/spec->term (get-in config-schema/root [:schema :crew])
                                (assoc plain :path-prefix ["crew"]))]
        (should-not-contain "Model alias" out)
        (should-not-contain "System prompt" out))))

  (context "titles"

    (it "builds the root title from the entity name when no path-prefix is given"
      (let [out (sut/spec->term config-schema/root plain)]
        (should-contain "[isaac] isaac schema" out)))

    (it "adds the entity-name suffix when path and entity differ"
      (let [out (sut/spec->term config-schema/provider
                                (assoc plain :path-prefix ["providers" "value"]))]
        (should-contain "[providers.value] provider schema" out)))

    (it "uses the collection's :name as the suffix when present"
      (let [out (sut/spec->term (get-in config-schema/root [:schema :crew])
                                (assoc plain :path-prefix ["crew"]))]
        (should-contain "[crew] crew table schema" out)))

    (it "falls back to map suffix when a collection has no :name"
      (let [out (sut/spec->term {:type :map :value-spec {:type :string}}
                                (assoc plain :path-prefix ["stuff"]))]
        (should-contain "[stuff] map schema" out)))

    (it "uses just [path] schema for a leaf"
      (let [out (sut/spec->term {:type :string :description "a string"}
                                (assoc plain :path-prefix ["some" "path"]))]
        (should-contain "[some.path] schema" out))))

  (context "colored output"

    (it "emits ANSI escape codes when :color? is true"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string}}}
                  {:color? true :width 80})]
        (should-contain "\033[" out)))

    (it "suppresses ANSI codes when :color? is false"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string}}}
                  plain)]
        (should-not-contain "\033[" out))))

  (context "section headings"

    (it "underlines the heading with a rule"
      (let [out (sut/spec->term
                  {:type :map :schema {:name {:type :string}}}
                  plain)]
        (should-contain "schema\n──" out))))

  (context "options-from"

    (it "renders an options: line when :options-from resolves to values"
      (let [out (sut/spec->term {:type :string :options-from :things}
                                (assoc plain :options-resolvers {:things (fn [] ["foo" "bar"])}))]
        (should-contain "options: bar, foo" out)))

    (it "sorts options alphabetically"
      (let [out (sut/spec->term {:type :string :options-from :things}
                                (assoc plain :options-resolvers {:things (fn [] ["zebra" "ant" "mango"])}))]
        (should-contain "options: ant, mango, zebra" out)))

    (it "omits the options: line when no resolver is registered for the key"
      (let [out (sut/spec->term {:type :string :options-from :things} plain)]
        (should-not-contain "options:" out)))

    (it "omits the options: line when the resolver returns nil"
      (let [out (sut/spec->term {:type :string :options-from :things}
                                (assoc plain :options-resolvers {:things (fn [] nil)}))]
        (should-not-contain "options:" out)))

    (it "renders options: in a field-block when the spec is inside a map schema"
      (let [out (sut/spec->term {:type :map :schema {:kind {:type :string :options-from :things}}}
                                (assoc plain :options-resolvers {:things (fn [] ["alpha" "beta"])}))]
        (should-contain "options: alpha, beta" out))))

  (context "manifest variant annotation"

    (it "prefixes a field's description with [variant] when :isaac/variant is set"
      (let [out (sut/spec->term {:type :map :schema {:loft {:type :string
                                                            :isaac/variant "telly"
                                                            :description "the roof"}}}
                                plain)]
        (should-contain "[telly] the roof" out)))

    (it "renders [variant] alone when there is no description"
      (let [out (sut/spec->term {:type :map :schema {:loft {:type :string
                                                            :isaac/variant "telly"}}}
                                plain)]
        (should-contain "[telly]" out)))))
