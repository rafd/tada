(defproject tada :lein-v
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [metosin/spec-tools "0.10.0"]
                 [org.clojure/core.match "0.3.0"]
                 [org.clojure/test.check "0.10.0"]]

  :plugins [[com.roomkey/lein-v "7.0.0"]]

  :release-tasks [["vcs" "assert-committed"]
                  ["v" "update"]
                  ["vcs" "push"]
                  ["deploy" "clojars"]]

  :profiles {:test {:dependencies [[metosin/reitit "0.3.9"]]}})
