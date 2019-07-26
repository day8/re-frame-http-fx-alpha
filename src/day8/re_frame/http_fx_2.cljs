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

(defn ->str
  "Returns the name String of x if x is a symbol or keyword, otherwise
   x.toString()."
  [x]
  (if (or (symbol? x)
          (keyword? x))
    (name x)
    (str x)))

(defn params->str
  "Returns a URI-encoded string of the params."
  [params]
  (if (zero? (count params))
    ""
    (let [pairs (reduce-kv
                  (fn [ret k v]
                    (conj ret (str (js/encodeURIComponent (->str k)) "="
                                   (js/encodeURIComponent (->str v)))))
                  []
                  params)]
      (str "?" (string/join "&" pairs)))))

(defn headers->js
  "Returns a new js/Headers JavaScript object of the ClojureScript map of headers."
  [headers]
  (reduce-kv
    (fn [js-headers header-name header-value]
      (doto js-headers
        (.append (->str header-name)
                 (->str header-value))))
    (js/Headers.)
    headers))

(defn request->js-init
  "Returns an init options js/Object to use as the second argument to js/fetch."
  [{:keys [method headers body mode credentials cache redirect referrer integrity] :as request}
   js-controller]
  (let [mode (or mode "same-origin")
        credentials (or credentials "include")
        redirect (or redirect "follow")]
    (cond->
      #js {;; There is always a controller, as in our impl all requests can be
           ;; aborted.
           :signal      (.-signal js-controller)

           ;; There is always a method, as dispatch is via sub-effects like :get.
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
      (obj/set "integrity" integrity))))

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
  {:url         (.-url js-response)
   :ok?         (.-ok js-response)
   :redirected? (.-redirected js-response)
   :status      (.-status js-response)
   :status-text (.-statusText js-response)
   :type        (.-type js-response)
   :final-uri?  (.-useFinalURL js-response)
   :headers     (js-headers->clj (.-headers js-response))})

(defn response->reader
  "Returns a keyword of the type of reader to use for the body of the
   response according to the Content-Type header."
  [{:keys [content-types]} response]
  (let [content-type (get-in response [:headers :content-type] "text/plain")]
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
                    (js/setTimeout #(reject :timeout) timeout)))])
    js-promise))

;; Effect Dispatch to Sub-effects
;; =============================================================================

(def sub-effects
  "The set of supported sub-effects."
  #{:get :head :options :post :put :delete
    :trigger :reg-profile :unreg-profile :abort})

(defn sub-effect-dispatch
  "Returns the sub-effect key in m if exactly one exists, otherwise an error
   keyword if zero or many exist."
  [m]
  (let [found (filter sub-effects (keys m))]
    (case (count found)
      0 :failure/missing-sub-effect
      1 (first found)
      :failure/multiple-sub-effects)))

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

(defn profile-swap-fn
  [current {profile-id :reg-profile
            default?   :default?
            :as        profile}]
  (cond-> current
          ;; Store default? profile-id as
          ;; 1) we need to know the 'latest' default; and
          ;; 2) avoid walking all profiles to find default; and
          ;; 3) we do not want to overwrite access to earlier default
          ;;    profile(s) that may still be available via profile-id.
          default?
          (assoc ::default-profile-id profile-id)

          :always
          (assoc profile-id profile)))

(defmethod sub-effect :reg-profile
  [profile]
  (swap! profile-id->profile profile-swap-fn profile))

