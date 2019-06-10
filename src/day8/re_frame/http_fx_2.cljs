(ns day8.re-frame.http-fx-2
  (:require
    [goog.object :as obj]
    [re-frame.core :refer [reg-fx reg-event-fx dispatch console]]
    [clojure.string :as string]))

;; Utilities
;; =============================================================================

(defn ->seq
  "Returns x if x satisfies ISequential, otherwise vector of x."
  [x]
  (if (sequential? x) x [x]))

(defn params->str
  "Returns a URI-encoded string of the params."
  [params]
  (if (not params)
    ""
    (let [pairs (reduce-kv
                  (fn [ret k v]
                    (conj ret (str (js/encodeURIComponent (name k)) "="
                                   (js/encodeURIComponent (name v)))))
                  []
                  params)]
      (str "?" (string/join "&" pairs)))))

(defn headers->js
  "Returns a new js/Headers JavaScript object of the ClojureScript map of headers."
  [headers]
  (reduce-kv
    (fn [js-headers header-name header-value]
      (.append js-headers
               (name header-name)
               (name header-value)))
    (js/Headers.)
    headers))

(defn request->js-init
  "Returns an init options js/Object to use as the second argument to js/fetch."
  [{:http/keys [method headers body mode credentials cache redirect referrer integrity] :as request
    :or        {http/mode "same-origin"
                http/credentials "include"
                http/redirect "follow"}}
   controller]
  (cond->
    #js {;; There is always a controller, as in our impl all requests can be
         ;; aborted.
         :signal      (.-signal controller)

         ;; There is always a method, as dispatch is via sub-effects like :http/get.
         :method      method

         ;; Although the below keys are usually optional, the default between
         ;; different browsers is inconsistent so we always set our own default.

         ;; Possible: cors no-cors same-origin navigate
         :mode        mode

         ;; Possible: omit same-origin include
         :credentials credentials

         ;; Possible: follow error manual
         :redirect    redirect}

    ;; Everything else is optional...
    headers
    (obj/set "headers" (headers->js headers))

    body
    (obj/set "body" body)

    ;; Possible: default no-store reload no-cache force-cache only-if-cached
    cache
    (obj/set "cache" cache)

    ;; Possible: no-referrer client
    referrer
    (obj/set "referrer" referrer)

    ;; Sub-resource integrity string
    integrity
    (obj/set "integrity" integrity)))

(defn js-headers->clj
  "Returns a new ClojureScript map of the js/Headers JavaScript object."
  [js-headers]
  (reduce
    (fn [headers [header-name header-value]]
      (assoc headers (keyword header-name) header-value))
    {}
    (es6-iterator-seq (.entries js-headers))))

(defn js-response->clj
  "Returns a new ClojureScript map of the js/Response JavaScript object."
  [js-response]
  {:http/url         (.-url js-response)
   :http/ok?         (.-ok js-response)
   :http/redirected? (.-redirected js-response)
   :http/status      (.-status js-response)
   :http/status-text (.-statusText js-response)
   :http/type        (.-type js-response)
   :http/final-uri?  (.-useFinalURL js-response)
   :http/headers     (js-headers->clj (.-headers js-response))})

(defn js-response->reader
  "Returns a keyword of the type of reader to use for the body of the
   js/Response JavaScript object according to the Content-Type header."
  [{:http/keys [content-types]} js-response]
  (let [content-type (some-> (.headers js-response)
                             (.get "Content-Type"))]
    (reduce-kv
      (fn [ret pattern reader]
        (if (or (and (string? pattern) (= content-type pattern))
                (and (regexp? pattern) (re-matches pattern content-type)))
          (reduced reader)
          ret))
      :text
      content-types)))

