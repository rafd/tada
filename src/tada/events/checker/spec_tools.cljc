(ns tada.events.checker.spec-tools
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.core.match :as match]
   [spec-tools.core :as st]
   [spec-tools.data-spec :as ds]
   [tada.events.checker.proto :as c]))

(defn make-event-spec
  [event]
  (ds/spec {:name (keyword "ev-spec" (name (event :id)))
            :spec (or (event :params) {})}))

(def transformer
  (st/type-transformer
   st/string-transformer
   st/strip-extra-keys-transformer
   st/strip-extra-values-transformer))

(defn sanitize-params
  "Given a params-spec and params,
   if the params pass the spec, returns the params
     (eliding any extra keys and values)
   if params do not pass spec, returns nil"
  [event params]
  (let [coerced-params (st/coerce (event ::params-spec) params transformer)]
    (when (s/valid? (event ::params-spec) coerced-params)
      coerced-params)))

(defn explain-params-errors [event value]
  (->> (s/explain-data (::params-spec event) value)
       ::s/problems
       (map (fn [{:keys [path pred _val _via _in]}]
              (match/match [pred]
                           [([fn [_] ([contains? _ missing-key] :seq)] :seq)]
                           {:issue :missing-key
                            :key-path (conj path missing-key)}
                           [_]
                           {:issue :incorrect-value
                            :key-path path})))
       (map (fn [{:keys [issue key-path]}]
              (str key-path " " issue)))
       (string/join "\n")))

(defn prepare-event
  [event]
  {::params-spec (make-event-spec event)})

(def SpecToolsChecker
  (reify c/Checker
    (c/prepare-event
     [_ event]
     (prepare-event event))
    (c/sanitize-params
     [_ event params]
     (sanitize-params event params))
    (c/explain-params-errors
     [_ event params]
     (explain-params-errors event params))))
