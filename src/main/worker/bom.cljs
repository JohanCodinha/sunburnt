(ns worker.bom
  "Bureau of Meteorology forecast data fetching and parsing.
   Uses streaming SAX parsing for memory efficiency."
  (:require [clojure.string :as str]
            ["saxes" :as saxes]))

;; BOM forecast feed URLs - one key feed per state
(def feed-ids
  ["IDV10753"   ; Victoria
   "IDN11060"   ; New South Wales
   "IDQ10605"   ; Queensland
   "IDS10044"   ; South Australia
   "IDW14199"   ; Western Australia
   "IDT16000"   ; Tasmania
   "IDD10207"]) ; Northern Territory

;; State code to feed ID mapping
(def state->feed-id
  {"VIC" "IDV10753"
   "NSW" "IDN11060"
   "ACT" "IDN11060"  ; ACT uses NSW feed
   "QLD" "IDQ10605"
   "SA"  "IDS10044"
   "WA"  "IDW14199"
   "TAS" "IDT16000"
   "NT"  "IDD10207"})

;; BOM forecast icon code to name mapping
;; Source: https://reg.bom.gov.au/info/forecast_icons.shtml
(def icon-code->name
  {1  "sunny"
   2  "clear"
   3  "partly-cloudy"
   4  "cloudy"
   6  "hazy"
   8  "light-rain"
   9  "windy"
   10 "fog"
   11 "showers"
   12 "rain"
   13 "dusty"
   14 "frost"
   15 "snow"
   16 "storm"
   17 "light-showers"
   18 "heavy-showers"
   19 "cyclone"})

(defn feed-url
  "Generate BOM feed URL for a product ID."
  [product-id]
  (str "http://reg.bom.gov.au/fwo/" product-id ".xml"))

(defn- matches-query?
  "Check if location name matches query (case-insensitive)."
  [location-name query]
  (and location-name
       (str/includes? (str/lower-case location-name) query)))

