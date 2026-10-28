(ns isaac.agent.drive.continuation
  (:require
    [clojure.string :as str]
    [isaac.agent.attention :as attention]
    [isaac.agent.comm.protocol :as comm]
    [isaac.agent.drive.tools-config :as tools-config]
    [isaac.agent.turn.queue :as turn-queue]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]))

(defn- turn-var [name]
  (requiring-resolve (symbol "isaac.agent.drive.turn" name)))

(def ^:private pending-tool-calls* (delay (turn-var "pending-tool-calls")))
(def ^:private response-tool-calls** (delay (turn-var "response-tool-calls*")))
(def ^:private execute-pending-tools!* (delay (turn-var "execute-pending-tools!")))
(def ^:private wrap-up-request* (delay (turn-var "wrap-up-request")))
(def ^:private empty-wrap-up-failure* (delay (turn-var "empty-wrap-up-failure")))
(def ^:private merge-response-usage* (delay (turn-var "merge-response-usage")))
(def ^:private append-message!* (delay (turn-var "append-message!")))
(def ^:private wrap-up-nudge* (delay @(turn-var "wrap-up-nudge")))

(defn- pending-tool-calls [result] (@pending-tool-calls* result))
(defn- response-tool-calls* [response] (@response-tool-calls** response))
(defn- execute-pending-tools! [tool-ctx tool-calls] (@execute-pending-tools!* tool-ctx tool-calls))
(defn- wrap-up-request [request prompt] (@wrap-up-request* request prompt))
(defn- empty-wrap-up-failure [] (@empty-wrap-up-failure*))
(defn- merge-response-usage [usage response] (@merge-response-usage* usage response))
(defn- append-message! [ctx session-key message] (@append-message!* ctx session-key message))

(defn apply-wrap-up-exhaustion [result chat-fn followup-fn current-request tool-ctx wrap-up-prompt]
  (let [pending (or (seq (pending-tool-calls result))
                    (seq (response-tool-calls* (:response result))))]
    (when (seq pending)
      (let [results  (execute-pending-tools! tool-ctx pending)
            messages (followup-fn @current-request (or (:response result) result) pending results)]
        (reset! current-request (assoc @current-request :messages messages))))
    (let [wrap-req     (wrap-up-request @current-request (or wrap-up-prompt @wrap-up-nudge*))
          wrap-resp    (chat-fn wrap-req)
          wrap-calls   (or (seq (pending-tool-calls wrap-resp))
                           (seq (response-tool-calls* wrap-resp)))
          wrap-content (:content wrap-resp)]
      (reset! current-request wrap-req)
      (if (seq wrap-calls)
        (let [results  (execute-pending-tools! tool-ctx wrap-calls)
              messages (followup-fn wrap-req wrap-resp wrap-calls results)
              note-req (assoc (assoc wrap-req :messages messages) :tools [])
              note-resp (chat-fn note-req)
              content   (:content note-resp)]
          (reset! current-request note-req)
          (if (str/blank? content)
            (empty-wrap-up-failure)
            (-> result
                (assoc :content content
                       :response note-resp
                       :exhaustion :wrapped-up
                       :loop-request? true)
                (assoc :usage (merge-response-usage (:usage result) note-resp)))))
        (if (str/blank? wrap-content)
          (empty-wrap-up-failure)
          (do
            (reset! current-request (assoc wrap-req :tools []))
            (-> result
                (assoc :content wrap-content
                       :response wrap-resp
                       :exhaustion :wrapped-up
                       :loop-request? true)
                (assoc :usage (merge-response-usage (:usage result) wrap-resp)))))))))

;; region ----- Continuations -----

(def continuation-note
  "The continuation turn's input. The wrap-up note is the turn's last
   assistant message (isaac-x0cw), so the model reads its own done/next note
   straight above this line."
  "Your previous turn ran out of cycles and wrapped up. Read your own wrap-up note above and continue from where it says to resume. Do not redo finished work.")

(defn- wrapped-up? [result]
  (and (= :cycle-limit (:ended-by result))
       (= :wrapped-up (:exhaustion result))))

(defn- continuation-count
  "How many continuations this turn already is. The count rides on the
   charge's origin, so it survives the queue without the drive keeping state."
  [charge]
  (or (get-in charge [:origin :continuation]) 0))

(defn- continuation-plan
  "What follows a finished turn: a fresh turn (:continue), the end of the
   budget (:exhausted), or nothing at all. Only a wrapped-up cycle-limit turn
   continues - a comm that answers :stop never does."
  [result n budget]
  (when (wrapped-up? result)
    (if (< n budget)
      {:action :continue :continuation (inc n) :budget budget}
      {:action :exhausted :continuation n :budget budget})))

(defn- turn-root [charge]
  (or (:root charge)
      (get-in charge [:config :root])
      (nexus/get :root)
      (loader/root)))

(defn- enqueue-continuation!
  "Park a fresh turn for this session on the durable turn queue - the same
   waiting room a resumed or held turn parks in (isaac-yxch). The note is
   persisted here, the way a parked CLI turn persists its user message at
   submit time; the queue worker drives a :from-queue? charge and never
   re-appends it. The origin carries the count and the original source, so
   the next turn knows which continuation it is."
  [charge ctx ch session-key {:keys [continuation budget]}]
  (append-message! ctx session-key {:role "user" :content continuation-note})
  (binding [turn-queue/*root* (turn-root charge)]
    (turn-queue/enqueue! {:session session-key
                          :input   continuation-note
                          :input-persisted? true
                          :origin  (assoc (or (:origin charge) {:kind :queue})
                                          :continuation continuation)
                          :from    (:from charge)
                          :for     (:for charge)
                          :comm    ch
                          :crew    (:crew charge)
                          :cwd     (:cwd charge)
                          :root    (turn-root charge)}))
  (log/info :turn/continued
            :session session-key
            :continuation continuation
            :budget budget))

(defn- continuations-exhausted!
  "The budget is spent and the work is still unfinished. Loud on purpose: an
   ERROR log, attention for the operator, and a bulletin so the comm that
   asked for the wrap-up can say so in its own channel."
  [charge ch session-key {:keys [continuation budget]}]
  (log/error :turn/continuations-exhausted
             :session session-key
             :continuation continuation
             :budget budget)
  (attention/maybe-notify-continuations-exhausted!
    (or (:config charge) (loader/snapshot "continuations-exhausted attention"))
    session-key
    {:continuation continuation :budget budget})
  (comm/on-bulletin ch session-key
                    {:kind :turn/continuations-exhausted
                     :text (str "Session " session-key " ran out of continuations ("
                                continuation " of " budget ") with work still unfinished")}))

(defn maybe-continue!
  "Called once per finished turn. The drive owns continuations (isaac-xpkf);
   nothing here knows what started the turn."
  [charge ctx ch session-key result]
  (try
    (let [{:keys [crew config crew-cfg cycle]} charge
          budget (:continuations (tools-config/resolve-cycle {:cycle cycle :config config
                                                 :crew crew :crew-cfg crew-cfg}))
          n      (continuation-count charge)]
      (when-let [plan (continuation-plan result n budget)]
        (if (= :continue (:action plan))
          (enqueue-continuation! charge ctx ch session-key plan)
          (continuations-exhausted! charge ch session-key plan))))
    (catch Throwable t
      (log/warn :turn/continuation-failed
                :session session-key
                :error (.getMessage t))))
  result)

;; endregion ^^^^^ Continuations ^^^^^
