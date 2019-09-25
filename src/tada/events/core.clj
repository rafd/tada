(ns tada.events.core
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.core.match :as match]
    [spec-tools.data-spec :as ds]))

(defonce events (atom {}))

(s/def :tada/events
  (ds/spec
    {:name :tada/events
     :spec {keyword? {:params {keyword? (ds/or {:keyword keyword?
                                                :fn fn?
                                                :spec s/spec?})}
                      :conditions fn? ;; must return array of true/false
                      :effect fn?}}}))

(defn register-events!
  [evs]
  {:pre [(s/valid? :tada/events evs)]}
  (reset! events
          (->> evs
               (map (fn [[k v]]
                      [k (assoc v :params-spec
                                (ds/spec {:name (keyword "ev-spec" (name k))
                                          :spec (v :params)}))]))
               (into {}))))

(defn- sanitize-params
  "Given a params-spec and params,
   if the params pass the spec, returns the params
     (eliding any extra keys)
   if params do not pass spec, returns nil"
  [ev params]
  (when (s/valid? (ev :params-spec) params)
    ;; TODO make use of spec shape to do a deep filter
    (select-keys params (keys (ev :params)))))

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
            ((ev :effect) sanitized-params)
            true)
          (throw (ex-info (str "Event conditions are not met:\n"
                               (string/join "\n" (map :message errors)))
                          {:anomaly :incorrect}))))
      (throw (ex-info (str "Event params do not meet spec:\n"
                           (explain-params-errors (ev :params-spec) params))
               {:anomaly :incorrect})))
    (throw (ex-info (str "No event with id " ev-id)
                    {:event-id ev-id
                     :anomaly :unsupported}))))