(defn- create-sax-parser
  "Create a SAX parser that extracts matching forecast locations.
   Returns {:parser parser :get-results fn :get-errors fn :is-done fn}."
  [query max-results]
  (let [parser (saxes/SaxesParser.)
        query-lower (str/lower-case (str/trim (str query)))
        ;; Use JS arrays/objects with explicit string keys to avoid property name munging
        results #js []
        errors #js []
        state #js {"inArea" false
                   "areaType" nil
                   "areaName" nil
                   "inForecastPeriod" false
                   "firstPeriod" true
                   "currentElement" nil
                   "forecast" nil
                   "minTemp" nil
                   "maxTemp" nil
                   "rainChance" nil
                   "done" false}]

    ;; Error handler
    (set! (.-errorHandler parser)
          (fn [err]
            (.push errors (str err))))

    ;; Element open handler
    (set! (.-openTagHandler parser)
          (fn [tag]
            (when-not (aget state "done")
              (let [tag-name (.-name tag)
                    attrs (.-attributes tag)]
                (cond
                  ;; Starting an area element
                  (= tag-name "area")
                  (let [area-type (aget attrs "type")]
                    (when (or (= area-type "location") (= area-type "metropolitan"))
                      (aset state "inArea" true)
                      (aset state "areaType" area-type)
                      (aset state "areaName" (aget attrs "description"))
                      (aset state "firstPeriod" true)
                      (aset state "forecast" nil)
                      (aset state "minTemp" nil)
                      (aset state "maxTemp" nil)
                      (aset state "rainChance" nil)))

                  ;; Starting a forecast-period (only care about first one)
                  (and (= tag-name "forecast-period") (aget state "inArea"))
                  (when (aget state "firstPeriod")
                    (aset state "inForecastPeriod" true))

                  ;; Starting a text or element tag inside forecast-period
                  (and (aget state "inForecastPeriod")
                       (or (= tag-name "text") (= tag-name "element")))
                  (aset state "currentElement" (aget attrs "type")))))))

    ;; Text content handler
    (set! (.-textHandler parser)
          (fn [text]
            (when-not (aget state "done")
              (when-let [elem-type (aget state "currentElement")]
                (let [text-val (str/trim text)]
                  (when (and (seq text-val) (aget state "inForecastPeriod"))
                    (case elem-type
                      "precis" (when-not (aget state "forecast")
                                 (aset state "forecast" text-val))
                      "forecast" (when-not (aget state "forecast")
                                   (aset state "forecast" text-val))
                      "air_temperature_minimum" (aset state "minTemp" (js/parseInt text-val 10))
                      "air_temperature_maximum" (aset state "maxTemp" (js/parseInt text-val 10))
                      "probability_of_precipitation" (aset state "rainChance" text-val)
                      nil)))))))

    ;; Element close handler
    (set! (.-closeTagHandler parser)
          (fn [tag]
            (when-not (aget state "done")
              (let [tag-name (.-name tag)]
                (cond
                  ;; Closing text/element tag
                  (or (= tag-name "text") (= tag-name "element"))
                  (aset state "currentElement" nil)

                  ;; Closing forecast-period
                  (= tag-name "forecast-period")
                  (when (aget state "inForecastPeriod")
                    (aset state "inForecastPeriod" false)
                    (aset state "firstPeriod" false))

                  ;; Closing area - emit result if it matches
                  (= tag-name "area")
                  (when (aget state "inArea")
                    (let [area-name (aget state "areaName")]
                      (when (matches-query? area-name query-lower)
                        (.push results #js {:location area-name
                                            :forecast (or (aget state "forecast") "No forecast available")
                                            :min_temp (aget state "minTemp")
                                            :max_temp (aget state "maxTemp")
                                            :rain_chance (or (aget state "rainChance") "N/A")})
                        ;; Check if we have enough results
                        (when (>= (.-length results) max-results)
                          (aset state "done" true))))
                    (aset state "inArea" false)))))))

    {:parser parser
     :get-results #(js->clj results :keywordize-keys true)
     :get-errors #(js->clj errors)
     :is-done #(aget state "done")}))

(defn- stream-parse-xml
  "Stream parse XML response using SAX, extracting matching locations.
   Returns a promise that resolves with {:results [...] :errors [...]}."
  [response query max-results]
  (let [reader (.getReader (.-body response))
        decoder (js/TextDecoder.)
        {:keys [parser get-results get-errors is-done]} (create-sax-parser query max-results)]

    (js/Promise.
     (fn [resolve _reject]
       (letfn [(read-chunk []
                 (-> (.read reader)
                     (.then (fn [result]
                              (if (or (.-done result) (is-done))
                                (do
                                  (.cancel reader)
                                  (.close parser)
                                  (resolve {:results (get-results) :errors (get-errors)}))
                                (do
                                  (.write parser (.decode decoder (.-value result)))
                                  (if (is-done)
                                    (do
                                      (.cancel reader)
                                      (.close parser)
                                      (resolve {:results (get-results) :errors (get-errors)}))
                                    (read-chunk))))))
                     (.catch (fn [_]
                               (.close parser)
                               (resolve {:results (get-results) :errors (get-errors)})))))]
         (read-chunk))))))

(defn- fetch-feed
  "Fetch a single feed with streaming SAX parse, return matching locations."
  [feed-id query max-results]
  (-> (js/fetch (feed-url feed-id))
      (.then (fn [response]
               (if (.-ok response)
                 (-> (stream-parse-xml response query max-results)
                     (.then (fn [data] (:results data))))
                 (js/Promise.resolve []))))
      (.catch (fn [_] (js/Promise.resolve [])))))

(defn fetch-forecast
  "Fetch forecasts from feeds sequentially, stopping when we have enough results.
   Memory efficient: only loads one feed at a time with streaming SAX parsing.
   Returns a promise that resolves with a vector of forecast results."
  [query]
  (let [max-results 10
        results (atom [])
        feeds-to-try (atom (vec feed-ids))]

    (js/Promise.
     (fn [resolve _reject]
       (letfn [(try-next-feed []
                 (if (or (empty? @feeds-to-try) (>= (count @results) max-results))
                   (resolve (vec (distinct (take max-results @results))))
                   (let [feed (first @feeds-to-try)]
                     (swap! feeds-to-try rest)
                     (-> (fetch-feed feed query (- max-results (count @results)))
                         (.then (fn [matches]
                                  (swap! results into matches)
                                  (try-next-feed)))))))]
         (try-next-feed))))))

;; -----------------------------------------------------------------------------
;; AAC-based forecast fetching (optimized path using D1 database)

(defn- create-aac-sax-parser
  "Create a SAX parser that extracts forecast for a specific AAC code.
   Returns all forecast periods (7 days) with dates and metadata.
   Returns {:parser parser :get-result fn :get-errors fn :is-done fn}."
  [target-aac]
  (let [parser (saxes/SaxesParser.)
        result (atom nil)
        periods #js []
        errors #js []
        state #js {"inArea" false
                   "areaAac" nil
                   "areaName" nil
                   "inForecastPeriod" false
                   "periodIndex" nil
                   "periodStart" nil
                   "periodEnd" nil
                   "currentElement" nil
                   "forecast" nil
                   "iconCode" nil
                   "minTemp" nil
                   "maxTemp" nil
                   "rainChance" nil
                   "done" false}]

    (set! (.-errorHandler parser)
          (fn [err]
            (.push errors (str err))))

    (set! (.-openTagHandler parser)
          (fn [tag]
            (when-not (aget state "done")
              (let [tag-name (.-name tag)
                    attrs (.-attributes tag)]
                (cond
                  (= tag-name "area")
                  (let [area-type (aget attrs "type")
                        aac (aget attrs "aac")]
                    (when (and (or (= area-type "location") (= area-type "metropolitan"))
                               (= aac target-aac))
                      (aset state "inArea" true)
                      (aset state "areaAac" aac)
                      (aset state "areaName" (aget attrs "description"))))

                  (and (= tag-name "forecast-period") (aget state "inArea"))
                  (do
                    (aset state "inForecastPeriod" true)
                    (aset state "periodIndex" (aget attrs "index"))
                    (aset state "periodStart" (aget attrs "start-time-local"))
                    (aset state "periodEnd" (aget attrs "end-time-local"))
                    (aset state "forecast" nil)
                    (aset state "iconCode" nil)
                    (aset state "minTemp" nil)
                    (aset state "maxTemp" nil)
                    (aset state "rainChance" nil))

                  (and (aget state "inForecastPeriod")
                       (or (= tag-name "text") (= tag-name "element")))
                  (aset state "currentElement" (aget attrs "type")))))))

    (set! (.-textHandler parser)
          (fn [text]
            (when-not (aget state "done")
              (when-let [elem-type (aget state "currentElement")]
                (let [text-val (str/trim text)]
                  (when (and (seq text-val) (aget state "inForecastPeriod"))
                    (case elem-type
                      "precis" (when-not (aget state "forecast")
                                 (aset state "forecast" text-val))
                      "forecast" (when-not (aget state "forecast")
                                   (aset state "forecast" text-val))
                      "forecast_icon_code" (aset state "iconCode" (js/parseInt text-val 10))
                      "air_temperature_minimum" (aset state "minTemp" (js/parseInt text-val 10))
                      "air_temperature_maximum" (aset state "maxTemp" (js/parseInt text-val 10))
                      "probability_of_precipitation" (aset state "rainChance" text-val)
                      nil)))))))

    (set! (.-closeTagHandler parser)
          (fn [tag]
            (when-not (aget state "done")
              (let [tag-name (.-name tag)]
                (cond
                  (or (= tag-name "text") (= tag-name "element"))
                  (aset state "currentElement" nil)

                  (= tag-name "forecast-period")
                  (when (aget state "inForecastPeriod")
                    (let [icon-code (aget state "iconCode")
                          icon-name (get icon-code->name icon-code "unknown")]
                      (.push periods #js {:start_time (aget state "periodStart")
                                          :end_time (aget state "periodEnd")
                                          :forecast (or (aget state "forecast") "No forecast available")
                                          :icon icon-name
                                          :min_temp (aget state "minTemp")
                                          :max_temp (aget state "maxTemp")
                                          :rain_chance (or (aget state "rainChance") "N/A")}))
                    (aset state "inForecastPeriod" false))

                  (= tag-name "area")
                  (when (aget state "inArea")
                    (reset! result {:location (aget state "areaName")
                                    :aac (aget state "areaAac")
                                    :periods (js->clj periods :keywordize-keys true)})
                    (aset state "done" true)
                    (aset state "inArea" false)))))))

    {:parser parser
     :get-result #(deref result)
     :get-errors #(js->clj errors)
     :is-done #(aget state "done")}))

(defn- stream-parse-xml-by-aac
  "Stream parse XML response using SAX, extracting forecast for specific AAC.
   Returns a promise that resolves with {:result {...} :errors [...]}."
  [response target-aac]
  (let [reader (.getReader (.-body response))
        decoder (js/TextDecoder.)
        {:keys [parser get-result get-errors is-done]} (create-aac-sax-parser target-aac)]

    (js/Promise.
     (fn [resolve _reject]
       (letfn [(read-chunk []
                 (-> (.read reader)
                     (.then (fn [result]
                              (if (or (.-done result) (is-done))
                                (do
                                  (.cancel reader)
                                  (.close parser)
                                  (resolve {:result (get-result) :errors (get-errors)}))
                                (do
                                  (.write parser (.decode decoder (.-value result)))
                                  (if (is-done)
                                    (do
                                      (.cancel reader)
                                      (.close parser)
                                      (resolve {:result (get-result) :errors (get-errors)}))
                                    (read-chunk))))))
                     (.catch (fn [_]
                               (.close parser)
                               (resolve {:result (get-result) :errors (get-errors)})))))]
         (read-chunk))))))

(defn fetch-forecast-by-aac
  "Fetch forecast for a specific BOM AAC code from the appropriate state feed.
   Returns a promise that resolves with the forecast result or nil."
  [state-code aac]
  (if-let [feed-id (get state->feed-id state-code)]
    (-> (js/fetch (feed-url feed-id))
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (stream-parse-xml-by-aac response aac)
                       (.then (fn [data] (:result data))))
                   (js/Promise.resolve nil))))
        (.catch (fn [_] (js/Promise.resolve nil))))
    (js/Promise.resolve nil)))
