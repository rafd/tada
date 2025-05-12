(ns tada.events.ring
  (:require
    [tada.events.core :as tada]))

(defn ring-dispatch-event! [t event-id event-params]
  (try
    (if-let [return (tada/do! t event-id event-params)]
      {:status 200
       :body return}
      {:status 200})
    (catch clojure.lang.ExceptionInfo e
      {:body (.getMessage e)
       :status (case (:anomaly (ex-data e))
                 :incorrect 400
                 :forbidden 403
                 :unsupported 405
                 :not-found 404
                 ;; if no anomaly (usually do to event :effect or :return throwing)
                 ;; rethrow the exception
                 (throw e))})))

(defn route [t event-id]
  (fn [request]
    (ring-dispatch-event! t event-id (request :params))))
