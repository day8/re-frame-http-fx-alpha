(ns day8.re-frame.http-fx-2-test
  (:require
    [clojure.test :refer [deftest is testing async use-fixtures]]
    [clojure.spec.alpha :as s]
    [re-frame.core :as re-frame]
    [day8.re-frame.http-fx-2 :as http-fx-2]
    [goog.object :as obj]))

;; Utilities
;; =============================================================================

(deftest ->seq-test
  (is (= [{}]
         (http-fx-2/->seq {})))
  (is (= [{}]
         (http-fx-2/->seq [{}])))
  (is (= [nil]
         (http-fx-2/->seq nil))))

(deftest ->str-test
  (is (= ""
         (http-fx-2/->str nil)))
  (is (= "42"
         (http-fx-2/->str 42)))
  (is (= "salient"
         (http-fx-2/->str :salient)))
  (is (= "symbolic"
         (http-fx-2/->str 'symbolic))))

(deftest ->params->str-test
  (is (= ""
         (http-fx-2/params->str nil)))
  (is (= ""
         (http-fx-2/params->str {})))
  (is (= "?sort=desc&start=0"
         (http-fx-2/params->str {:sort :desc
                                 :start 0})))
  (is (= "?fq=Expect%20nothing%2C%20%5Ba-z%26%26%5B%5Eaeiou%5D%5D&debug=timing"
         (http-fx-2/params->str {:fq "Expect nothing, [a-z&&[^aeiou]]"
                                 :debug 'timing}))))

(deftest headers->js-test
  (let [js-headers (http-fx-2/headers->js {:content-type "application/json"})]
    (is (instance? js/Headers js-headers))
    (is (= "application/json"
           (.get js-headers "content-type")))))

(deftest request->js-init-test
  (let [controller (js/AbortController.)
        js-init (http-fx-2/request->js-init
                  #:http {:method "GET"}
                  controller)]
    (is (= "{\"signal\":{},\"method\":\"GET\",\"mode\":\"same-origin\",\"credentials\":\"include\",\"redirect\":\"follow\"}"
           (js/JSON.stringify js-init)))
    (is (= (.-signal controller)
           (.-signal js-init)))))

(deftest js-headers->clj-test
  (let [headers {:content-type "application/json"
                 :server       "nginx"}]
    (is (= headers
           (http-fx-2/js-headers->clj (http-fx-2/headers->js headers))))))

(deftest js-response->clj
  (is (= #:http {:url ""
                 :ok? true
                 :redirected? false
                 :status 200
                 :status-text ""
                 :type "default"
                 :final-uri? nil
                 :headers {}}
         (http-fx-2/js-response->clj (js/Response.)))))

(deftest response->reader-test
  (is (= :text
         (http-fx-2/response->reader
           {}
           #:http {:headers {:content-type "application/json"}})))
  (is (= :blob
         (http-fx-2/response->reader
           #:http {:content-types {"text/plain" :blob}}
           #:http {:headers {}})))
  (is (= :json
         (http-fx-2/response->reader
           #:http {:content-types {#"(?i)application/.*json" :json}}
           #:http {:headers {:content-type "application/json"}}))))

(deftest timeout-race-test
  (async done
    (-> (http-fx-2/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 16)))
          32)
        (.catch (fn [value]
                  (is (= :winner value))
                  (done)))))
  (async done
    (-> (http-fx-2/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 32)))
          16)
        (.catch (fn [value]
                  (is (= :problem/timeout value))
                  (done))))))

;; Effect Dispatch to Sub-effects
;; =============================================================================

(deftest sub-effect-dispatch-test
  (is (= :failure/missing-sub-effect
         (http-fx-2/sub-effect-dispatch {})))
  (is (= :failure/missing-sub-effect
         (http-fx-2/sub-effect-dispatch #:http {:bogus ""})))
  (is (= :failure/multiple-sub-effects
         (http-fx-2/sub-effect-dispatch #:http {:get "" :head ""})))
  (is (= :http/get
         (http-fx-2/sub-effect-dispatch #:http {:get ""})))
  (is (= :http/head
         (http-fx-2/sub-effect-dispatch #:http {:head ""})))
  (is (= :http/options
         (http-fx-2/sub-effect-dispatch #:http {:options ""})))
  (is (= :http/post
         (http-fx-2/sub-effect-dispatch #:http {:post ""})))
  (is (= :http/put
         (http-fx-2/sub-effect-dispatch #:http {:put ""})))
  (is (= :http/delete
         (http-fx-2/sub-effect-dispatch #:http {:delete ""})))
  (is (= :http/transition
         (http-fx-2/sub-effect-dispatch #:http {:transition ""})))
  (is (= :http/reg-profile
         (http-fx-2/sub-effect-dispatch #:http {:reg-profile ""})))
  (is (= :http/unreg-profile
         (http-fx-2/sub-effect-dispatch #:http {:unreg-profile ""})))
  (is (= :http/abort
         (http-fx-2/sub-effect-dispatch #:http {:abort ""}))))

;; Requests
;; =============================================================================

(deftest fsm-swap-fn
  (is (= {:http-xyz {::http-fx-2/request       #:http {:state :problem}
                     ::http-fx-2/js-controller {}}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       #:http {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :problem)))
  (is (= {:http-xyz {::http-fx-2/request       #:http {:state :cancelled}
                     ::http-fx-2/js-controller nil}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       #:http {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :cancelled)))
  (is (= {:http-xyz {::http-fx-2/request       #:http {:state :failed
                                                       :failure :fsm}
                     ::http-fx-2/js-controller {}}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       #:http {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :done))))
