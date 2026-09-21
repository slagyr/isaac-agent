(ns isaac.config.defaults
  "The one place that knows where a default is written.

   `:defaults :<entity>` is a template: it behaves exactly as if every entity of
   that kind had set those fields, so **where a default is written decides how
   it ranks**. `:defaults :provider :effort` is outranked by a model's or a
   crew's effort (today's behavior); `:defaults :crew :effort` would outrank
   every model's. To keep a field's rank, write its default on the lowest layer
   that carries the field.

   `:defaults :frequencies` is the odd one out: session selection, not an
   entity template.")

;; --- frequencies: session selection -----------------------------------------

(defn frequencies-template
  "Default session selection fields (:crew, :reach, …)."
  [cfg]
  (get-in cfg [:defaults :frequencies]))

(defn crew-id
  "The crew a session gets when nothing names one."
  [cfg]
  (get-in cfg [:defaults :frequencies :crew]))

;; --- crew template ----------------------------------------------------------

(defn crew-template [cfg] (get-in cfg [:defaults :crew]))

(defn model-id
  "The model a crew gets when it names none."
  [cfg]
  (get-in cfg [:defaults :crew :model]))

(defn context-mode [cfg] (get-in cfg [:defaults :crew :context-mode]))

(defn cycle-knobs
  "Default turn knobs (:limit, :checkpoint-every, …) for every crew."
  [cfg]
  (get-in cfg [:defaults :crew :cycle]))

(defn tools
  "Default tool configuration for every crew: :allow, :deny, :directories,
   :max-parallel."
  [cfg]
  (get-in cfg [:defaults :crew :tools]))

(defn tool-allow [cfg] (get-in cfg [:defaults :crew :tools :allow]))
(defn tool-deny [cfg] (get-in cfg [:defaults :crew :tools :deny]))
(defn tool-directories [cfg] (get-in cfg [:defaults :crew :tools :directories]))
(defn max-parallel [cfg] (get-in cfg [:defaults :crew :tools :max-parallel]))

;; --- model template ---------------------------------------------------------

(defn model-template [cfg] (get-in cfg [:defaults :model]))

;; --- provider template ------------------------------------------------------

(defn provider-template [cfg] (get-in cfg [:defaults :provider]))

(defn effort [cfg] (get-in cfg [:defaults :provider :effort]))
(defn history-retention [cfg] (get-in cfg [:defaults :provider :history-retention]))
(defn compaction [cfg] (get-in cfg [:defaults :provider :compaction]))
(defn stream-idle-timeout-ms [cfg] (get-in cfg [:defaults :provider :stream-idle-timeout-ms]))
(defn retry-after-ms [cfg] (get-in cfg [:defaults :provider :retry-after-ms]))
(defn auth-retry-ms [cfg] (get-in cfg [:defaults :provider :auth-retry-ms]))

;; --- tool output caps -------------------------------------------------------

(defn tool-caps
  "Tool output caps applied at the turn boundary. Nothing overrides these."
  [cfg]
  (select-keys (get-in cfg [:defaults :tools]) [:max-lines :max-bytes]))
