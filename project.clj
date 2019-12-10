(defproject    day8.re-frame/http-fx-alpha "lein-git-inject/version"
  :description "A re-frame effects handler for fetching resources (including across the network)."
  :url         "https://github.com/day8/re-frame-http-fx-alpha.git"
  :license     {:name "MIT"}

  :dependencies [[org.clojure/clojure       "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.597" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library
                               org.clojure/google-closure-library-third-party]]
                 [thheller/shadow-cljs      "2.8.81" :scope "provided"]
                 [re-frame                  "0.10.9" :scope "provided"]]

  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.11"]
                                  [karma-reporter     "3.1.0"]]}}

  :plugins      [[day8/lein-git-inject "0.0.4"]
                 [lein-shadow          "0.1.7"]
                 [lein-shell           "0.5.0"]]

  :middleware   [leiningen.git-inject/middleware]

  :clean-targets [:target-path
                  "shadow-cljs.edn"
                  "package.json"
                  "package-lock.json"
                  "resources/public/js/test"]

  :jvm-opts ["-Xmx1g"]

  :source-paths ["src"]
  :test-paths ["test"]
  :resource-paths ["run/resources"]

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

  :aliases {"dev-auto"   ["with-profile" "dev" "do"
                          ["clean"]
                          ["shadow" "watch" "browser-test"]]
            "karma-once" ["do"
                          ["clean"]
                          ["shadow" "compile" "karma-test"]
                          ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]}

  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/CLOJARS_USERNAME
                                    :password      :env/CLOJARS_PASSWORD}]]

  :release-tasks [["deploy" "clojars"]])
