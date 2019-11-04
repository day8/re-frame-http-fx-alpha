(ns day8.re-frame.http-fx-alpha-test
  (:require
    [clojure.test :refer [deftest is testing async use-fixtures]]
    [clojure.spec.alpha :as s]
    [goog.object :as obj]
    [re-frame.core :as re-frame]
    [day8.re-frame.http-fx-alpha :as http-fx-alpha]))

;; Utilities
;; =============================================================================

(deftest ->seq-test
  (is (= [{}]
         (http-fx-alpha/->seq {})))
  (is (= [{}]
         (http-fx-alpha/->seq [{}])))
  (is (= [nil]
         (http-fx-alpha/->seq nil))))

(deftest ->str-test
  (is (= ""
         (http-fx-alpha/->str nil)))
  (is (= "42"
         (http-fx-alpha/->str 42)))
  (is (= "salient"
         (http-fx-alpha/->str :salient)))
  (is (= "symbolic"
         (http-fx-alpha/->str 'symbolic))))

(deftest ->params->str-test
  (is (= ""
         (http-fx-alpha/params->str nil)))
  (is (= ""
         (http-fx-alpha/params->str {})))
  (is (= "?sort=desc&start=0"
         (http-fx-alpha/params->str {:sort :desc :start 0})))
  (is (= "?fq=Expect%20nothing%2C%20%5Ba-z%26%26%5B%5Eaeiou%5D%5D&debug=timing"
         (http-fx-alpha/params->str {:fq "Expect nothing, [a-z&&[^aeiou]]"}
                                 :debug 'timing))))

(deftest headers->js-test
  (let [js-headers (http-fx-alpha/headers->js {:content-type "application/json"})]
    (is (instance? js/Headers js-headers))
    (is (= "application/json"
           (.get js-headers "content-type")))))

(deftest request->js-init-test
  (let [controller (js/AbortController.)
        js-init (http-fx-alpha/request->js-init
                  {:method "GET"}
                  controller)]
    (is (= "{\"signal\":{},\"method\":\"GET\",\"mode\":\"same-origin\",\"credentials\":\"include\",\"redirect\":\"follow\"}"
           (js/JSON.stringify js-init)))
    (is (= (.-signal controller)
           (.-signal js-init)))))

(deftest js-headers->clj-test
  (let [headers {:content-type "application/json"
                 :server       "nginx"}]
    (is (= headers
           (http-fx-alpha/js-headers->clj (http-fx-alpha/headers->js headers))))))

(deftest js-response->clj
  (is (= {:url ""
          :ok? true
          :redirected? false
          :status 200
          :status-text ""
          :type "default"
          :final-uri? nil
          :headers {}}
         (http-fx-alpha/js-response->clj (js/Response.)))))

(deftest response->reader-test
  (is (= :text
         (http-fx-alpha/response->reader
           {}
           {:headers {:content-type "application/json"}})))
  (is (= :blob
         (http-fx-alpha/response->reader
           {:content-types {"text/plain" :blob}}
           {:headers {}})))
  (is (= :json
         (http-fx-alpha/response->reader
           {:content-types {#"(?i)application/.*json" :json}}
           {:headers {:content-type "application/json"}}))))

(deftest timeout-race-test
  (async done
    (-> (http-fx-alpha/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 16)))
          32)
        (.catch (fn [value]
                  (is (= :winner value))
                  (done)))))
  (async done
    (-> (http-fx-alpha/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 32)))
          16)
        (.catch (fn [value]
                  (is (= :timeout value))
                  (done))))))

;; Profiles
;; =============================================================================

; {:params {:sort :desc, :jwt "eyJhbGc..."}, :headers {:accept "application/json", :authorization "Bearer eyJhbGc..."}, :path [:a :b :c :d :e], :fsm {:in-process [:in-process-a], :in-problem [:in-problem-b]}, :timeout 5000, :profiles [:xyz :jwt], :action :GET, :url "http://api.example.com/articles"}
; {:params {:sort :desc, :jwt "eyJhbGc..."}, :headers {:accept "application/json", :authorization "Bearer eyJhbGc..."}, :path [:d :e],          :fsm {:in-process [:in-process-a], :in-problem [:in-problem-b]}, :timeout 5000 :profiles [:xyz :jwt], :action :GET, :url "http://api.example.com/articles",}))

(deftest conj-profiles-test
  (is (= {:params {:sort :desc
                   :jwt  "eyJhbGc..."}
                  :headers    {:accept        "application/json"
                               :authorization "Bearer eyJhbGc..."}
                  :path       [:a :b :c :d :e]
                  :fsm {:in-process [:in-process-a]
                        :in-problem [:in-problem-b]}
                  :timeout    5000
                  :profiles   [:xyz :jwt]
                  :action     :GET
                  :url        "http://api.example.com/articles"}
         (http-fx-alpha/conj-profiles
           {:profiles   [:xyz :jwt]
            :action     :GET
            :url        "http://api.example.com/articles"
            :fsm {:in-process [:in-process-a]
                  :in-problem [:in-problem-a]}
            :params     {:sort :desc}
            :headers    {:accept "application/json"}
            :path       [:a :b :c]}
           [{:reg-profile :xyz
             :values      {:fsm {:in-problem [:in-problem-b]}
                           :timeout    5000}}
            {:reg-profile :jwt
             :values      {:params  {:jwt "eyJhbGc..."}
                           :headers {:authorization "Bearer eyJhbGc..."}
                           :path    [:d :e]}}]))))

;; Requests
;; =============================================================================

(deftest fsm-swap-fn
  (is (= {:http-xyz {::http-fx-alpha/request       {:state :problem}
                     ::http-fx-alpha/js-controller {}}}
         (http-fx-alpha/fsm-swap-fn
           {:http-xyz {::http-fx-alpha/request       {:state :waiting}
                       ::http-fx-alpha/js-controller {}}}
           :http-xyz
           :problem
           nil)))
  (is (= {:http-xyz {::http-fx-alpha/request       {:state :cancelled}
                     ::http-fx-alpha/js-controller nil}}
         (http-fx-alpha/fsm-swap-fn
           {:http-xyz {::http-fx-alpha/request       {:state :waiting}
                       ::http-fx-alpha/js-controller {}}}
           :http-xyz
           :cancelled
           nil)))
  (is (= {:http-xyz {::http-fx-alpha/request       {:state :failed
                                                       :problem :fsm
                                                       :problem-from-state :waiting
                                                       :problem-to-state :done}
                     ::http-fx-alpha/js-controller {}}}
         (http-fx-alpha/fsm-swap-fn
           {:http-xyz {::http-fx-alpha/request       {:state :waiting}
                       ::http-fx-alpha/js-controller {}}}
           :http-xyz
           :done
           nil))))
