(ns worker.core
  "Cloudflare Worker entry point with routing and request handlers."
  (:require [reitit.core :as r]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as malli]
            [worker.ring :as ring]
            [worker.bom :as bom]))

;; -----------------------------------------------------------------------------
;; Route Handlers
;;
;; Handlers receive Ring-style request maps:
;;   {:request-method :get
;;    :uri            "/path"
;;    :query-params   {:q "value"}
;;    :path-params    {:id "123"}
;;    :body-params    {...}
;;    :headers        {...}}
;;
;; Handlers return response maps (or Promise of response map):
;;   {:status 200
;;    :headers {"content-type" "..."}
;;    :body ...}

(defn- api-hello-handler
  "Returns a simple JSON greeting with timestamp."
  [_request]
  (ring/json-response {:message "Hello from ClojureScript!"
                       :timestamp (.now js/Date)}))

(defn- forecast-handler
  "Returns forecast data for matching locations.
   Query param 'q' is validated by Malli coercion."
  [{:keys [parameters]}]
  (let [{:keys [q]} (:query parameters)]
    (-> (bom/fetch-forecast q)
        (.then (fn [results]
                 (ring/json-response {:query q
                                      :results results
                                      :count (count results)}))))))

(defn- not-found-handler
  "Returns 404 for unknown routes."
  [_request]
  (ring/text-response "Not Found" 404))

(def router
  (r/router
   [["/api/hello" {:name ::api-hello
                   :handler api-hello-handler}]
    ["/api/forecast" {:name ::api-forecast
                      :handler forecast-handler
                      :coercion malli/coercion
                      :parameters {:query [:map [:q :string]]}}]]
   {:compile coercion/compile-request-coercers}))


(def ^:private handle-request
  "Main request handler - wraps router with Ring adapter."
  (ring/wrap-routes router not-found-handler))

(def handler-obj
  "Export object for Cloudflare Workers with fetch handler."
  #js {:fetch (fn [request _env _ctx]
                (handle-request request))})