(defmethod sub-effect :unreg-profile
  [{profile-id :unreg-profile}]
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
    (fn [acc {:keys [combine values]}]
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
  [{:keys [profiles] :as m}]
  (->> profiles
      (get-profiles)
      (conj-profiles m)))

;; Invalid Sub-effects
;; =============================================================================
(defmethod sub-effect :failure/missing-sub-effect
  [m]
  (let [m' (m+profiles m)
        {:keys [in-failure]} m'
        err {:failure :invalid
             :reason :missing-sub-effect
             :debug-message "invalid effect map contains no sub-effect key."}]
    (if in-failure
      (dispatch (conj in-failure nil nil err))
      (console :error "http fx: no in-failure event handler exists to handle this error: " err))))

(defmethod sub-effect :failure/multiple-sub-effects
  [m]
  (let [m' (m+profiles m)
        {:keys [in-failure]} m'
        err {:failure :invalid
             :reason :multiple-sub-effects
             :debug-message "invalid sub-effect map contains multiple sub-effect keys."}]
    (if in-failure
      (dispatch (conj in-failure nil nil err))
      (console :error "http fx: no in-failure event handler exists to handle this error: " err))))

;; Requests
;; =============================================================================

(def request-id->request-and-controller
  "An Atom that contains a mapping of request-ids to requests and their
   associated js/AbortController; i.e.,
   {:http-123 {::request {:state :waiting :method... }
               ::js-controller js/AbortController}}"
  (atom {}))

(def fsm
  "A mapping of states to valid transitions out of that state."
  {:requested  #{:setup :failed}
   :setup      #{:waiting}
   :waiting    #{:problem :processing :cancelled}
   :problem    #{:waiting :failed}
   :processing #{:failed :succeeded}
   :failed     #{:teardown}
   :succeeded  #{:teardown}
   :cancelled  #{:teardown}
   :teardown   #{}})

(def trigger->to-state
  "A mapping of triggers to states."
  {:request   :setup
   :send      :waiting
   :retry     :waiting
   :problem   :problem
   :success   :processing
   :fail      :failed
   :processed :succeeded
   :abort     :cancelled
   :done      :teardown})

(def fsm->event-keys
  "A mapping of states to the event handler to dispatch in that state."
  {:setup      :in-setup
   :waiting    :in-wait
   :problem    :in-problem
   :processing :in-process
   :cancelled  :in-cancelled
   :failed     :in-failed
   :succeeded  :in-succeeded
   :teardown   :in-teardown})

(defn fsm-swap-fn
  "In current value of request-id->request-and-controller moves state of request
   with request-id to-state if it is a valid state transition, otherwise moves
   state to :failed."
  [current request-id to-state merge-request-state]
  (let [{::keys [request]} (get current request-id)
        {from-state :state} request
        valid-to-state? (get fsm from-state #{})]
    (if (valid-to-state? to-state)
      (cond-> current
              (= to-state :cancelled)
              (assoc-in [request-id ::js-controller] nil)
              true
              (assoc-in [request-id ::request :state] to-state)
              true
              (update-in [request-id ::request] merge merge-request-state))
      (update-in current [request-id ::request] assoc
                 :state :failed
                 :failure :fsm))))

;; TODO handle undefined event handler(s); default event handlers

(defn fsm->!
  "Moves state of request with request-id to-state if it is a valid state
   transition, otherwise to :failed. Dispatches to the appropriate event handler.
   Returns nil."
  ([request-id to-state]
   (fsm->! request-id to-state nil))
  ([request-id to-state merge-request-state]
   (let [[_ {{{:keys [state] :as request-state} ::request
              js-controller                     ::js-controller} request-id}]
         (swap-vals! request-id->request-and-controller
                     fsm-swap-fn request-id to-state merge-request-state)
         event-key (get fsm->event-keys state)
         event (or (get request-state event-key) [:no-handler])
         event' (conj event request-state)]
     (when (= :cancelled state)
       (.abort js-controller))
     (when (not= :waiting state)
       (dispatch event')))))

(defn body-handler
  "Dispatches the request with request-id and the associated response with a
   body to the appropriate event handler. Returns nil."
  [request-id response reader js-body]
  (let [body (if (= :json reader) (js->clj js-body :keywordize-keys true) js-body)
        response' (assoc response :body body)]
    (if (:ok? response')
      (fsm->! request-id :processing {:response response'})
      (fsm->! request-id :problem {:response response'
                                   :problem :server}))))

(defn body-failed-handler
  "Dispatches the request with request-id and the associated response to the
   in-failed event handler due to a failure reading the body. Returns nil."
  [request-id response reader js-error]
  ;(console :error js-error)
  (fsm->! request-id :failed {:response response
                              :problem :body}))

(defn response-handler
  "Reads the js/Response JavaScript object stream, that is associated with the
   request with request-id, to completion. Returns nil."
  [request-id js-response]
  (let [{{request ::request} request-id} @request-id->request-and-controller
        response (js-response->clj js-response)
        reader (response->reader request response)]
    (-> (case reader
          :json         (.json js-response)
          :form-data    (.formData js-response)
          :blob         (.blob js-response)
          :array-buffer (.arrayBuffer js-response)
          :text         (.text js-response))
        (.then (partial body-handler request-id response reader))
        (.catch (partial body-failed-handler request-id response reader)))))

(defn problem-handler
  [request-id js-error]
  ;;(console :error js-error)
  (fsm->! request-id :problem {:problem js-error}))

(defn fetch
  "Initiate the request. Returns nil."
  [{:keys [request-id]}]
  (fsm->! request-id :waiting)
  (let [{::keys [request js-controller]} (get @request-id->request-and-controller request-id)
        {:keys [url timeout]} request]
    (-> (timeout-race (js/fetch url (request->js-init request js-controller)) timeout)
        (.then (partial response-handler request-id))
        (.catch (partial problem-handler request-id)))
    nil))

(defn setup
  "Initialise the request. Returns nil."
  [{:keys [url params] :as request}]
  (let [request-id (keyword (gensym "http-"))
        url' (str url (params->str params))
        request' (-> request
                     (merge {:request-id request-id
                             :url        url'
                             :state      :requested})
                     (m+profiles))
        js-controller (js/AbortController.)]
    (swap! request-id->request-and-controller
           #(assoc %1 %2 %3)
           request-id
           {::request       request'
            ::js-controller js-controller})
    (fsm->! request-id :setup)
    nil))

(defmethod sub-effect :get
  [{url :get :as request}]
  (setup (merge request {:method "GET" :url url})))

(defmethod sub-effect :head
  [{url :head :as request}]
  (setup (merge request {:method "HEAD" :url url})))

(defmethod sub-effect :post
  [{url :post :as request}]
  (setup (merge request {:method "POST" :url url})))

(defmethod sub-effect :put
  [{url :put :as request}]
  (setup (merge request {:method "PUT" :url url})))

(defmethod sub-effect :delete
  [{url :delete :as request}]
  (setup (merge request {:method "DELETE" :url url})))

(defmethod sub-effect :options
  [{url :options :as request}]
  (setup (merge request {:method "OPTIONS" :url url})))

(defmethod sub-effect :trigger
  [{:keys [request-id trigger]}]
  (let [request (get-in @request-id->request-and-controller [request-id ::request])
        to-state (get trigger->to-state trigger)]
    (when (#{:send :retry} trigger)
      (fetch request))
    (fsm->! request-id to-state)))

;; Abort
;; =============================================================================

(defmethod sub-effect :abort
  [{request-id :abort}]
  (fsm->! request-id :cancelled))

(defn abort-event-handler
  "Generic HTTP abort event handler."
  [_ [_ request-id]]
  {:http {:abort request-id}})

(reg-event-fx :abort abort-event-handler)