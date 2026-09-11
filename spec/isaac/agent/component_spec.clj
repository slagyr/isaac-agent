(ns isaac.agent.component-spec
  (:require
    [clojure.edn :as edn]
    [isaac.agent.component]
    [isaac.bridge.resume :as resume]
    [isaac.bridge.suspend :as suspend]
    [isaac.comm.delivery.worker :as delivery]
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.turn.worker :as turn]
    [speclj.core :refer :all]))

(describe "agent components"

  (it "registers the session store, resumes on start, and suspends on stop"
    (let [calls         (atom [])
          session-store ::store
          config        {:server {:suspend-timeout-ms 4321}}
          instance      (component-factory/create :agent-lifecycle
                                                  {:config config
                                                   :root   "/isaac"})]
      (with-redefs [store/registered-store           (constantly nil)
                    store/register!                  (fn [cfg root]
                                                       (swap! calls conj [:register cfg root])
                                                       session-store)
                    resume/resume-interrupted-turns! #(swap! calls conj [:resume %])
                    suspend/suspend!                 #(swap! calls conj [:suspend %])]
        (component/start instance)
        (component/stop instance))
      (should= [[:register config "/isaac"]
                [:resume {:session-store session-store :root "/isaac" :cfg config}]
                [:suspend {:session-store session-store :timeout-ms 4321}]]
               @calls)))

  (it "contributes the lifecycle first and the workers after it"
    (let [components (:isaac/component (edn/read-string (slurp "resources/isaac-manifest.edn")))]
      (should= [:agent-lifecycle :comm-delivery :turn-queue]
               (vec (keys components)))
      (should= #{'isaac.agent.component}
               (set (map :namespace (vals components))))))

  (it "starts and stops each worker through its own component"
    (let [calls (atom [])]
      (with-redefs [nexus/get       (constantly ::scheduler)
                    delivery/start! #(do (swap! calls conj [:delivery-start %]) ::delivery)
                    delivery/stop!  #(swap! calls conj [:delivery-stop %])
                    turn/start!     #(do (swap! calls conj [:turn-start %]) ::turn)
                    turn/stop!      #(swap! calls conj [:turn-stop %])]
        (doseq [id [:comm-delivery :turn-queue]]
          (let [instance (component-factory/create id {})]
            (component/start instance)
            (component/stop instance))))
      (should= [[:delivery-start {}] [:delivery-stop ::delivery]
                [:turn-start {}] [:turn-stop ::turn]]
               @calls)))

  )
