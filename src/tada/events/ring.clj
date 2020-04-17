(ns tada.events.ring
  (:require
    [tada.events.core :as tada]))

 (defn route [ev-id]
  (fn [request]
    (try
      (if-let [return (tada/do! ev-id (request :params))]
        {:status 200
         :body return}
        {:status 200})
      (catch clojure.lang.ExceptionInfo e
        {:body (.getMessage e)
         :status (case (:anomaly (ex-data e))
                   :incorrect 400
                   :forbidden 403
                   :unsupported 405
                   :not-found 404)}))))
