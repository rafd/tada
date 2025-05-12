(ns tada.test.events.core
  (:require
   [clojure.test :refer :all]
   [clojure.string :as string]
   [tada.events.core :as tada]))

(deftest registering-events

  (testing "Can register events with correct spec"
    (let [t (tada/init :malli)]
      (tada/register!
       t
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
             (set (keys @(:tada/event-store t)))))))

  (testing "Can register with :tada/effect-return as :return value"
    (let [t (tada/init :malli)]
      (tada/register!
       t
       [{:id :effect-return-keyword
         :effect (fn [_] true)
         :return :tada/effect-return}])
      (is (= true (tada/do! t :effect-return-keyword {})))))

  (testing "Incorrect :return value throws"
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          (tada/init :malli)
          [{:id :bad-effect-return
            :effect (fn [_] true)
            :return true}]))))

  (testing "Trying to register invalid events fails"
    (is (thrown?
         java.lang.AssertionError
         (tada/register!
          (tada/init :malli)
          ;; id should be a keyword
          [{:id "wrong"}])))

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"malli.core/invalid-schema"
         (tada/register!
          (tada/init :malli)
          [{:id :foobar
            :params {:a string?
                     ;; spec needs to exist
                     :b :non-existent-spec}}])))

    (is (thrown?
         java.lang.Exception
         (tada/register!
          (tada/init :spec-tools)
          [{:id :foobar
            :params {:a string?
                     ;; spec needs to exist
                     :b :non-existent-spec}}])))))

(def events
  [{:id :foobar
    :params {:a string?
             :b integer?}
    :conditions (fn [{:keys [a b]}]
                  [[#(string/starts-with? a "a") :incorrect "A must start with 'a'"]
                   [#(even? b) :incorrect "B must be even"]])
    :effect (fn [{:keys [a b]}] (pr [a b]))
    :return (fn [_] true)}])

(deftest running-events
  (testing "Calling non-existant event throws"
    (try
      (tada/do! (tada/init :malli) :wrong {:a "aoeu" :b 2})
      (catch clojure.lang.ExceptionInfo ex
        (is (= :unsupported (:anomaly (ex-data ex)))))))

  (testing "Calling event with params violating spec throws"
    (let [t (tada/init :malli)
          _ (tada/register! t events)]
      (try
        (tada/do! t :foobar {:a 5 :b 2})
        (catch clojure.lang.ExceptionInfo ex
          (is (= :incorrect (:anomaly (ex-data ex))))
          (is (string/starts-with?
               (.getMessage ex)
               "Params for event "))))
      (try
        (tada/do! t :foobar {:b 5})
        (catch clojure.lang.ExceptionInfo ex
          (is (= :incorrect (:anomaly (ex-data ex))))
          (is (string/starts-with?
               (.getMessage ex)
               "Params for event "))))))

  (testing "Calling event with params violating conditions throws"
    (let [t (tada/init :malli)
          _ (tada/register! t events)]
      (try
        (tada/do! t :foobar {:a "foobar" :b 2})
        (catch clojure.lang.ExceptionInfo ex
          (is (= :incorrect (:anomaly (ex-data ex))))
          (is (string/starts-with?
               (.getMessage ex)
               "Condition for event "))))
      (try
        (tada/do! t :foobar {:a "aoeu" :b 3})
        (catch clojure.lang.ExceptionInfo ex
          (is (= :incorrect (:anomaly (ex-data ex))))
          (is (string/starts-with?
               (.getMessage ex)
               "Condition for event "))))))

  (testing "Calling with correct arguments works"
    (let [t (tada/init :malli)
          _ (tada/register! t events)]
      (let [out (with-out-str (is (= true (tada/do! t :foobar {:a "aoeu" :b 2}))))]
        (is (= (pr-str ["aoeu" 2]) out)))))

  (testing "Events with no condition work"
    (let [t (tada/init :malli)]
      (tada/register!
       t
       [{:id :no-conditions
         :params {:a string?}
         :return (fn [_] true)}])
      (is (= true (tada/do! t :no-conditions {:a "asd"})))))

  (testing "Events with no conditions, no params work"
    (let [t (tada/init :malli)]
      (tada/register!
       t
       [{:id :no-conditions-no-params
         :return (fn [_] true)}])
      (is (= true (tada/do! t :no-conditions-no-params {}))))))

(deftest one-by-one-conditions
  (let [side-effects (atom #{})
        t (tada/init :malli)]
    (tada/register!
     t
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
        (tada/do! t :foobar {:a "xoobar" :b 3})
        (catch clojure.lang.ExceptionInfo _))
      (is (= @side-effects #{:a})))))

(clojure.test/run-tests)
