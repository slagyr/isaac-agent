(ns isaac.bridge.core
  "Bridge dispatches charges and slash commands.

  Reply layering: core formatters and slash :message strings are plain text at
  ground level. Fixed-width slash output (e.g. /status) is tagged as
  :preformatted in on-chatter so markdown comms can fence it; CLI and LLM
  paths stay raw. See isaac.comm.render."
  (:require
    [clojure.string :as str]
    [isaac.bridge.status :as status]
    [isaac.bridge.suspend :as suspend]
    [isaac.comm.render :as render]
    [isaac.conversation.router :as conversation]
    [isaac.charge :as charge]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.drive.observer :as observer]
    [isaac.drive.turn :as turn]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]
    [isaac.nexus :as nexus]
    [isaac.prompt.catalog :as prompt-catalog]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]
    [isaac.slash.builtin :as slash-builtin]
    [isaac.slash.registry :as slash-registry]
    [isaac.turn.queue :as turn-queue]
    [isaac.turnstile :as turnstile]))

;; region ----- Helpers -----

(defn resolve-session-cwd
  "Resolves session cwd from the cascade: explicit override > crew > channel default.
   explicit-cwd: user-specified override (highest priority).
   crew-cfg: crew config map; may contain :cwd.
   channel-default: the channel's automatic fallback (lowest priority)."
  [explicit-cwd crew-cfg channel-default]
  (or explicit-cwd (:cwd crew-cfg) channel-default))

