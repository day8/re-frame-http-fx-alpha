(ns day8.re-frame.http-fx-2-test
  (:require
    [clojure.test :refer [deftest is testing async use-fixtures]]
    [clojure.spec.alpha :as s]
    [goog.object :as obj]
    [re-frame.core :as re-frame]
    [day8.re-frame.http-fx-2 :as http-fx-2]))

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
         (http-fx-2/params->str {:sort :desc :start 0})))
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
           (http-fx-2/js-headers->clj (http-fx-2/headers->js headers))))))

(deftest js-response->clj
  (is (= {:url ""
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
           {:headers {:content-type "application/json"}})))
  (is (= :blob
         (http-fx-2/response->reader
           {:content-types {"text/plain" :blob}}
           {:headers {}})))
  (is (= :json
         (http-fx-2/response->reader
           {:content-types {#"(?i)application/.*json" :json}}
           {:headers {:content-type "application/json"}}))))

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
         (http-fx-2/conj-profiles
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
  (is (= {:http-xyz {::http-fx-2/request       {:state :problem}
                     ::http-fx-2/js-controller {}}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :problem
           nil)))
  (is (= {:http-xyz {::http-fx-2/request       {:state :cancelled}
                     ::http-fx-2/js-controller nil}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :cancelled
           nil)))
  (is (= {:http-xyz {::http-fx-2/request       {:state :failed
                                                       :problem :fsm
                                                       :problem-from-state :waiting
                                                       :problem-to-state :done}
                     ::http-fx-2/js-controller {}}}
         (http-fx-2/fsm-swap-fn
           {:http-xyz {::http-fx-2/request       {:state :waiting}
                       ::http-fx-2/js-controller {}}}
           :http-xyz
           :done
           nil))))
