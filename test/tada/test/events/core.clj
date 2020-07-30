(ns tada.test.events.core
  (:require
   [clojure.test :refer :all]
   [clojure.string :as string]
   [tada.events.core :as tada]
   [spec-tools.data-spec :as ds]))

(defn- event-fixture
  [f]
  (reset! tada/event-store {})
  (f)
  (reset! tada/event-store {}))

(use-fixtures :each event-fixture)

(deftest registering-events

  (testing "Can register events with correct spec"
    (tada/register!
     [{:id :foobar
       :params {:a string?
                :b :tada/event}
       :conditions (fn [{:keys [a b]}] [true])
       :effect (fn [{:keys [a b]}] [a b])}
      {:id :bazquux
       :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
       :conditions (fn [{:keys [c]}] [false])
       :effect (fn [{:keys [c]}] c)}])
    (is (= #{:bazquux :foobar}
           (set (keys @tada/event-store)))
        "Ids of event should be keys of events map"))

  (testing "Trying to register invalid events fails"
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          [{:id "wrong"
            :params {:a string?
                     :b :tada/event}
            :conditions (fn [{:keys [a b]}] [true])
            :effect (fn [{:keys [a b]}] [a b])}
           {:id :bazquux
            :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
            :conditions (fn [{:keys [c]}] [[false :incorrect "Complain"]])
            :effect (fn [{:keys [c]}] c)}])))
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          [{:id :foobar
            :params {:a string?
                     :b :tada/event}
            :conditions (fn [{:keys [a b]}] [true])
            :effect (fn [{:keys [a b]}] [a b])}
           {:id :bazquux
            :params {:c :some-invalid/spec}
            :conditions (fn [{:keys [c]}] [true])
            :effect (fn [{:keys [c]}] c)}])))))

(deftest running-events
  (tada/register!
   [{:id :foobar
     :params {:a string?
              :b integer?}
     :conditions (fn [{:keys [a b]}]
                   [[(string/starts-with? a "a") :incorrect "A must start with 'a'"]
                    [(even? b) :incorrect "B must be even"]])
     :effect (fn [{:keys [a b]}] (pr [a b]))}
    {:id :bazquux
     :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
     :conditions (fn [{:keys [c]}]
                   [[(< 2 (count (keys c))) :incorrect
                     "C must have at least two keys"]])
     :effect (fn [{:keys [c]}] c)}])

  (testing "Calling non-existant event throws"
    (try
      (tada/do! :wrong {:a "aoeu" :b 2})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :unsupported (:anomaly (ex-data ex)))))))

  (testing "Calling event with params violating spec throws"
    (try
      (tada/do! :foobar {:a 5 :b 2})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Event params do not meet spec:"))))
    (try
      (tada/do! :foobar {:b 5})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Event params do not meet spec:")))))

  (testing "Calling event with params violating conditions throws"
    (try
      (tada/do! :foobar {:a "foobar" :b 2})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Event conditions are not met:"))))
    (try
      (tada/do! :foobar {:a "aoeu" :b 3})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Event conditions are not met:")))))

  (testing "Calling with correct arguments works"
    (let [out (with-out-str (is (= true (tada/do! :foobar {:a "aoeu" :b 2}))))]
      (is (= (pr-str ["aoeu" 2]) out)))))
