(ns worker.bom
  "Bureau of Meteorology forecast data fetching and parsing.
   Uses streaming SAX parsing for memory efficiency."
  (:require [clojure.string :as str]
            ["saxes" :as saxes]))

;; State code to feed ID mapping (forecasts)
(def state->feed-id
  {"VIC" "IDV10753"
   "NSW" "IDN11060"
   "ACT" "IDN11060"  ; ACT uses NSW feed
   "QLD" "IDQ11295"  ; IDQ10605 only has ME/FA codes, IDQ11295 has PT codes
   "SA"  "IDS10044"
   "WA"  "IDW14199"
   "TAS" "IDT16000"
   "NT"  "IDD10207"})

;; State code to observation feed ID mapping (JSON feeds with 72hr history)
(def state->obs-feed-id
  {"VIC" "IDV60910"
   "NSW" "IDN60910"
   "ACT" "IDN60910"  ; ACT uses NSW feed
   "QLD" "IDQ60910"
   "SA"  "IDS60910"
   "WA"  "IDW60910"
   "TAS" "IDT60910"
   "NT"  "IDD60910"})

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

(defn- feed-url
  "Generate BOM feed URL for a product ID."
  [product-id]
  (str "http://reg.bom.gov.au/fwo/" product-id ".xml"))

;; -----------------------------------------------------------------------------
;; AAC-based forecast fetching (using D1 database for location lookup)

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

;; -----------------------------------------------------------------------------
;; Live observation fetching (JSON feeds with 72hr history)

(defn- obs-json-url
  "Generate BOM observation JSON URL for a specific station.
   Format: http://reg.bom.gov.au/fwo/{FEED_ID}/{FEED_ID}.{WMO}.json"
  [feed-id wmo-id]
  (str "http://reg.bom.gov.au/fwo/" feed-id "/" feed-id "." wmo-id ".json"))

(defn- format-obs-time
  "Convert BOM time format (YYYYMMDDHHmmss) to ISO 8601 with timezone."
  [time-str tz-offset]
  (when (and time-str (>= (count time-str) 14))
    (str (subs time-str 0 4) "-"
         (subs time-str 4 6) "-"
         (subs time-str 6 8) "T"
         (subs time-str 8 10) ":"
         (subs time-str 10 12) ":"
         (subs time-str 12 14)
         (or tz-offset "+00:00"))))

(defn- transform-observation
  "Transform a BOM JSON observation entry to our format.
   Takes a JS object and returns a Clojure map."
  [obs]
  (let [tz-offset (aget obs "TDZ")]
    {:station (aget obs "name")
     :observation_time (format-obs-time (aget obs "aifstime_local") tz-offset)
     :temp_c (aget obs "air_temp")
     :feels_like_c (aget obs "apparent_t")
     :humidity (aget obs "rel_hum")
     :wind_dir (aget obs "wind_dir")
     :wind_speed_kmh (aget obs "wind_spd_kmh")
     :gust_speed_kmh (aget obs "gust_kmh")
     :rain_last_hour_mm (aget obs "rain_hour")
     :rain_24hr_mm (let [v (aget obs "rainfall_24hr")]
                     (if (nil? v)
                       (let [trace (aget obs "rain_trace")]
                         (when (and trace (not= trace "-"))
                           (js/parseFloat trace)))
                       v))
     :cloud (let [c (aget obs "cloud")] (when (not= c "-") c))}))

(defn fetch-observation-by-wmo
  "Fetch observations for a specific WMO station ID.
   Returns a promise that resolves with the current observation or nil.
   The JSON feed contains 72hrs of history at 30-min intervals."
  [state-code wmo-id]
  (if-let [feed-id (get state->obs-feed-id state-code)]
    (-> (js/fetch (obs-json-url feed-id wmo-id))
        (.then (fn [response]
                 (if (.-ok response)
                   (.json response)
                   (js/Promise.resolve nil))))
        (.then (fn [json]
                 (when json
                   (when-let [data (some-> json
                                           (aget "observations")
                                           (aget "data"))]
                     (when (pos? (.-length data))
                       (transform-observation (aget data 0)))))))
        (.catch (fn [_] (js/Promise.resolve nil))))
    (js/Promise.resolve nil)))

;; -----------------------------------------------------------------------------
;; Multi-station observation fetching (for SSR pages)

(def ^:private target-fields
  "Fields we want to collect from observation stations."
  [:temp_c :feels_like_c :humidity :wind_speed_kmh :gust_speed_kmh :rain_24hr_mm])

(defn- merge-observations
  "Merge observations from multiple stations.
   For each field, use the first non-nil value (stations ordered by distance).
   Track which station provided each value in :sources."
  [stations observations]
  (let [station-obs-pairs (map vector stations observations)
        ;; For each target field, find first station with non-nil value
        field-results (for [field target-fields]
                        (let [match (first (filter (fn [[_ obs]]
                                                     (and obs (some? (get obs field))))
                                                   station-obs-pairs))]
                          (when match
                            (let [[station obs] match]
                              [field {:value (get obs field)
                                      :station (:obs_name station)
                                      :distance_km (:distance_km station)}]))))
        field-map (into {} (filter some? field-results))
        ;; Build response with values and sources
        values (into {} (for [[k v] field-map] [k (:value v)]))
        sources (into {} (for [[k v] field-map]
                           [k {:station (:station v)
                               :distance_km (:distance_km v)}]))
        ;; Get observation time and wind direction from first available observation
        first-obs (first (filter some? observations))
        first-station (first (filter (fn [[_ obs]] (some? obs)) station-obs-pairs))]
    (when (seq values)
      (assoc values
             :sources sources
             :primary_station (when first-station (:obs_name (first first-station)))
             :observation_time (:observation_time first-obs)
             :wind_dir (:wind_dir first-obs)))))

(defn fetch-observations-multi
  "Fetch observations from multiple stations, merging data.
   Prefers data from closer stations. Tracks source of each field.
   stations is a vector of {:obs_wmo :obs_name :distance_km :rank}"
  [state-code stations]
  (if (empty? stations)
    (js/Promise.resolve nil)
    (let [fetch-promises (mapv #(fetch-observation-by-wmo state-code (:obs_wmo %)) stations)]
      (-> (js/Promise.all (clj->js fetch-promises))
          (.then (fn [results]
                   (let [observations (js->clj results :keywordize-keys true)]
                     (merge-observations stations observations))))))))