(defn- unknown-session-crew-message [session-key crew-id origin]
  (let [kind (:kind origin)]
    (str "unknown crew on session " session-key ": " crew-id
         (cond
           (= :cli kind)                      "\npass --crew to override"
           (contains? #{:webhook :cron} kind) nil
           :else                              "\nsend /crew <name> to change crew"))))

(defn- no-model-message [crew-id]
  (str "no model configured for crew: " crew-id))

(defn- reject-turn [session-key crew-id reason message]
  (log/warn :drive/turn-rejected :session session-key :crew crew-id :reason reason)
  {:error reason :message message})

(defn- refuse-dispatch [session-key]
  (log/warn :dispatch/refused :reason :session-in-flight :session session-key)
  {:dispatched? false :reason :session-in-flight})

(defn- reply-chunk [result]
  (if (contains? result :data)
    (render/preformatted-chunk (status/format-status (:data result)))
    (:message result)))

(defn- reply-result [session-key ch result]
  (let [chunk  (reply-chunk result)
        output (render/chunk-text chunk)]
    (when ch
      (comm/on-chatter ch session-key nil chunk)
      (comm/on-turn-end ch session-key (assoc result
                                              :content output
                                              :format  (render/chunk-format chunk))))
    (assoc result :content output :format (render/chunk-format chunk))))

(defn- autonomous-origin? [origin]
  (contains? #{:hail :cron} (:kind origin)))

(defn- prompt-catalog-opts [ctx]
  {:config    (:config ctx)
   :cwd       (:cwd ctx)
   :fs        (or (nexus/get :fs) (fs/instance))
   :root (or (get-in ctx [:config :root])
                  (:root ctx)
                  (nexus/get :root))})

(defn- unknown-command-result [name args]
  {:type    :command
   :command :unknown
   :message (str "unknown command: "
                 (if (str/blank? args)
                   (str "/" name)
                   name))})

(defn- ensure-session! [request]
  (let [session-store* (or (:session-store request) (nexus/get-in [:sessions :store]))
        cfg            (or (when (map? (:config request)) (:config request)) (loader/snapshot "turn dispatch entry — falls back to ambient config when charge carries none") {})
        crew-id        (or (:crew request) (get-in cfg [:defaults :crew]) "main")
        crew-cfg       (get (:crew cfg) crew-id)
        request        (conversation/route-conversation! (assoc request :crew-cfg crew-cfg))
        session-key    (:session-key request)
        resolved-cwd   (resolve-session-cwd (:cwd request) crew-cfg nil)]
    (if (and session-key (lifecycle/episodes-crew? cfg crew-id))
      (let [resolved (lifecycle/resolve-thread!
                       {:root          (or (get-in cfg [:root]) (:root request) (nexus/get :root))
                        :crew          crew-id
                        :thread        session-key
                        :session-store session-store*
                        :cfg           cfg
                        :cwd           resolved-cwd
                        :origin        (:origin request)})]
        (lifecycle/maybe-recall-at-open!
          resolved
          {:query         (:input request)
           :cfg           cfg
           :root          (or (get-in cfg [:root]) (:root request) (nexus/get :root))
           :crew          crew-id
           :session-store session-store*})
        (assoc request :session-key (:session-key resolved)))
      (do
        (when (and session-key
                   (nil? (store/get-session session-store* session-key))
                   (or (:origin request) resolved-cwd))
          (session-ctx/create-with-resolved-behavior!
            session-key {:crew          crew-id
                         :cwd           resolved-cwd
                         :origin        (:origin request)
                         :config        cfg
                         :session-store session-store*}))
        request))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Slash Command Handlers -----

(defn- handle-slash [session-key input ctx]
  (let [{:keys [args name]} (slash-builtin/parse-command input)]
    (if-let [command (slash-registry/lookup name (:module-index ctx))]
      {:action :reply
       :result ((:handler command) session-key input ctx)}
      (if-let [{:keys [input]} (prompt-catalog/resolve-command-prompt (prompt-catalog-opts ctx) name args)]
        {:action :turn
         :charge (assoc ctx :input input)}
        (if (autonomous-origin? (:origin ctx))
          {:action :turn
           :charge ctx}
          {:action :reply
           :result (unknown-command-result name args)})))))

;; endregion ^^^^^ Slash Command Handlers ^^^^^

;; region ----- Triage -----

(defn slash-command?
  "Returns true if input begins with a slash."
  [input]
  (and (string? input) (str/starts-with? input "/")))

(defn- route-charge! [c]
  (let [ch          (:comm c)
        session-key (:session-key c)]
    (cond
      (charge/slash? c)
      (let [{:keys [action charge result]} (handle-slash session-key (:input c) c)]
        (case action
          :reply {:result (reply-result session-key ch result)}
          :turn  {:charge charge}
          {:error :invalid-slash-action}))

      (charge/unresolved? c)
      {:result (reject-turn session-key (:crew c) (:charge/reason c)
                            (case (:charge/reason c)
                              :unknown-crew (unknown-session-crew-message session-key (:crew c) (:origin c))
                              :no-model     (no-model-message (:crew c))
                              "resolution failed"))}

      :else
      {:charge c})))

(defn- marker-source [charge]
  (let [kind (:kind (:origin charge))]
    (cond
      (= :hail kind) :hail
      (= :cron kind) :cron
      (:comm charge) :comm
      :else          :cli)))

(defn- turn-marker
  "The durable resume ROUTING for an in-flight turn (isaac-7li9): source, the hail
   delivery id / embedded delivery payload when present, and started-at. Resolved
   values (model, etc.) are deliberately NOT stored — they re-resolve at resume."
  [charge]
  (let [origin   (:origin charge)
        delivery (:hail-delivery charge)]
    (cond-> {:source     (marker-source charge)
             :started-at (System/currentTimeMillis)}
      (:hail-id origin) (assoc :delivery-id (str (:hail-id origin)))
      delivery          (assoc :attempts (:attempts delivery) :delivery delivery))))

(defn record-turn-marker!
  "The bridge is the single writer of durable turn markers (isaac-7li9). Callers
   (comm dispatch here, the hail delivery worker) hand a charge; the bridge builds
   the resume-routing marker from it and persists it via the SessionStore."
  [store session-key charge]
  (store/record-turn-marker! store session-key (turn-marker charge)))

(defn clear-turn-marker! [store session-key]
  (suspend/release-turn-marker! store session-key))

(defn- maybe-live-seal! [charge result]
  (when (and charge
             (not= :cli (:kind (:origin charge)))
             (not (:error result))
             (not (:unavailable? result))
             (not (get-in result [:response :error])))
    (let [cfg     (or (when (map? (:config charge)) (:config charge)) {})
          crew-id (or (:crew charge) (get-in cfg [:defaults :crew]) "main")]
      (when (lifecycle/episodes-crew? cfg crew-id)
        (lifecycle/maybe-seal!
          {:fs            (or (nexus/get :fs) (fs/instance))
           :root          (or (get-in cfg [:root]) (:root charge) (nexus/get :root))
           :crew          crew-id
           :episode-id    (:session-key charge)
           :session-store (or (:session-store charge) (nexus/get-in [:sessions :store]))
           :cfg           cfg}))))
  result)

(defn- isolate-cleanup! [step-name f]
  (try
    (f)
    (catch Throwable t
      (log/warn :turn/finalization-step-failed
                :step step-name
                :error (.getMessage t)
                :ex-class (.getName (class t))))))

(defn- resolve-charge-observers [charge]
  (let [refs (:observers charge)]
    (cond
      (empty? refs) {:charge charge}
      (every? #(satisfies? observer/TurnObserver %) refs) {:charge charge}
      :else (let [resolved (observer/resolve-submitted refs)]
              (if (:error resolved)
                resolved
                {:charge (assoc charge :observers (:observers resolved))})))))

(defn- turnstile-refuse-message [decision]
  (or (:message decision)
      (str "turnstile refused: "
           (let [reason (:reason decision)]
             (cond
               (keyword? reason) (name reason)
               (string? reason)  reason
               :else             (pr-str reason))))))

(defn- persist-parked-user-message! [charge]
  (when-not (:from-queue? charge)
    (when-let [session-key (:session-key charge)]
      (when-let [input (:input charge)]
        (when-let [ss (or (:session-store charge) (nexus/get-in [:sessions :store]))]
          (store/append-message! ss session-key {:role "user" :content input}))))))

(defn- format-turnstile-refs [refs]
  (->> refs
       (map (fn [ts-ref]
              (cond
                (satisfies? turnstile/Turnstile ts-ref) nil
                (sequential? ts-ref) (str (name (first ts-ref))
                                          (when (seq (rest ts-ref))
                                            (str ":" (str/join "/" (rest ts-ref)))))
                (keyword? ts-ref) (name ts-ref)
                :else (str ts-ref))))
       (remove nil?)
       vec))

(defn- charge-root [charge]
  (or (:root charge)
      (get-in charge [:config :root])
      (nexus/get :root)
      (loader/root)))

(defn- park-held-charge! [charge decision]
  (persist-parked-user-message! charge)
  (let [record (binding [turn-queue/*root* (charge-root charge)]
                 (turn-queue/enqueue!
                   (cond-> {:session    (:session-key charge)
                            :input      (:input charge)
                            :turnstiles (:turnstiles charge)
                            :crew       (:crew charge)
                            :origin     (:origin charge)
                            :cwd        (:cwd charge)
                            :observers  (:observers charge)
                            :message    (:message decision)
                            :reason     :hold
                            :state      :held}
                     (:held-id charge) (assoc :id (:held-id charge)))))
        refs   (format-turnstile-refs (:turnstiles charge))
        label  (or (first refs) "turnstile")]
    {:held    true
     :id      (:id record)
     :reason  :hold
     :message (or (:message decision) (str label " held"))
     :turnstiles refs}))

(defn- maybe-log-gateless! [charge]
  (when (and (empty? (:turnstiles charge))
             (seq (turnstile/registered-names)))
    (log/info :turnstile/gateless
              :session (:session-key charge)
              :message "gateless turn in a registered worksite")))

(defn- admit-charge-turnstiles [charge]
  (let [refs (:turnstiles charge)]
    (cond
      (empty? refs)
      (do (maybe-log-gateless! charge)
          {:charge charge})

      :else
      (let [resolved (if (every? #(satisfies? turnstile/Turnstile %) refs)
                       {:turnstiles refs}
                       (turnstile/resolve-submitted refs))]
        (if (:error resolved)
          resolved
          (let [decision (turnstile/admit-all! (:turnstiles resolved)
                                               {:session-key (:session-key charge)
                                                :cwd         (:cwd charge)
                                                :crew        (:crew charge)
                                                :origin      (:origin charge)
                                                :now         (or (:now charge) (memory/now))})]
            (if (:error decision)
              {:error   (:error decision)
               :reason  (:reason decision)
               :message (turnstile-refuse-message decision)}
              {:charge (assoc charge :turnstile-tokens (:tokens decision))})))))))

(defn- dispatch-charge! [c]
  (let [{:keys [charge result]} (route-charge! c)]
    (if charge
      (let [obs-check (resolve-charge-observers charge)]
        (if (:error obs-check)
          {:error   (:error obs-check)
           :message (:message obs-check)
           :ref     (:ref obs-check)}
          (let [charge   (or (:charge obs-check) charge)
                ts-check (admit-charge-turnstiles charge)]
            (cond
              (and (:error ts-check) (= :hold (:reason ts-check)))
              (park-held-charge! charge ts-check)

              (:error ts-check)
              {:error   (:error ts-check)
               :reason  (:reason ts-check)
               :message (:message ts-check)
               :ref     (:ref ts-check)}

              :else
              (let [charge (or (:charge ts-check) charge)]
                (if-let [session-key (:session-key charge)]
                  (let [session-store* (or (:session-store charge) (nexus/get-in [:sessions :store]))]
                    (if (store/mark-in-flight! session-store* session-key)
                      (do
                        (record-turn-marker! session-store* session-key charge)
                        (try
                          (maybe-live-seal! charge (turn/run-turn! charge))
                          (finally
                            (isolate-cleanup! :clear-turn-marker
                                              #(clear-turn-marker! session-store* session-key))
                            (isolate-cleanup! :clear-in-flight
                                              #(store/clear-in-flight! session-store* session-key)))))
                      (refuse-dispatch session-key)))
                  (maybe-live-seal! charge (turn/run-turn! charge))))))))
      result)))

(defn dispatch!
  "Comm-facing entry point. Accepts a charge (built via charge/build) or a
   request map (which gets passed through charge/build). Slash commands are
   handled here; normal turns delegate to run-turn!. Bridge -> drive only."
  ([input]
    (if (charge/charge? input)
      (dispatch-charge! input)
      (let [request (ensure-session! (merge (nexus/necho) input))]
        (dispatch-charge! (charge/build request)))))
  ([_root request]
    ;; Two-arg form is a back-compat shim — root now lives on the
    ;; config snapshot, which downstream readers consult directly.
    (dispatch! request)))

;; endregion ^^^^^ Triage ^^^^^
