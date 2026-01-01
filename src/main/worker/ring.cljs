(ns worker.ring
  "Ring-like adapter for Cloudflare Workers.

   Converts between Cloudflare's Fetch API (Request/Response objects)
   and Ring-style maps, enabling idiomatic Clojure handlers."
  (:require [clojure.string :as str]
            [reitit.core :as r]
            [reitit.coercion :as coercion]))

;; -----------------------------------------------------------------------------
;; Request Parsing

(defn- parse-query-params
  "Parse URL search params into a Clojure map with keyword keys."
  [url]
  (let [params (.-searchParams url)
        result (atom {})]
    (.forEach params (fn [v k] (swap! result assoc (keyword k) v)))
    @result))

(defn- parse-headers
  "Parse Headers object into a Clojure map with lowercase string keys."
  [headers]
  (let [result (atom {})]
    (.forEach headers (fn [v k] (swap! result assoc (str/lower-case k) v)))
    @result))

(defn- parse-json-body
  "Parse request body as JSON. Returns Promise<map|nil>."
  [request]
  (-> (.json request)
      (.then #(js->clj % :keywordize-keys true))
      (.catch (constantly nil))))

(defn- parse-form-body
  "Parse request body as form data. Returns Promise<map|nil>."
  [request]
  (-> (.formData request)
      (.then (fn [form-data]
               (let [result (atom {})]
                 (.forEach form-data (fn [v k] (swap! result assoc (keyword k) v)))
                 @result)))
      (.catch (constantly nil))))

(defn- parse-body
  "Parse request body based on content-type. Returns Promise<map|nil>.
   Only parses for methods that typically have bodies."
  [request headers body-hint]
  (let [method (.-method request)
        content-type (get headers "content-type" "")]
    (if (and (#{"POST" "PUT" "PATCH"} method)
             body-hint)
      (cond
        (str/includes? content-type "application/json")
        (parse-json-body request)

        (str/includes? content-type "application/x-www-form-urlencoded")
        (parse-form-body request)

        (str/includes? content-type "multipart/form-data")
        (parse-form-body request)

        ;; Default to JSON if body requested but no content-type
        (= body-hint :json)
        (parse-json-body request)

        :else
        (js/Promise.resolve nil))
      (js/Promise.resolve nil))))

(defn ->request-map
  "Convert Cloudflare Request + Reitit match to Ring-like request map.

   Returns a Promise that resolves to:
   {:request-method :get/:post/etc
    :uri            \"/path\"
    :query-string   \"a=1&b=2\" (without leading ?)
    :query-params   {:a \"1\" :b \"2\"}
    :path-params    {:id \"123\"} (from Reitit match)
    :body-params    {...} (parsed JSON/form, when applicable)
    :headers        {\"content-type\" \"...\"}
    :scheme         :https
    :server-name    \"example.com\"
    :server-port    443}

   Body is only parsed if route data contains :body truthy value."
  [request match]
  (let [url (js/URL. (.-url request))
        headers (parse-headers (.-headers request))
        body-hint (get-in match [:data :body])
        query-str (.-search url)
        base {:request-method (keyword (str/lower-case (.-method request)))
              :uri            (.-pathname url)
              :query-string   (when (seq query-str) (subs query-str 1))
              :query-params   (parse-query-params url)
              :path-params    (or (:path-params match) {})
              :headers        headers
              :scheme         (let [proto (.-protocol url)]
                                (keyword (subs proto 0 (dec (count proto)))))
              :server-name    (.-hostname url)
              :server-port    (let [p (.-port url)]
                                (if (seq p)
                                  (js/parseInt p 10)
                                  (if (= "https:" (.-protocol url)) 443 80)))}]
    (-> (parse-body request headers body-hint)
        (.then (fn [body]
                 (if body
                   (assoc base :body-params body)
                   base))))))

;; -----------------------------------------------------------------------------
;; Coercion

(defn- coerce-request
  "Apply Reitit coercion to request parameters.
   Returns {:ok request-map} or {:error exception}.
   Uses pre-compiled coercers from match [:result :coerce]."
  [request-map match]
  (try
    (if-let [coerced (coercion/coerce! match)]
      {:ok (assoc request-map :parameters coerced)}
      {:ok request-map})
    (catch :default e
      {:error e})))

(defn- format-coercion-error
  "Format coercion error for JSON response."
  [error]
  (let [data (ex-data error)
        errors (:errors data)
        param-type (keyword (last (:in data)))]
    {:error "Invalid parameters"
     :in param-type
     :issues (if (sequential? errors)
               (mapv (fn [e]
                       (let [path (:path e)
                             path-str (if (seq path)
                                        (str/join "." (map name path))
                                        "root")]
                         {:field path-str
                          :message "missing required field"}))
                     errors)
               [(ex-message error)])}))

;; -----------------------------------------------------------------------------
;; Response Building

(defn ->response
  "Convert Ring-like response map to Cloudflare Response.

   Response map shape:
   {:status  200              ; HTTP status (default 200)
    :headers {\"x-custom\" \"value\"}  ; Headers map (optional)
    :body    ...}             ; String, map, vector, or nil

   If body is a map or vector, it's JSON-encoded and Content-Type
   is set to application/json (unless already specified)."
  [{:keys [status headers body] :or {status 200 headers {}}}]
  (let [needs-json? (and (some? body)
                         (not (string? body)))
        body-str (cond
                   (nil? body)    ""
                   (string? body) body
                   :else          (.stringify js/JSON (clj->js body)))
        headers* (if (and needs-json?
                          (not (contains? headers "content-type")))
                   (assoc headers "content-type" "application/json")
                   headers)]
    (js/Response. body-str
                  #js {:status status
                       :headers (clj->js headers*)})))

;; -----------------------------------------------------------------------------
;; Convenience Response Helpers

(defn json-response
  "Create a JSON response map."
  ([body] (json-response body 200))
  ([body status]
   {:status status
    :headers {"content-type" "application/json"}
    :body body}))

(defn html-response
  "Create an HTML response map."
  ([body] (html-response body 200))
  ([body status]
   {:status status
    :headers {"content-type" "text/html; charset=utf-8"}
    :body body}))

(defn text-response
  "Create a plain text response map."
  ([body] (text-response body 200))
  ([body status]
   {:status status
    :headers {"content-type" "text/plain"}
    :body body}))

(defn redirect-response
  "Create a redirect response map."
  ([location] (redirect-response location 302))
  ([location status]
   {:status status
    :headers {"location" location}
    :body ""}))

;; -----------------------------------------------------------------------------
;; Handler Wrapper

(defn wrap-handler
  "Wrap a Ring-style handler for use with Cloudflare Workers.

   The wrapped handler receives a Ring-like request map and should return
   either a response map or a Promise that resolves to a response map.

   If the route has coercion configured, parameters are validated and coerced.
   Coerced parameters are available under :parameters key.
   The :env key contains Cloudflare bindings (D1, KV, etc).

   Usage:
     (defn my-handler [{:keys [parameters]}]
       (let [{:keys [q]} (:query parameters)]
         {:status 200 :body {:query q}}))

     ((wrap-handler my-handler) request match)"
  [handler]
  (fn [request match]
    (-> (->request-map request match)
        (.then (fn [request-map]
                 ;; Merge request into match for coerce! (it expects request fields at top level)
                 (let [match-with-request (merge match request-map)
                       {:keys [ok error]} (coerce-request request-map match-with-request)
                       ok-with-env (when ok (assoc ok :env (:env match)))]
                   (if error
                     (js/Promise.resolve
                      {:status 400
                       :body (format-coercion-error error)})
                     (let [result (handler ok-with-env)]
                       (if (instance? js/Promise result)
                         result
                         (js/Promise.resolve result)))))))
        (.then ->response)
        (.catch (fn [err]
                  (js/console.error "Handler error:" err)
                  (->response {:status 500
                               :body {:error "Internal Server Error"
                                      :message (str err)}}))))))

;; -----------------------------------------------------------------------------
;; Router Integration

(defn wrap-routes
  "Create a request handler from a Reitit router.

   Usage:
     (def handler (wrap-routes router))

     ;; In Cloudflare Worker export:
     #js {:fetch (fn [req env ctx] (handler req env))}"
  [router not-found-handler]
  (let [not-found (wrap-handler (or not-found-handler
                                    (constantly {:status 404
                                                 :body "Not Found"})))]
    (fn [request env]
      (let [url (js/URL. (.-url request))
            path (.-pathname url)
            match (r/match-by-path router path)
            match-with-env (when match (assoc match :env env))]
        (if match
          (let [handler (get-in match [:data :handler])]
            ((wrap-handler handler) request match-with-env))
          (not-found request nil))))))
