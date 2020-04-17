(ns tada.events.core
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.core.match :as match]
    [spec-tools.core :as st]
    [spec-tools.data-spec :as ds]))

(defonce events (atom {}))

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
                      :status boolean?
                      :anomaly keyword?
                      :message string?)))}))

(s/def :tada/event
  (ds/spec
    {:name :tada/event
     :spec {:params {keyword? (ds/or {:keyword keyword?
                                      :fn fn?
                                      :spec s/spec?})}
            :conditions fn? ;; :tada/condition-fn
            (ds/opt :effect) fn?
            (ds/opt :return) fn?}}))

(s/def :tada/events
  (ds/spec
    {:name :tada/events
     :spec {keyword? :tada/event}}))

(defn- make-event-spec
  [event]
  (ds/spec {:name (keyword "ev-spec" (name (event :id)))
            :spec (event :params)}))

(defn register-events!
  [evs]
  {:pre [(every? (partial s/valid? :tada/event) evs)]
   :post [(s/valid? :tada/events @events)]}
  (reset!
    events
    (into {}
          (comp
            (map (fn [evt] (assoc evt :params-spec (make-event-spec evt))))
            (map (juxt :id identity)))
          evs)))

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
  [ev params]
  (let [coerced-params (st/coerce (ev :params-spec) params transformer)]
    (when (s/valid? (ev :params-spec) coerced-params)
      coerced-params)))

(defn- rule-errors
  "Returns boolean of whether the the conditions for an event are satisfied.
   Should be called with sanitized-params."
  [ev sanitized-params]
  (->> ((ev :conditions) sanitized-params)
       (remove (fn [[pass? _ _]] pass?))
       (map (fn [[pass? anomaly message]]
              {:anomaly anomaly
               :message message}))))

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

(defn do! [ev-id params]
  (if-let [ev (@events ev-id)]
    (if-let [sanitized-params (sanitize-params ev params)]
      (let [errors (rule-errors ev sanitized-params)]
        (if (empty? errors)
          (do
            (when (ev :effect)
              ((ev :effect) sanitized-params))
            (if (ev :return)
              ((ev :return) sanitized-params)
              nil))
          (throw (ex-info (str "Event conditions are not met:\n"
                               (string/join "\n" (map :message errors)))
                          {:anomaly :incorrect}))))
      (throw (ex-info (str "Event params do not meet spec:\n"
                           (explain-params-errors (ev :params-spec) params))
               {:anomaly :incorrect})))
    (throw (ex-info (str "No event with id " ev-id)
                    {:event-id ev-id
                     :anomaly :unsupported}))))
