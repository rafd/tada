(ns tada.events.core
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [malli.error :as me]
   [malli.experimental.lite :as ml]))

(defonce event-store (atom {}))

(def Event
  [:map
   [:id :keyword]
   [:params {:optional true}
    [:map-of
     :keyword
     ;; no "meta-schema" exists yet
     ;; https://github.com/metosin/malli/issues/904
     :any]]
    ;; takes map of params, returns array of [no-arg-predicate-fn anomaly message]
    [:conditions {:optional true} fn?]
    ;; takes map of params, can return anything
    [:effect {:optional true} fn?]
    ;; either keyword or function that takes params + special param
    [:return {:optional true} ifn?]])

(defn- make-validator
  [event]
  (m/validator
    (ml/schema (or (event :params) {}))))

(defn- make-schema
  [{:keys [params]}]
  (cond
    (nil? params)
    (m/schema :map)

    (map? params)
    (ml/schema params)
    #_(into [:map] (map ml/-entry params))

    :else
    (m/schema params)))

#_(make-schema {:params nil})
#_(make-schema {:params [:map
                         [:foo :string]]})
#_(make-schema {:params {:a :string}})

(def transformer
  (mt/transformer
    mt/string-transformer
    mt/strip-extra-keys-transformer))

(defn- make-coercer
 [event]
 (m/coercer (make-schema event) transformer))

(defn register!
  [events]
  {:pre [(every? (fn [event]
                   (or (m/validate Event event)
                       (do (println
                             "Event did not pass validation:\n"
                             event "\n"
                             (me/humanize (m/explain Event event)))
                           false)))
                 events)]
   :post [(m/validate [:map-of :keyword Event] @event-store)]}
  (swap! event-store merge (->> events
                                (map (fn [event]
                                       [(event :id) (assoc event
                                                      ::coercer (make-coercer event)
                                                      ::validator (make-validator event)
                                                      ::schema (make-schema event))]))
                                (into {}))))

(defn- sanitize-params
  "Given a params-spec and params,
   if the params pass the spec, returns the params
     (eliding any extra keys and values)
   if params do not pass spec, returns nil"
  [event params]
  ;; catching malli exceptions here
  ;; because do! will throw anyway if this fn returns nil
  (try
    (let [coerced-params ((event ::coercer) params)]
      (when ((event ::validator) coerced-params)
        coerced-params))
    (catch clojure.lang.ExceptionInfo _)))

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

(defn explain-params-errors [spec value]
  (->> (m/explain spec value)
       me/humanize))

(defn do! [event-id params]
  (if-let [event (@event-store event-id)]
    (if-let [sanitized-params (sanitize-params event params)]
      (let [error (condition-check event sanitized-params)]
        (if (nil? error)
          (let [effect-return (when (event :effect)
                                ((event :effect) sanitized-params))]
            (if (event :return)
              ((event :return) (assoc sanitized-params
                                      :tada/effect-return effect-return))
              nil))
          (throw (ex-info (str "Condition for event " event-id " is not met:\n"
                               (:message error))
                          {:error error
                           :anomaly :incorrect}))))
      (throw (ex-info (str "Params for event " event-id " do not meet spec:\n"
                           (explain-params-errors (event ::schema) params))
                      {:anomaly :incorrect})))
    (throw (ex-info (str "No event with id " event-id)
                    {:event-id event-id
                     :anomaly :unsupported}))))
