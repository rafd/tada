(ns tada.events.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.core.match :as match]
   [spec-tools.core :as st]
   [spec-tools.data-spec :as ds]))

(defonce event-store (atom {}))

;; Providing spec for documentation purposes, but not hooking it up
;; since it tries to generative test the functions, which isn't
;; necessarily always desired; additionally, we also know that the
;; condition function will always get input that corresponds to the
;; params spec, but that spec isn't created until after being checked
;; against this
(s/def :tada/condition-fn
  (ds/spec
   {:name :tada/condition-fn
    :spec (s/fspec
           :args (s/cat :arg map?)
           :ret (s/coll-of
                 (s/cat
                  :status fn? ;; that returns a boolean
                  :anomaly keyword?
                  :message string?)))}))

(s/def :tada/event
  (ds/spec
   {:name :tada/event
    :spec {(ds/opt :params) {keyword? (ds/or {:keyword keyword?
                                              :fn fn?
                                              :spec s/spec?
                                              :set set?
                                              :vector vector?})}
           (ds/opt :conditions) fn? ;; :tada/condition-fn
           (ds/opt :effect) fn?
           (ds/opt :return) ifn?}}))

(s/def :tada/events
  (ds/spec
   {:name :tada/events
    :spec {keyword? :tada/event}}))

(defn- make-event-spec
  [event]
  (ds/spec {:name (keyword "ev-spec" (name (event :id)))
            :spec (or (event :params) {})}))

(defn register!
  [events]
  {:pre [(every? (fn [event] (if (s/valid? :tada/event event)
                               true
                               (do (s/explain :tada/event event)
                                   false))) events)]
   :post [(s/valid? :tada/events @event-store)]}
  (swap! event-store merge (->> events
                                (map (fn [event]
                                       [(event :id) (assoc event :params-spec (make-event-spec event))]))
                                (into {}))))

(def transformer
  (st/type-transformer
   st/string-transformer
   st/strip-extra-keys-transformer
   st/strip-extra-values-transformer))

(defn- sanitize-params
  "Given a params-spec and params,
   if the params pass the spec, returns the params
     (eliding any extra keys and values)
   if params do not pass spec, returns nil"
  [event params]
  (let [coerced-params (st/coerce (event :params-spec) params transformer)]
    (when (s/valid? (event :params-spec) coerced-params)
      coerced-params)))

(defn- condition-check
  "Evaluates conditions one at a time, returning the first error encountered (or nil if no errors).
   Should be called with sanitized-params."
  [event sanitized-params]
  (if (nil? (event :conditions))
    nil
    (->> ((event :conditions) sanitized-params)
         ;; using reduce to ensure one-at-a-time
         (reduce (fn [memo [pass-thunk? anomaly message]]
                   (if (pass-thunk?)
                     nil
                     (reduced {:anomaly anomaly
                               :message message}))) nil))))

(defn explain-params-errors [spec value]
  (->> (s/explain-data spec value)
       ::s/problems
       (map (fn [{:keys [path pred val via in]}]
              (match/match [pred]
                [([fn [_] ([contains? _ missing-key] :seq)] :seq)] {:issue :missing-key
                                                                    :key-path (conj path missing-key)}
                [_] {:issue :incorrect-value
                     :key-path path})))
       (map (fn [{:keys [issue key-path]}]
              (str key-path " " issue)))
       (string/join "\n")))

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
                          {:anomaly :incorrect}))))
      (throw (ex-info (str "Params for event " event-id " do not meet spec:\n"
                           (explain-params-errors (event :params-spec) params))
                      {:anomaly :incorrect})))
    (throw (ex-info (str "No event with id " event-id)
                    {:event-id event-id
                     :anomaly :unsupported}))))
