(defproject day8.re-frame/http-fx-2 "2.0.0-SNAPSHOT"
  :description  "A re-frame effects handler for fetching resources (including across the network)."
  :url          "https://github.com/Day8/re-frame-http-fx-2.git"
  :license      {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library]]
                 [thheller/shadow-cljs "2.8.51" :scope "provided"]
                 [re-frame "0.10.9" :scope "provided"]]

  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [karma-reporter "3.1.0"]]
                   :plugins      [[lein-shell "0.5.0"]]}}

  :plugins [[lein-shadow "0.1.5"]]
  
  :clean-targets  [:target-path
                   ".shadow-cljs"
                   "shadow-cljs.edn"
                   "package.json"
                   "package-lock.json"
                   "resources/public/js/test"]

  :resource-paths ["run/resources"]
  :jvm-opts       ["-Xmx1g"]

  :source-paths   ["src"]

  :test-paths     ["test"]

  :shadow-cljs {:nrepl  {:port 8777}

                :builds {:browser-test
                         {:target    :browser-test
                          :ns-regexp "-test$"
                          :test-dir  "resources/public/js/test"
                          :devtools  {:http-root "resources/public/js/test"
                                      :http-port 8290}}

                         :karma-test
                         {:target    :karma
                          :ns-regexp "-test$"
                          :output-to "target/karma-test.js"}}}

  :aliases {"dev-auto" ["with-profile" "dev" "shadow" "watch" "browser-test"]
            "test-once"  ["do"
                   ["shadow" "compile" "karma-test"]
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
