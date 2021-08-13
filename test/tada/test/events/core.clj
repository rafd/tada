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
        :conditions (fn [{:keys [a b]}] [#(constantly true)])
        :effect (fn [{:keys [a b]}] [a b])}
      {:id :bazquux
       :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
       :conditions (fn [{:keys [c]}] [#(constantly false)])
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
            :conditions (fn [{:keys [a b]}] [#(constantly true)])
            :effect (fn [{:keys [a b]}] [a b])}
           {:id :bazquux
            :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
            :conditions (fn [{:keys [c]}] [[#(constantly false) :incorrect "Complain"]])
            :effect (fn [{:keys [c]}] c)}])))
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          [{:id :foobar
            :params {:a string?
                     :b :tada/event}
            :conditions (fn [{:keys [a b]}] [#(constantly true)])
            :effect (fn [{:keys [a b]}] [a b])}
           {:id :bazquux
            :params {:c :some-invalid/spec}
            :conditions (fn [{:keys [c]}] [#(constantly true)])
            :effect (fn [{:keys [c]}] c)}])))))

(deftest running-events
  (tada/register!
   [{:id :foobar
     :params {:a string?
              :b integer?}
     :conditions (fn [{:keys [a b]}]
                   [[#(string/starts-with? a "a") :incorrect "A must start with 'a'"]
                    [#(even? b) :incorrect "B must be even"]])
     :effect (fn [{:keys [a b]}] (pr [a b]))
     :return (fn [_] true)}
    {:id :bazquux
     :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
     :conditions (fn [{:keys [c]}]
                   [[#(< 2 (count (keys c))) :incorrect
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
             "Params for event "))))
    (try
      (tada/do! :foobar {:b 5})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Params for event ")))))

  (testing "Calling event with params violating conditions throws"
    (try
      (tada/do! :foobar {:a "foobar" :b 2})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Condition for event "))))
    (try
      (tada/do! :foobar {:a "aoeu" :b 3})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :incorrect (:anomaly (ex-data ex))))
        (is (string/starts-with?
             (.getMessage ex)
             "Condition for event ")))))

  (testing "Calling with correct arguments works"
    (let [out (with-out-str (is (= true (tada/do! :foobar {:a "aoeu" :b 2}))))]
      (is (= (pr-str ["aoeu" 2]) out))))


  (testing "Events with no condition work"
    (tada/register!
      [{:id :no-conditions
        :params {:a string?}
        :return (fn [_] true)}])
    (is (= true (tada/do! :no-conditions {:a "asd"})))))

(deftest one-by-one-conditions
  (let [side-effects (atom #{})]
    (tada/register!
      [{:id :foobar
        :params {:a string?
                 :b integer?}
        :conditions (fn [{:keys [a b]}]
                      [[#(do
                           (swap! side-effects conj :a)
                           (string/starts-with? a "a")) :incorrect "A must start with 'a'"]
                       [#(do
                           (swap! side-effects conj :b)
                           (even? b)) :incorrect "B must be even"]])
        :effect (fn [{:keys [a b]}] (pr [a b]))}])

    (testing "If a condition fails, subsequent conditions are not checked"
      (try
        (tada/do! :foobar {:a "xoobar" :b 3})
        (catch clojure.lang.ExceptionInfo _))
      (is (= @side-effects #{:a})))))
