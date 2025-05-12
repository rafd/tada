(defproject tada :lein-v
  :description "A clojure(script) library that helps you compose web-applications out of declarative data-driven parts"
  :url "https://github.com/rafd/tada"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [metosin/malli "0.16.2"]]

  :plugins [[com.roomkey/lein-v "7.0.0"]]

  :release-tasks [["vcs" "assert-committed"]
                  ["v" "update"]
                  ["vcs" "push"]
                  ["deploy" "clojars"]]

  :profiles {:test {:dependencies [[metosin/reitit "0.3.9"]]}})