(defn timeout-race
  "Returns a js/Promise JavaScript object that is a race between another
   js/Promise JavaScript object and timeout in ms if timeout is not nil,
   otherwise js-promise."
  [js-promise timeout]
  (if timeout
    (.race js/Promise
           #js [js-promise
                (js/Promise.
                  (fn [_ reject]
                    (js/setTimeout #(reject :error/timeout) timeout)))])
    js-promise))

;; Effect Dispatch to Sub-effects
;; =============================================================================

(def sub-effects
  "The set of supported sub-effects."
  #{:http/get :http/head :http/options :http/post :http/put :http/delete
    :http/transition :http/reg-profile :http/unreg-profile :http/abort})

(defn sub-effect-dispatch
  "Returns the sub-effect key in m if exactly one exists, otherwise an error
   keyword if zero or many exist."
  [m]
  (let [found (filter sub-effects (keys m))]
    (case (count found)
      0 ::error-missing-sub-effect
      1 (first found)
      ::error-multiple-sub-effects)))

(defmulti sub-effect sub-effect-dispatch)

(defn http-fx
  "Executes the HTTP effect via value-based dispatch to sub-effects."
  [effect]
  (let [seq-of-sub-effects (->seq effect)]
    (doseq [effect seq-of-sub-effects]
      (sub-effect effect))))

(reg-fx :http http-fx)

;; Profiles
;; =============================================================================

(def profile-id->profile
  "An Atom that contains a mapping of profile-ids to profile maps."
  (atom {}))

(defmethod sub-effect :http/reg-profile
  [profile]
  (swap! profile-id->profile
         (fn [current-value
              {profile-id :http/reg-profile
               default?   :http/default?
               :as        profile}]
           (cond-> current-value
                   ;; Store default? profile-id as
                   ;; 1) we need to know the 'latest' default; and
                   ;; 2) avoid walking all profiles to find default; and
                   ;; 3) we do not want to overwrite access to earlier default
                   ;;    profile(s) that may still be available via profile-id.
                   default?
                   (assoc ::default-profile-id profile-id)

                   :always
                   (assoc profile-id profile)))
         profile))

