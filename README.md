# TADA

A Clojure library for declarative events.

## Example

```clojure
(ns bank.core
  (:require
     [tada.events.core :as tada]
     [spec-tools.data-spec :as ds]
     [clojure.spec.alpha :as s]))

;; say we're modeling a bank...
;; and we already have some specs:
;; (here, we're using ds-spec)

(s/def :bank/currency #{:CAD :USD})

(s/def :bank/user
  (ds/spec
    {:name :bank/user
     :spec {:bank.user/id uuid?
            :bank.user/name string?}}))

(s/def :bank/account
  (ds/spec
    {:name :bank/account
     :spec {:bank.account/id uuid?
            :bank.account/balance integer?
            :bank.account/currency :bank/currency
            :bank.account/owners [:bank.user/id]}}))


;; and some database functions:

(defn transfer! [from-account-id to-account-id amount])

(defn deposit! [account-id amount])

(defn user-exists? [user-id] ...)

(defn account-exists? [account-id] ...)

(defn user-owns-account? [user-id account-id] ...)

(defn get-account [account-id] ...)


;; we then define some events for our app:

(def events
  [{:id :deposit!

    :params {:user-id :bank.user/id
             :account-id :bank.account/id
             :amount integer?
             :currency :bank/currency}

    :conditions
    (fn [{:keys [user-id account-id amount currency]}]
       [[(user-exists? user-id) :forbidden "User with this id does not exist"]
        [(account-exists? account-id) :not-found "Account with this id does not exist"]
        [(user-owns-account? user-id account-id) :forbidden "User does not own this account"]
        [(= currency (:currency (get-account account-id)) :incorrect "Deposit currency must match account"]])

    :effect
    (fn [{:keys [account-id amount]}]
       (deposit! account-id amount))}

   {:id :transfer!

    :params {:user-id :bank.user/id
             :from-account-id :bank.account/id
             :to-account-id :bank.account/id
             :amount (and integer? pos?)})}

    :conditions
    (fn [{:keys [user-id from-account-id to-account-id amount]}]
       [[(user-exists? user-id) :forbidden "User with this id does not exist"]
        [(account-exists? to-account-id) :not-found "Account with this id does not exist"]
        [(user-owns-account? user-id from-account-id) :forbidden "User does not own this account"]
        [(account-exists? from-account-id) :incorrect "Account with this id does not exist"]
        [(>= (:balance (get-account from-account-id)) amount) :conflict "Insufficient funds in account"]
        [(= (:currency (get-account from-account-id)
            (:currency (get-account to-account-id)) :conflict "Currency of accounts must match"]])

    :effect (fn [{:keys [from-account-id to-account-id amount]}]
               (transfer! from-account-id to-account-id amount)}]}])


;; register our events

(tada/register-events! events)

;; and then we can dispatch them with do!

;; when we call with everything correct, it runs the effect
(tada/do! :deposit! {:user-id #uuid "..."
                     :account-id #uuid "..."
                     :amount 100
                     :currency :CAD})

;; if called with invalid arguments, an error is raised
(tada/do! :deposit! {:user-id #uuid "..."
                     :account-id #uuid "..."
                     :currency :CAD})
;; => error: "Missing amount"

;; if conditions aren't met, also raises an error
(tada/do! :transfer! {:user-id #uuid "..."
                      :from-account-id #uuid "..."
                      :to-account-id #uuid "..."
                      :amount 100})
;; => error: "Insufficient Funds in Account"

```

## Event manipulation

Given a set of events, tada has a number of utilities to generate useful functions (or other artefacts)
to pass off to other systems.

## events -> ring-handlers

`tada.events.ring` can be used to generate ring-handlers from events.
These handlers convert the anomaly in the events to the appropriate status code.
Below, we're using reitit to route these events:

```clojure
(require '[tada.events.core])
(require '[tada.events.ring])
(require '[reitit.ring])

(def app
  (reitit.ring/ring-handler
    (reitit.ring/router
      ["/api"
        ["/transfer"
          {:post {:handler (tada.events.ring/route :add-event!)}}]
        ["/deposit"
          {:post {:handler (tada.events.ring/route :deposit!)}}]])))

;; plus some middleware to insert the authenticated user-id into params
```
