(ns tada.events.core
  (:require
   [clojure.spec.alpha :as s]
   [tada.events.checker.proto :as c]))

;; Providing spec for documentation purposes, but not hooking it up
;; since it tries to generative test the functions, which isn't
;; necessarily always desired; additionally, we also know that the
;; condition function will always get input that corresponds to the
;; params spec, but that spec isn't created until after being checked
;; against this
(s/def ::condition-fn
       (s/fspec
        :args (s/cat :arg map?)
        :ret (s/coll-of
              (s/cat
               :status fn? ;; that returns a boolean
               :anomaly keyword?
               :message string?))))

(s/def ::spec any?)

(s/def ::params
       (s/map-of keyword? ::spec))

(s/def ::conditions fn?) ;; ::condition-fn
(s/def ::effect fn?)
(s/def ::return ifn?)

(s/def ::event
       (s/keys :opt-un [::params
                        ::conditions
                        ::effect
                        ::return]))

(s/def ::event-store
       (s/map-of keyword? ::event))

(defn- valid? [spec object]
  (if (s/valid? spec object)
    true
    (do (s/explain spec object)
        false)))

(defn- condition-check
  "Evaluates conditions one at a time, returning the first error encountered (or nil if no errors).
  Should be called with sanitized-params."
  [event sanitized-params]
  (if (nil? (event :conditions))
    nil
    (->> ((event :conditions) sanitized-params)
         (map-indexed vector)
         ;; using reduce to ensure one-at-a-time
         (reduce (fn [_ [index [pass-thunk? anomaly message]]]
                   (if (pass-thunk?)
                     nil
                     (reduced {:index index
                               :anomaly anomaly
                               :message message}))) nil))))

(defn init
  [checker]
  {:tada/event-store (atom {})
   :tada/checker (if (keyword? checker)
                   (case checker
                     :malli
                     @(requiring-resolve 'tada.events.checker.malli/MalliChecker)
                     :spec-tools
                     @(requiring-resolve 'tada.events.checker.spec-tools/SpecToolsChecker))
                   ;; assuming passed in one of the above directly
                   ;; which would be necessary in cljs
                   checker)})

(defn register!
  [{:tada/keys [event-store checker]} events]
  {:pre [(every? (partial valid? ::event) events)]
   :post [(valid? ::event-store @event-store)]}
  (swap! event-store merge
         (->> events
              (map (fn [event]
                     [(event :id) (merge event (c/prepare-event checker event))]))
              (into {}))))

(defn do!
  [{:tada/keys [event-store checker]} event-id params]
  (if-let [event (@event-store event-id)]
    (if-let [sanitized-params (c/sanitize-params checker event params)]
      (let [error (condition-check event sanitized-params)]
        (if (nil? error)
          (let [effect-return (when (event :effect)
                                ((event :effect) sanitized-params))]
            (if (event :return)
              ((event :return) (assoc sanitized-params
                                      :tada/effect-return effect-return))
              nil))
          (throw (ex-info (str "Condition for event " event-id " is not met:\n"
                               (pr-str error))
                          {:anomaly :incorrect}))))
      (throw (ex-info (str "Params for event " event-id " do not meet spec:\n"
                           (c/explain-params-errors checker event params))
                      {:anomaly :incorrect})))
    (throw (ex-info (str "No event with id " event-id)
                    {:event-id event-id
                     :anomaly :unsupported}))))
