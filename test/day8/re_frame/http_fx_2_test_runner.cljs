(ns day8.re-frame.http-fx-2-test-runner
  (:require
    [clojure.test :refer [run-all-tests]]
    [jx.reporter.karma :as karma :include-macros true]
    [day8.re-frame.http-fx-2-test])
  (:refer-clojure :exclude [set-print-fn!]))

(enable-console-print!)

(defn ^:export set-print-fn! [f]
  (set! cljs.core.*print-fn* f))

(defn ^:export run-html-tests []
  (run-all-tests #"day8.re-frame.http-fx-2-test"))

(defn ^:export run-karma [karma]
  (karma/run-tests
    karma
    'day8.re-frame.http-fx-2-test))