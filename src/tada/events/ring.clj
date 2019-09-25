(ns tada.events.ring
  (:require
    [tada.core :as tada]))

(defn route [ev-id]
  (fn [request]
    (try
      (tada/do! ev-id (request :params))
      {:status 200}
      (catch clojure.lang.ExceptionInfo e
        {:body (.getMessage e)
         :status (case (:anomaly (ex-data e))
                   :incorrect 400
                   :unsupported 500)}))))
