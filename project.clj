(defproject day8.re-frame/http-fx-2 "2.0.0-SNAPSHOT"
  :description  "A re-frame effects handler for fetching resources (including across the network)."
  :url          "https://github.com/Day8/re-frame-http-fx-2.git"
  :license      {:name "MIT"}
  :dependencies [[thheller/shadow-cljs       "2.8.39"]
                 [org.clojure/clojure        "1.10.1"]
                 [org.clojure/clojurescript  "1.10.520"]
                 [re-frame                   "0.10.7"]]

  :profiles {:dev   {:dependencies [[binaryage/devtools "0.9.10"]
                                    [karma-reporter     "3.1.0"]]}}

  :clean-targets  [:target-path
                   "resources/public/js/test"]

  :resource-paths ["run/resources"]
  :jvm-opts       ["-Xmx1g"]

  :source-paths   ["src"]

  :test-paths     ["test"]

  :deploy-repositories [["clojars" {:sign-releases false
                                    :url "https://clojars.org/repo"
                                    :username :env/CLOJARS_USERNAME
                                    :password :env/CLOJARS_PASSWORD}]]

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
