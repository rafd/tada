# TADA

A Clojure library for declarative events.

Basic usage looks like this:

```clojure
;; Register our events
(tada/register-events!
    [{:id :foobar
      :params {:a string?
               :b integer?}
      :conditions (fn [{:keys [a b]}]
                    [[(string/starts-with? a "a") :incorrect
                      "A must start with 'a'"]
                     [(even? b) :incorrect "B must be even"]])
      :effect (fn [{:keys [a b]}] (prn "EFFECT" [a b]))}
     {:id :bazquux
      :params {:c (ds/spec {:name "beep" :spec {keyword? string?}})}
      :conditions (fn [{:keys [c]}]
                    [[(< 2 (count (keys c))) :incorrect
                      "C must have at least two keys"]])
      :effect (fn [{:keys [c]}] (prn "EFFECT" c))}])

;; when we call with everything correct, it runs the effect
(tada/do! :foobar {:a "aoeu" :b 2}) ;; "EFFECT" ["aoeu" 2]
;; if called with invalid arguments, an error is raised
(tada/do! :foobar {:a "aoeu" :b 3}) ;; error: "B must be even"
(tada/do! :foobar {:a "foo" :b 2}) ;; error: "A must start with 'a'"
(tada/do! :foobar {:a "aoeu"}) ;; error: [:b]: :missing-key
```
