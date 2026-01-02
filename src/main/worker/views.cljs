(ns worker.views
  "Server-side rendered HTML views for the weather app."
  (:require [worker.hiccup :refer [html]]))

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
;; Components

(defn layout
  "Base HTML layout with CSS link and optional client JS"
  [{:keys [title include-client-js?]} & body]
  (str "<!DOCTYPE html>"
       (html
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
           gust_speed_kmh rain_24hr_mm sources]}]
  (let [main-station (or primary_station station)]
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
                            (get sources :rain_24hr_mm) main-station))]]))

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

(defn error-page
  "Error page"
  [message]
  (layout
   {:title "Error"}
   [:a.back-link {:href "/"} "← Back to search"]
   [:div.error message]))