(defmethod sub-effect :http/unreg-profile
  [{profile-id :http/unreg-profile}]
  (swap! profile-id->profile #(dissoc %1 %2) profile-id))

(defn get-profile
  "Returns a profile map for the profile-id if one exists in profiles, otherwise
   nil."
  [profiles profile-id]
  (case profile-id
    ;; Special case :none means do not get any profile!
    :none nil

    ;; nil effectively means no profile was requested, thus get default.
    nil (get profiles (get profiles ::default-profile-id))

    ;; Otherwise, just get the profile with the profile-id.
    (get profiles profile-id)))

(defn get-profiles
  "Returns a lazy sequence of profile maps for the profile-id(s)."
  [profile-id]
  (let [seq-of-profile-ids (if (sequential? profile-id) profile-id [profile-id])
        profiles @profile-id->profile]
    (->> seq-of-profile-ids
         (map (partial get-profile profiles))
         (filter identity))))

(defn conj-profiles
  "Returns a new map with the seq-of-profile-maps 'added' to the sub-effect map."
  [m seq-of-profile-maps]
  (reduce
    (fn [acc {:http/keys [combine values]}]
      (reduce-kv
        (fn [foo k f]
          (let [existing (get acc k)
                addition (get values k)]
            (assoc foo k (f existing addition))))
        (merge values acc)
        combine))
    m
    seq-of-profile-maps))

(defn m+profiles
  ""
  [{:http/keys [profiles] :as m}]
  (->> profiles
      (get-profiles)
      (conj-profiles m)))

;; Invalid Sub-effects
;; =============================================================================
(defmethod sub-effect ::error-missing-sub-effect
  [m]
  (let [m' (m+profiles m)
        {:http/keys [in-failure]} m'
        err {:http/failure :invalid
             :http/reason :missing-sub-effect
             :http/debug-message "invalid effect map contains no sub-effect key."}]
    (if in-failure
      (dispatch (conj in-failure nil nil err))
      (console :error "http fx: no in-failure event handler exists to handle this error: " err))))

(defmethod sub-effect ::error-multiple-sub-effects
  [m]
  (let [m' (m+profiles m)
        {:http/keys [in-failure]} m'
        err {:http/failure :invalid
             :http/reason :multiple-sub-effects
             :http/debug-message "invalid sub-effect map contains multiple sub-effect keys."}]
    (if in-failure
      (dispatch (conj in-failure nil nil err))
      (console :error "http fx: no in-failure event handler exists to handle this error: " err))))

;; Requests
;; =============================================================================

(def request-id->request-and-controller
  "An Atom that contains a mapping of request-ids to requests and their
   associated js/AbortController; i.e.,
   {:http-123 {::request {:state :waiting :method... }
               ::controller js/AbortController}}"
  (atom {}))

(def fsm
  "A mapping of states to valid transitions out of that state."
  {:requested  #{:waiting :failed}
   :waiting    #{:problem :processing :cancelled}
   :problem    #{:requested :failed}
   :processing #{:failed :succeeded}
   :failed     #{:done}
   :succeeded  #{:done}
   :cancelled  #{:done}
   :done       #{}})

(def fsm->event-keys
  "A mapping of states to the event handler to dispatch in that state."
  {:waiting    :http/in-wait
   :problem    :http/in-problem
   :processing :http/in-process
   :cancelled  :http/in-cancelled
   :failed     :http/in-failed
   :succeeded  :http/in-succeeded
   :done       :http/in-done})

(defn fsm-swap-fn
  "In current value of request-id->request-and-controller moves state of request
   with request-id to-state if it is a valid state transition, otherwise moves
   state to :failed."
  [current request-id to-state]
  (let [{::keys [request]} (get current request-id)
        {from-state :http/state} request
        valid-to-state? (get fsm from-state)]
    (if (valid-to-state? to-state)
      (cond-> current
              (= to-state :cancelled)
              (assoc-in [request-id ::controller] nil)
              :always
              (assoc-in [request-id ::request :http/state] to-state))
      (update-in current [request-id ::request] assoc
                 :http/state :failed
                 :http/problem :fsm))))

(defn fsm->!
  "Moves state of request with request-id to-state if it is a valid state
   transition, otherwise to :failed. Dispatches to the appropriate event handler.
   Returns nil."
  ([request-id to-state]
   (fsm->! request-id to-state nil nil))
  ([request-id to-state response]
   (fsm->! request-id to-state response nil))
  ([request-id to-state response error]
   (let [[_ {{{:http/keys [state] :as request} ::request
              js-controller                    ::js-controller} request-id}]
         (swap-vals! request-id->request-and-controller
                     fsm-swap-fn request-id to-state)
         event-key (get fsm->event-keys state)
         event (conj (get request event-key) request response error)]
     (when (= :cancelled state)
       (.abort js-controller))
     (dispatch event))))

(defn body-handler
  "Dispatches the request with request-id and the associated response with a
   body to the appropriate event handler. Returns nil."
  [request-id js-response js-body]
  (let [response (-> (js-response->clj js-response)
                     (assoc :body js-body))]
    (if (:ok? response)
      (fsm->! request-id :processing response)
      (fsm->! request-id :problem response {:http/problem :server}))))

(defn body-failed-handler
  "Dispatches the request with request-id and the associated response to the
   in-failed event handler due to a failure reading the body. Returns nil."
  [request-id js-response js-error]
  (let [response (js-response->clj js-response)]
    (console :error js-error)
    (fsm->! request-id :failed response {:http/problem :body})))

(defn response-handler
  "Reads the js/Response JavaScript object stream, that is associated with the
   request with request-id, to completion. Returns nil."
  [request-id js-response]
  (let [{{request ::request} request-id} @request-id->request-and-controller]
    (-> (case (js-response->reader request js-response)
          :json         (.json js-response)
          :form-data    (.formData js-response)
          :blob         (.blob js-response)
          :array-buffer (.arrayBuffer js-response)
          :text         (.text js-response))
        (.then (partial body-handler request-id js-response))
        (.catch (partial body-failed-handler request-id js-response)))))

(defn problem-handler
  [request-id js-error]
  (console :error js-error)
  (fsm->! request-id :problem nil {:http/problem js-error}))

(defn fetch
  [{:http/keys [request-id url params timeout] :as request
    :or        {:http/request-id (keyword (gensym "http-"))}}]
  (let [url'     (str url (params->str params))
        request' (-> request
                     (merge #:http {:request-id request-id
                                    :url        url'
                                    :state      :waiting})
                     (m+profiles))
        controller (js/AbortController.)]
    (swap! request-id->request-and-controller
           #(assoc %1 %2 %3)
           request-id
           {::request    request'
            ::controller controller})
    (-> (timeout-race (js/fetch url' (request->js-init request' controller)) timeout)
        (.then (partial response-handler request-id))
        (.catch (partial problem-handler request-id)))))

(defmethod sub-effect :http/get
  [{url :http/get :as request}]
  (fetch (merge request #:http {:method "GET" :url url})))

(defmethod sub-effect :http/head
  [{url :http/head :as request}]
  (fetch (merge request #:http {:method "HEAD" :url url})))

(defmethod sub-effect :http/post
  [{url :http/post :as request}]
  (fetch (merge request #:http {:method "POST" :url url})))

(defmethod sub-effect :http/put
  [{url :http/put :as request}]
  (fetch (merge request #:http {:method "PUT" :url url})))

(defmethod sub-effect :http/delete
  [{url :http/delete :as request}]
  (fetch (merge request #:http {:method "DELETE" :url url})))

(defmethod sub-effect :http/options
  [{url :http/options :as request}]
  (fetch (merge request #:http {:method "OPTIONS" :url url})))

(defmethod sub-effect :http/transition
  [{to-state                  :http/transition
    {:http/keys [request-id]} :http/request}]
  (fsm->! request-id to-state))

;; Abort
;; =============================================================================

(defmethod sub-effect :http/abort
  [{request-id :http/abort}]
  (fsm->! request-id :cancelled))

(defn abort-event-handler
  "Generic HTTP abort event handler."
  [_ [_ request-id]]
  {:http #:http {:abort request-id}})

(reg-event-fx :http/abort abort-event-handler)