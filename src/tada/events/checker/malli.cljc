(ns tada.events.checker.malli
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [malli.error :as me]
   [malli.experimental.lite :as ml]
   [tada.events.checker.proto :as c]))

(defn make-validator
  [event]
  (m/validator
    (ml/schema (or (event :params) {}))))

(defn make-schema
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

(defn make-coercer
 [event]
 (m/coercer (make-schema event) transformer))

(defn sanitize-params
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

(defn explain-params-errors [event value]
  (->> (m/explain (::schema event) value)
       me/humanize))

(defn prepare-event [event]
  {::coercer (make-coercer event)
   ::validator (make-validator event)
   ::schema (make-schema event)})

(def MalliChecker
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
