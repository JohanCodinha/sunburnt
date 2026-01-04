(ns worker.views
  "Server-side rendered HTML views for the weather app."
  (:require [replicant.string :as r]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Icon Mapping

(def ^:private icon-map
  {"sunny"         "☀️"
   "clear"         "🌙"
   "partly-cloudy" "⛅"
   "cloudy"        "☁️"
   "hazy"          "🌫️"
   "light-rain"    "🌦️"
   "windy"         "💨"
   "fog"           "🌫️"
   "showers"       "🌧️"
   "rain"          "🌧️"
   "dusty"         "💨"
   "frost"         "❄️"
   "snow"          "🌨️"
   "storm"         "⛈️"
   "light-showers" "🌦️"
   "heavy-showers" "🌧️"
   "cyclone"       "🌀"})

;; -----------------------------------------------------------------------------
;; Date Formatting

(defn- format-day
  "Format a date string to display day name"
  [date-str]
  (let [date (js/Date. date-str)
        today (js/Date.)
        tomorrow (js/Date. (.getTime today))]
    (.setDate tomorrow (inc (.getDate tomorrow)))
    (cond
      (= (.toDateString date) (.toDateString today)) "Today"
      (= (.toDateString date) (.toDateString tomorrow)) "Tomorrow"
      :else (.toLocaleDateString date "en-AU" #js {:weekday "short" :day "numeric"}))))

;; -----------------------------------------------------------------------------
;; Temperature Graph

(defn- parse-iso-time
  "Parse ISO 8601 time string to epoch milliseconds"
  [time-str]
  (when time-str
    (.getTime (js/Date. time-str))))

(defn- format-time-label
  "Format timestamp to short time label (e.g., '2pm', '11am')"
  [timestamp]
  (let [date (js/Date. timestamp)
        hours (.getHours date)]
    (cond
      (= hours 0) "12am"
      (= hours 12) "12pm"
      (< hours 12) (str hours "am")
      :else (str (- hours 12) "pm"))))

(defn- format-date-label
  "Format timestamp to short date label (e.g., 'Mon 2')"
  [timestamp]
  (let [date (js/Date. timestamp)]
    (.toLocaleDateString date "en-AU" #js {:weekday "short" :day "numeric"})))

(defn- catmull-rom-to-bezier
  "Convert Catmull-Rom control points to cubic bezier control points.
   Takes 4 points: p0, p1, p2, p3 and returns bezier control points for p1->p2 segment."
  [[x0 y0] [x1 y1] [x2 y2] [x3 y3]]
  (let [tension (/ 1 6)]
    {:c1x (+ x1 (* tension (- x2 x0)))
     :c1y (+ y1 (* tension (- y2 y0)))
     :c2x (- x2 (* tension (- x3 x1)))
     :c2y (- y2 (* tension (- y3 y1)))
     :x x2
     :y y2}))

(defn- build-smooth-path
  "Build SVG path string with smooth curves through all points using Catmull-Rom spline."
  [points]
  (when (>= (count points) 2)
    (let [pts (vec points)
          ;; Extend endpoints for smooth curve at edges
          first-pt (first pts)
          last-pt (last pts)
          extended (vec (concat [first-pt] pts [last-pt]))
          n (count extended)
          ;; Start path at first real point
          [start-x start-y] (nth extended 1)
          segments (for [i (range 1 (- n 2))]
                     (catmull-rom-to-bezier
                      (nth extended (dec i))
                      (nth extended i)
                      (nth extended (inc i))
                      (nth extended (min (+ i 2) (dec n)))))]
      (str "M " start-x " " start-y " "
           (str/join " "
                     (for [{:keys [c1x c1y c2x c2y x y]} segments]
                       (str "C " c1x " " c1y " " c2x " " c2y " " x " " y)))))))

(defn temperature-graph
  "Render SVG temperature graph from observation history.
   history is a vector of observations (newest first) with :observation_time and :temp_c"
  [history station-name]
  (let [;; Filter observations with valid temperature and time, reverse to chronological order
        valid-obs (->> history
                       (filter #(and (:temp_c %) (:observation_time %)))
                       reverse
                       vec)]
    (when (>= (count valid-obs) 2)
      (let [;; Parse times and calculate bounds
            data-points (mapv (fn [obs]
                                {:time (parse-iso-time (:observation_time obs))
                                 :temp (:temp_c obs)})
                              valid-obs)
            times (mapv :time data-points)
            temps (mapv :temp data-points)
            min-time (apply min times)
            max-time (apply max times)
            min-temp (apply min temps)
            max-temp (apply max temps)
            ;; Add padding to temp range
            temp-range (- max-temp min-temp)
            temp-padding (max 2 (* temp-range 0.15))
            y-min (- min-temp temp-padding)
            y-max (+ max-temp temp-padding)
            ;; SVG dimensions
            width 560
            height 180
            padding-left 45
            padding-right 15
            padding-top 25
            padding-bottom 35
            graph-width (- width padding-left padding-right)
            graph-height (- height padding-top padding-bottom)
            ;; Scale functions
            time-range (- max-time min-time)
            scale-x (fn [t] (+ padding-left (* graph-width (/ (- t min-time) time-range))))
            scale-y (fn [temp] (+ padding-top (* graph-height (/ (- y-max temp) (- y-max y-min)))))
            ;; Generate points for path
            path-points (mapv (fn [{:keys [time temp]}]
                                [(scale-x time) (scale-y temp)])
                              data-points)
            ;; Generate time labels (every 6 hours)
            hour-ms (* 1000 60 60)
            label-interval (* 6 hour-ms)
            first-label-time (-> min-time (/ label-interval) js/Math.ceil (* label-interval))
            time-labels (loop [t first-label-time labels []]
                          (if (> t max-time)
                            labels
                            (recur (+ t label-interval)
                                   (conj labels {:time t :x (scale-x t) :label (format-time-label t)}))))
            ;; Generate temp labels
            temp-step (cond (> (- y-max y-min) 20) 5 (> (- y-max y-min) 10) 2 :else 1)
            first-temp-label (-> y-min (/ temp-step) js/Math.ceil (* temp-step))
            temp-labels (loop [temp first-temp-label labels []]
                          (if (> temp y-max)
                            labels
                            (recur (+ temp temp-step)
                                   (conj labels {:temp temp :y (scale-y temp)}))))
            ;; Date labels
            day-ms (* 24 hour-ms)
            first-midnight (let [d (js/Date. min-time)]
                             (.setHours d 0 0 0 0)
                             (+ (.getTime d) day-ms))
            date-labels (loop [t first-midnight labels []]
                          (if (> t max-time)
                            labels
                            (recur (+ t day-ms)
                                   (conj labels {:time t :x (scale-x t) :label (format-date-label t)}))))]
        [:div.temperature-graph
         [:h4 (str "Temperature — " (or station-name "Weather Station"))]
         [:svg {:viewBox (str "0 0 " width " " height) :class "temp-chart"}
          [:g.grid-lines
           (for [{:keys [y]} temp-labels]
             [:line {:x1 padding-left :y1 y :x2 (- width padding-right) :y2 y
                     :stroke "#e0e0e0" :stroke-width 1}])]
          [:path {:d (build-smooth-path path-points)
                  :fill "none" :stroke "#ff9800" :stroke-width 2.5
                  :stroke-linecap "round" :stroke-linejoin "round"}]
          [:g.data-points
           (for [[x y] path-points]
             [:circle {:cx x :cy y :r 3 :fill "#ff9800" :stroke "white" :stroke-width 1.5}])]
          [:g.y-axis
           (for [{:keys [temp y]} temp-labels]
             [:text {:x (- padding-left 8) :y (+ y 4) :text-anchor "end" :font-size 11 :fill "#666"}
              (str temp "°")])]
          [:g.x-axis
           (for [{:keys [x label]} time-labels]
             [:text {:x x :y (- height 18) :text-anchor "middle" :font-size 10 :fill "#888"} label])]
          [:g.date-labels
           (for [{:keys [x label]} date-labels]
             [:g
              [:line {:x1 x :y1 padding-top :x2 x :y2 (- height padding-bottom)
                      :stroke "#ccc" :stroke-width 1 :stroke-dasharray "3,3"}]
              [:text {:x x :y (- height 5) :text-anchor "middle" :font-size 10 :fill "#666" :font-weight 500}
               label]])]]]))))
;; -----------------------------------------------------------------------------
;; Components

(defn layout
  "Base HTML layout with CSS link and optional client JS"
  [{:keys [title include-client-js?]} & body]
  (str "<!DOCTYPE html>"
       (r/render
        [:html {:lang "en"}
         [:head
          [:meta {:charset "UTF-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/styles.css"}]]
         [:body
          [:div.container body]
          (when include-client-js?
            [:script {:src "/js/client.js" :defer true}])]])))

(defn- detail-with-source
  "Render a detail value with inline source attribution.
   Only shows source if it differs from primary station."
  [label value unit source primary-station]
  [:div.detail
   [:div.detail-label label]
   [:div.detail-value (str value unit)]
   (when (and source (not= (:station source) primary-station))
     [:div.detail-source (str "from " (:station source))])])

(defn current-conditions
  "Render current observation data with source attribution.
   Supports both old format (single station) and new format (multi-station with sources)."
  [{:keys [station primary_station temp_c feels_like_c humidity wind_dir wind_speed_kmh
           gust_speed_kmh rain_24hr_mm sources history]}]
  (let [main-station (or primary_station station)
        temp-station (get-in sources [:temp_c :station])]
    [:div.current-conditions
     [:h3 (str "Current Conditions" (when main-station (str " — " main-station)))]
     [:div.current-temps
      [:span.temp-main (if (some? temp_c) (str temp_c "°") "--")]
      (when (some? feels_like_c)
        [:span.feels-like (str "Feels like " feels_like_c "°")])]
     [:div.current-details
      (when (some? humidity)
        (detail-with-source "Humidity" humidity "%" (get sources :humidity) main-station))
      (when (some? wind_speed_kmh)
        (detail-with-source "Wind" (str (or wind_dir "") " " wind_speed_kmh) " km/h"
                            (get sources :wind_speed_kmh) main-station))
      (when (some? gust_speed_kmh)
        (detail-with-source "Gusts" gust_speed_kmh " km/h"
                            (get sources :gust_speed_kmh) main-station))
      (when (some? rain_24hr_mm)
        (detail-with-source "Rain (24h)" rain_24hr_mm " mm"
                            (get sources :rain_24hr_mm) main-station))]
     (temperature-graph history (or temp-station main-station))]))

(defn forecast-card
  "Render a single forecast period"
  [{:keys [start_time forecast icon min_temp max_temp rain_chance]}]
  [:div.forecast-card
   [:div.day (format-day start_time)]
   [:div.icon (get icon-map icon "🌡️")]
   [:div.conditions (or forecast "")]
   [:div.temps
    (when (some? min_temp)
      [:span.temp.min (str min_temp "°")])
    (when (some? max_temp)
      [:span.temp.max (str max_temp "°")])
    (when rain_chance
      [:span.temp.rain rain_chance])]])

(defn forecast-section
  "Render 7-day forecast"
  [{:keys [periods]}]
  (when (seq periods)
    [:div.forecast-section
     [:h3 "7-Day Forecast"]
     (map forecast-card periods)]))

(defn other-matches-section
  "Render links to other matching locations"
  [matches]
  (when (seq matches)
    [:div.other-matches
     [:h4 "Other matches:"]
     (for [{:keys [name slug state]} matches]
       [:a {:href (str "/forecast/" slug)} (str name ", " state)])]))

(defn home-page
  "Home page with search autocomplete"
  [{:keys [examples]}]
  (layout
   {:title "Australian Weather Forecast"
    :include-client-js? true}
   [:h1 "Australian Weather Forecast"]
   [:p.subtitle "Search for any Australian city or town"]
   [:div.search-box
    [:input#search {:type "text"
                    :placeholder "Enter city name..."
                    :autocomplete "off"}]
    [:div#suggestions.suggestions]]
   [:div.examples
    "Try: "
    (for [{:keys [name slug]} examples]
      [:a {:href (str "/forecast/" slug)} name])]
   [:div.stations-link
    [:a {:href "/stations"} "Browse all weather stations →"]]
   [:div.tech-stack
    "Built with ClojureScript + shadow-cljs + Reitit + Cloudflare Workers"
    [:br]
    "Data from Bureau of Meteorology"]))

(defn forecast-page
  "Forecast page for a specific location"
  [{:keys [place observation forecast other-matches]}]
  (layout
   {:title (str (:name place) " Weather Forecast")}
   [:a.back-link {:href "/"} "← Back to search"]
   [:div.location-header
    [:h2 (:name place)]
    [:div.state (str (when (:type place) (str (:type place) ", ")) (:state place))]]
   (when observation
     (current-conditions observation))
   (when forecast
     (forecast-section forecast))
   (other-matches-section other-matches)
   [:div.tech-stack
    "Built with ClojureScript + shadow-cljs + Reitit + Cloudflare Workers"
    [:br]
    "Data from Bureau of Meteorology"]))

(defn not-found-page
  "404 page for unknown locations"
  [slug]
  (layout
   {:title "Location Not Found"}
   [:div.not-found
    [:h2 "Location Not Found"]
    [:p (str "We couldn't find a location matching \"" slug "\".")]
    [:a.back-link {:href "/"} "← Back to search"]]))

(defn stations-list-page
  "Stations list page with map and search autocomplete"
  [_opts]
  (str "<!DOCTYPE html>"
       (r/render
        [:html {:lang "en"}
         [:head
          [:meta {:charset "UTF-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title "Weather Stations"]
          [:link {:rel "stylesheet" :href "/styles.css"}]
          [:link {:rel "stylesheet" :href "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css"}]
          [:script {:src "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"}]]
         [:body
          [:div.container
           [:a.back-link {:href "/"} "← Back to search"]
           [:h1 "Weather Stations"]
           [:p.subtitle "Click a station on the map or search below"]
           [:div#map.stations-map]
           [:div.search-box
            [:input#station-search {:type "text"
                                    :placeholder "Search stations..."
                                    :autocomplete "off"}]
            [:div#station-suggestions.suggestions]]
           [:div.tech-stack
            "Built with ClojureScript + shadow-cljs + Reitit + Cloudflare Workers"
            [:br]
            "Data from Bureau of Meteorology"]]
          [:script {:src "/js/client.js" :defer true}]]])))

(defn- station-conditions
  "Render current observation data for a single station"
  [{:keys [station temp_c feels_like_c humidity wind_dir wind_speed_kmh
           gust_speed_kmh rain_24hr_mm history]}]
  [:div.current-conditions
   [:h3 (str "Current Conditions — " (or station "Weather Station"))]
   [:div.current-temps
    [:span.temp-main (if (some? temp_c) (str temp_c "°") "--")]
    (when (some? feels_like_c)
      [:span.feels-like (str "Feels like " feels_like_c "°")])]
   [:div.current-details
    (when (some? humidity)
      [:div.detail
       [:div.detail-label "Humidity"]
       [:div.detail-value (str humidity "%")]])
    (when (some? wind_speed_kmh)
      [:div.detail
       [:div.detail-label "Wind"]
       [:div.detail-value (str (or wind_dir "") " " wind_speed_kmh " km/h")]])
    (when (some? gust_speed_kmh)
      [:div.detail
       [:div.detail-label "Gusts"]
       [:div.detail-value (str gust_speed_kmh " km/h")]])
    (when (some? rain_24hr_mm)
      [:div.detail
       [:div.detail-label "Rain (24h)"]
       [:div.detail-value (str rain_24hr_mm " mm")]])]
   (temperature-graph history station)])

(defn station-page
  "Station detail page with current conditions and temperature graph"
  [{:keys [station observation]}]
  (layout
   {:title (str (:obs_name station) " Weather Station")}
   [:a.back-link {:href "/stations"} "← Back to stations"]
   [:div.location-header
    [:h2 (:obs_name station)]
    [:div.state (:state station)]
    (when (and (:obs_lat station) (:obs_lon station))
      [:div.coordinates
       (str "Location: " (.toFixed (:obs_lat station) 4) ", " (.toFixed (:obs_lon station) 4))])]
   (if observation
     (station-conditions observation)
     [:div.no-data "No observation data currently available for this station."])
   [:div.tech-stack
    "Built with ClojureScript + shadow-cljs + Reitit + Cloudflare Workers"
    [:br]
    "Data from Bureau of Meteorology"]))

(defn error-page
  "Error page"
  [message]
  (layout
   {:title "Error"}
   [:a.back-link {:href "/"} "← Back to search"]
   [:div.error message]))
