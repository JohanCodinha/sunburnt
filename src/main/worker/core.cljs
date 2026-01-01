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

(defn- search-places
  "Search for places matching query in D1 database.
   Returns top matches ordered by importance."
  [db query]
  (-> (.prepare db "SELECT name, type, state, lat, lon, bom_aac, importance
                    FROM places
                    WHERE name LIKE ?
                    ORDER BY importance DESC, name ASC
                    LIMIT 10")
      (.bind (str "%" query "%"))
      (.all)
      (.then (fn [result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- forecast-handler
  "Returns forecast data for matching locations.
   Query param 'q' is validated by Malli coercion.
   Uses D1 database to find matching places and their BOM forecast locations."
  [{:keys [parameters env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-places db q)
        (.then (fn [places]
                 (if (seq places)
                   (let [best-match (first places)]
                     (-> (bom/fetch-forecast-by-aac (:state best-match) (:bom_aac best-match))
                         (.then (fn [forecast]
                                  (ring/json-response
                                   {:query q
                                    :place {:name (:name best-match)
                                            :type (:type best-match)
                                            :state (:state best-match)
                                            :lat (:lat best-match)
                                            :lon (:lon best-match)}
                                    :forecast forecast
                                    :other_matches (mapv #(select-keys % [:name :type :state])
                                                         (rest places))})))))
                   (ring/json-response {:query q
                                        :error "No matching places found"
                                        :places []})))))))

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
  #js {:fetch (fn [request env _ctx]
                (handle-request request env))})
