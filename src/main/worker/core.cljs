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
   Returns top matches ordered by importance, including observation station info."
  [^js db query]
  (-> (.prepare db "SELECT p.name, p.type, p.state, p.lat, p.lon, p.bom_aac, p.importance,
                           b.obs_wmo, b.obs_name
                    FROM places p
                    LEFT JOIN bom_locations b ON p.bom_aac = b.aac
                    WHERE p.name LIKE ?
                    ORDER BY p.importance DESC, p.name ASC
                    LIMIT 10")
      (.bind (str "%" query "%"))
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))

(defn- forecast-handler
  "Returns forecast data for matching locations.
   Query param 'q' is validated by Malli coercion.
   Uses D1 database to find matching places and their BOM forecast locations.
   Fetches both forecast and live observation data in parallel."
  [{:keys [parameters ^js env]}]
  (let [{:keys [q]} (:query parameters)
        db (.-PLACES_DB env)]
    (-> (search-places db q)
        (.then (fn [places]
                 (if (seq places)
                   (let [best-match (first places)
                         state (:state best-match)
                         ;; Fetch forecast and observation in parallel
                         forecast-promise (bom/fetch-forecast-by-aac state (:bom_aac best-match))
                         obs-promise (if-let [wmo (:obs_wmo best-match)]
                                       (bom/fetch-observation-by-wmo state wmo)
                                       (js/Promise.resolve nil))]
                     (-> (js/Promise.all #js [forecast-promise obs-promise])
                         (.then (fn [results]
                                  (let [[forecast observation] (js->clj results :keywordize-keys true)]
                                    (ring/json-response
                                     {:query q
                                      :place {:name (:name best-match)
                                              :type (:type best-match)
                                              :state (:state best-match)
                                              :lat (:lat best-match)
                                              :lon (:lon best-match)}
                                      :observation observation
                                      :forecast forecast
                                      :other_matches (mapv #(select-keys % [:name :type :state])
                                                           (rest places))}))))))
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
