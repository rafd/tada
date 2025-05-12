(ns tada.test.events.core
  (:require
   [clojure.test :refer :all]
   [clojure.string :as string]
   [tada.events.core :as tada]))

(defn- event-fixture
  [f]
  (reset! tada/event-store {})
  (f)
  (reset! tada/event-store {}))

(use-fixtures :each event-fixture)

(deftest registering-events

  (testing "Can register events with correct spec"
    (tada/register!
     [{:id :minimal}
      {:id :complete
       :params {:a string?
                :b :string}
       :conditions
       (fn [{:keys [_a _b]}]
         [#(constantly true)])
       :effect
       (fn [{:keys [a b]}]
         [a b])
       :return
       (fn [_] true)}])
    (is (= #{:minimal :complete}
           (set (keys @tada/event-store)))))

  (testing "Can register with :tada/effect-return as :return value"
    (tada/register!
     [{:id :effect-return-keyword
       :effect (fn [_] true)
       :return :tada/effect-return}])
    (is (= true (tada/do! :effect-return-keyword {}))))

  (testing "Incorrect :return value throws"
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          [{:id :bad-effect-return
            :effect (fn [_] true)
            :return true}]))))

  (testing "Trying to register invalid events fails"
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          ;; id should be a keyword
          [{:id "wrong"}])))

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"malli.core/invalid-schema"
         (tada/register!
          [{:id :foobar
            :params {:a string?
                     ;; spec needs to exist
                     :b :non-existent-spec}}])))))

(deftest running-events
  (tada/register!
   [{:id :foobar
     :params {:a string?
              :b integer?}
     :conditions (fn [{:keys [a b]}]
                   [[#(string/starts-with? a "a") :incorrect "A must start with 'a'"]
                    [#(even? b) :incorrect "B must be even"]])
     :effect (fn [{:keys [a b]}] (pr [a b]))
     :return (fn [_] true)}])

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
    (is (= true (tada/do! :no-conditions {:a "asd"}))))

  (testing "Events with no conditions, no params work"
    (tada/register!
     [{:id :no-conditions-no-params
       :return (fn [_] true)}])
    (is (= true (tada/do! :no-conditions-no-params {})))))

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

(clojure.test/run-tests)
