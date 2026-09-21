(ns isaac.effort)

(def default-effort 7)

(defn effort->string [n]
  (cond
    (nil? n)  nil
    (zero? n) nil
    (<= n 3)  "low"
    (<= n 6)  "medium"
    :else     "high"))

(defn effort->adaptive-level
  "Maps Isaac's 0-10 effort knob to Anthropic adaptive output_config.effort levels."
  [n]
  (cond
    (nil? n)  nil
    (zero? n) nil
    (= n 10)  "max"
    (<= n 3)  "low"
    (<= n 6)  "medium"
    :else     "high"))

(defn resolve-effort
  "Resolves effort integer from the chain: session > crew > model > provider > 7.
   Each entity map arrives under its :defaults template, so a default ranks with
   the layer it is written on: :defaults :provider :effort loses to a model's,
   :defaults :crew :effort beats one. `provider-template` is the last word
   before 7, for the case where no provider resolves at all."
  [session crew-cfg model-cfg provider-cfg provider-template]
  (or (:effort session)
      (:effort crew-cfg)
      (:effort model-cfg)
      (:effort provider-cfg)
      (:effort provider-template)
      default-effort))
