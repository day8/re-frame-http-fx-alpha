(defproject day8.re-frame/http-fx-2 "2.0.0-SNAPSHOT"
  :description  "A re-frame effects handler for fetching resources (including across the network)."
  :url          "https://github.com/Day8/re-frame-http-fx-2.git"
  :license      {:name "MIT"}
  :dependencies [[thheller/shadow-cljs       "2.8.47" :scope "provided"]
                 [org.clojure/clojure        "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript  "1.10.520" :scope "provided"]
                 [re-frame                   "0.10.8" :scope "provided"]]

  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [karma-reporter "3.1.0"]]
                   :plugins      [[lein-shell "0.5.0"]]}}

  :clean-targets  [:target-path
                   "resources/public/js/test"]

  :resource-paths ["run/resources"]
  :jvm-opts       ["-Xmx1g"]

  :source-paths   ["src"]

  :test-paths     ["test"]

  :aliases {"dev" ["do"
                   ["shell" "npm" "install"]
                   ["with-profile" "dev" "run" "-m" "shadow.cljs.devtools.cli" "watch" "browser-test"]]
            "ci"  ["do"
                   ["shell" "npm" "install"]
                   ["run" "-m" "shadow.cljs.devtools.cli" "compile" "ci"]
                   ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]}

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
