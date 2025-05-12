(ns tada.events.checker.proto)

(defprotocol Checker
  (prepare-event [this event])
  (sanitize-params [this event params])
  (explain-params-errors [this event params]))
