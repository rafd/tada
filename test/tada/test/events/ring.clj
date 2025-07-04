(ns tada.test.events.ring
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [tada.events.core :as tada]
   [tada.events.ring :as tada-ring]
   [reitit.ring :as ring]))

(deftest requesting-events
  (let [db (atom [])
        t (tada/init :malli)]
    (tada/register!
      t
      [{:id :add-event!
        :params {:name string?
                 :value integer?}
        :conditions (fn [{:keys [name value]}]
                      [[#(or (string/starts-with? name "user-")
                             (string/starts-with? name "system-"))
                        :incorrect
                        "Even name must begin with 'user-' or 'system-'"]
                       [#(<= 0 value) :incorrect "Value must be positive"]])
        :effect (fn [{:keys [name value] :as arg}]
                  (swap! db conj arg))}])
    (let [handler (tada-ring/route t :add-event!)]
      (testing "Can call the handler"
        (is (= {:status 200}
               (handler {:params {:name "user-thing"
                                  :value 5}})))
        (is (= {:status 200}
               (handler {:params {:name "system-thing"
                                  :value 0}})))
        (is (= [{:name "user-thing" :value 5}
                {:name "system-thing" :value 0}]
               @db)))
      (testing "Bad requests fail"
        (is (= 400
               (:status (handler {:params {:name "beep"
                                           :value 1}}))))
        (is (= 400
               (:status (handler {:params {:name "system-beep"
                                           :value -1}}))))
        (is (= 400
               (:status (handler {:params {:beep 1 :boop "aoeu"}}))))
        (is (= 400
               (:status (handler {:params {:name "user-thing"}}))))
        (is (= 400
               (:status (handler {:params {:value 5}}))))

        (is (= [{:name "user-thing" :value 5}
                {:name "system-thing" :value 0}]
               @db))))))

(deftest routing-with-reitit
  (let [db (atom [])
        t (tada/init :malli)]
    (tada/register!
      t
      [{:id :add-event!
        :params {:name string?
                 :value integer?}
        :conditions (fn [{:keys [name value]}]
                      [[#(or (string/starts-with? name "user-")
                             (string/starts-with? name "system-"))
                        :incorrect
                        "Even name must begin with 'user-' or 'system-'"]
                       [#(<= 0 value) :incorrect "Value must be positive"]])
        :effect (fn [{:keys [name value] :as arg}]
                  (swap! db conj arg))}])
    (let [app (ring/ring-handler
                (ring/router
                  ["/api"
                   ["/event"
                    {:post {:handler (tada-ring/route t :add-event!)}}]]))]
      (testing "Can call the handler"
        (is (= {:status 200}
               (app {:request-method :post
                     :uri "/api/event"
                     :params {:name "user-thing"
                              :value 5}})))
        (is (= {:status 200}
               (app {:request-method :post
                     :uri "/api/event"
                     :params {:name "system-thing"
                              :value 0}})))
        (is (= [{:name "user-thing" :value 5}
                {:name "system-thing" :value 0}]
               @db)))

      (testing "Bad requests fail"
        (is (= 400
               (:status (app {:request-method :post
                              :uri "/api/event"
                              :params {:name "beep"
                                       :value 1}}))))
        (is (= 400
               (:status (app {:request-method :post
                              :uri "/api/event"
                              :params {:name "system-beep"
                                       :value -1}}))))
        (is (= 400
               (:status (app {:request-method :post
                              :uri "/api/event"
                              :params {:beep 1 :boop "aoeu"}}))))
        (is (= 400
               (:status (app {:request-method :post
                              :uri "/api/event"
                              :params {:name "user-thing"}}))))
        (is (= 400
               (:status (app {:request-method :post
                              :uri "/api/event"
                              :params {:value 5}}))))

        (is (= [{:name "user-thing" :value 5}
                {:name "system-thing" :value 0}]
               @db))))))
