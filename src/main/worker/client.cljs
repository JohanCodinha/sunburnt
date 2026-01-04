(ns worker.client
  "Client-side JavaScript for search autocomplete and map.
   Reusable autocomplete component for places and stations.")

(def ^:private debounce-delay 200)
(def ^:private min-query-length 2)

;; State is stored per-autocomplete instance using the input element ID as key
(defonce ^:private states (atom {}))

(defn- get-state [id]
  (get @states id {:timeout-id nil :active-index -1 :items []}))

(defn- set-state! [id key value]
  (swap! states assoc-in [id key] value))

(defn- update-state! [id updates]
  (swap! states update id merge updates))

;; -----------------------------------------------------------------------------
;; HTML Utilities

(defn- escape-html
  "Escape HTML special characters"
  [s]
  (-> (str s)
      (.replace (js/RegExp. "&" "g") "&amp;")
      (.replace (js/RegExp. "<" "g") "&lt;")
      (.replace (js/RegExp. ">" "g") "&gt;")))

;; -----------------------------------------------------------------------------
;; Autocomplete Core

(defn- render-suggestion
  "Render a single suggestion item using the provided render function"
  [render-fn item index active? id-key]
  (let [item-id (get item id-key)]
    (str "<div class=\"suggestion" (when active? " active") "\" "
         "data-id=\"" (escape-html item-id) "\" "
         "data-index=\"" index "\">"
         (render-fn item escape-html)
         "</div>")))

(defn- render-suggestions
  "Render all suggestions"
  [items active-index render-fn id-key]
  (if (empty? items)
    ""
    (->> items
         (map-indexed (fn [idx item]
                        (render-suggestion render-fn item idx (= idx active-index) id-key)))
         (apply str))))

(defn- show-suggestions
  "Display suggestions dropdown"
  [instance-id ^js suggestions-el items render-fn id-key]
  (update-state! instance-id {:items items :active-index -1})
  (set! (.-innerHTML suggestions-el) (render-suggestions items -1 render-fn id-key))
  (.add (.-classList suggestions-el) "active"))

(defn- hide-suggestions
  "Hide suggestions dropdown"
  [instance-id ^js suggestions-el]
  (update-state! instance-id {:items [] :active-index -1})
  (.remove (.-classList suggestions-el) "active"))

(defn- fetch-items
  "Fetch suggestions from API"
  [instance-id query ^js suggestions-el api-endpoint render-fn id-key]
  (-> (js/fetch (str api-endpoint "?q=" (js/encodeURIComponent query)))
      (.then #(.json %))
      (.then (fn [data]
               (let [items (js->clj data :keywordize-keys true)]
                 (if (seq items)
                   (show-suggestions instance-id suggestions-el items render-fn id-key)
                   (hide-suggestions instance-id suggestions-el)))))
      (.catch (fn [_err]
                (hide-suggestions instance-id suggestions-el)))))

(defn- update-active
  "Update active suggestion highlighting"
  [instance-id ^js suggestions-el new-index render-fn id-key]
  (let [{:keys [items]} (get-state instance-id)
        clamped-index (cond
                        (neg? new-index) (dec (count items))
                        (>= new-index (count items)) 0
                        :else new-index)]
    (set-state! instance-id :active-index clamped-index)
    (set! (.-innerHTML suggestions-el) (render-suggestions items clamped-index render-fn id-key))))

(defn- on-input
  "Handle input event with debouncing"
  [instance-id ^js input-el ^js suggestions-el api-endpoint render-fn id-key]
  ;; Clear existing timeout
  (when-let [tid (:timeout-id (get-state instance-id))]
    (js/clearTimeout tid))

  (let [query (.-value input-el)]
    (if (< (count query) min-query-length)
      (hide-suggestions instance-id suggestions-el)
      ;; Debounce the API call
      (let [tid (js/setTimeout
                 #(fetch-items instance-id query suggestions-el api-endpoint render-fn id-key)
                 debounce-delay)]
        (set-state! instance-id :timeout-id tid)))))

(defn- on-keydown
  "Handle keyboard navigation"
  [instance-id ^js e ^js suggestions-el navigate-fn render-fn id-key]
  (let [key (.-key e)
        {:keys [items active-index]} (get-state instance-id)]
    (when (seq items)
      (case key
        "ArrowDown" (do (.preventDefault e)
                        (update-active instance-id suggestions-el (inc active-index) render-fn id-key))
        "ArrowUp" (do (.preventDefault e)
                      (update-active instance-id suggestions-el (dec active-index) render-fn id-key))
        "Enter" (do (.preventDefault e)
                    (when (>= active-index 0)
                      (let [item (nth items active-index)]
                        (navigate-fn item))))
        "Escape" (hide-suggestions instance-id suggestions-el)
        nil))))

(defn- on-suggestion-click
  "Handle click on suggestion"
  [instance-id ^js e navigate-fn id-key]
  (when-let [suggestion (.closest (.-target e) ".suggestion")]
    (when-let [item-id (.getAttribute suggestion "data-id")]
      (let [{:keys [items]} (get-state instance-id)
            item (first (filter #(= (str (get % id-key)) item-id) items))]
        (when item
          (navigate-fn item))))))

(defn- on-document-click
  "Hide suggestions when clicking outside"
  [instance-id ^js e ^js search-box-el ^js suggestions-el]
  (when-not (.contains search-box-el (.-target e))
    (hide-suggestions instance-id suggestions-el)))

;; -----------------------------------------------------------------------------
;; Autocomplete Initialization

(defn init-autocomplete
  "Initialize an autocomplete instance with configuration.

   Options:
   - :input-id       - ID of the input element
   - :suggestions-id - ID of the suggestions container
   - :api-endpoint   - API endpoint to fetch from (e.g., '/api/places')
   - :render-fn      - Function (fn [item escape-html] html-string) to render each suggestion
   - :id-key         - Key to use as item identifier (e.g., :slug, :obs_wmo)
   - :navigate-fn    - Function (fn [item]) called when item is selected"
  [{:keys [input-id suggestions-id api-endpoint render-fn id-key navigate-fn]}]
  (when-let [input-el (.getElementById js/document input-id)]
    (when-let [suggestions-el (.getElementById js/document suggestions-id)]
      (let [search-box-el (.-parentElement input-el)
            instance-id input-id]

        ;; Initialize state for this instance
        (update-state! instance-id {:timeout-id nil :active-index -1 :items []})

        ;; Input handler
        (.addEventListener input-el "input"
                           #(on-input instance-id input-el suggestions-el api-endpoint render-fn id-key))

        ;; Keyboard navigation
        (.addEventListener input-el "keydown"
                           #(on-keydown instance-id % suggestions-el navigate-fn render-fn id-key))

        ;; Click on suggestion
        (.addEventListener suggestions-el "click"
                           #(on-suggestion-click instance-id % navigate-fn id-key))

        ;; Click outside to close
        (.addEventListener js/document "click"
                           #(on-document-click instance-id % search-box-el suggestions-el))

        ;; Focus input on load
        (.focus input-el)))))

;; -----------------------------------------------------------------------------
;; Stations Map

(def ^:private map-style-url
  "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")

(def ^:private temp-min-zoom 7)

(defn- fetch-and-update-temps
  "Fetch temperatures for visible stations and update the map source."
  [^js map-obj]
  (when (>= (.getZoom map-obj) temp-min-zoom)
    (let [^js bounds (.getBounds map-obj)
          ^js sw (.getSouthWest bounds)
          ^js ne (.getNorthEast bounds)
          bounds-str (str (.-lng sw) "," (.-lat sw) "," (.-lng ne) "," (.-lat ne))]
      (-> (js/fetch (str "/weather/api/stations/temps?bounds=" bounds-str))
          (.then (fn [r] (.json r)))
          (.then (fn [temps]
                   (let [^js source (.getSource map-obj "stations")
                         ^js data (when source (.-_data source))]
                     (when data
                       (let [features (.-features data)]
                         (doseq [i (range (.-length features))]
                           (let [^js feature (aget features i)
                                 ^js props (.-properties feature)
                                 wmo (.-wmo props)
                                 temp (aget temps wmo)]
                             (if temp
                               (aset props "temp" (str (js/Math.round temp) "°"))
                               (js-delete props "temp"))))
                         (.setData source data))))))
          (.catch (fn [_] nil))))))

(defn- init-stations-map
  "Initialize MapLibre GL map for stations page"
  []
  (when-let [map-el (.getElementById js/document "map")]
    (when (exists? js/maplibregl)
      (let [map-obj (js/maplibregl.Map.
                     #js {:container "map"
                          :style map-style-url
                          :center #js [134.0 -28.0]
                          :zoom 3.5})]

        ;; Add navigation controls
        (.addControl map-obj (js/maplibregl.NavigationControl.))

        ;; Load station data when map is ready
        (.on map-obj "load"
             (fn []
               (-> (js/fetch "/weather/api/stations/geojson")
                   (.then (fn [r] (.json r)))
                   (.then (fn [data]
                            ;; Add source
                            (.addSource map-obj "stations"
                                        #js {:type "geojson" :data data})

                            ;; Add circle layer for station markers
                            (.addLayer map-obj
                                       #js {:id "station-circles"
                                            :type "circle"
                                            :source "stations"
                                            :paint #js {:circle-radius #js ["interpolate" #js ["linear"] #js ["zoom"]
                                                                            4 3
                                                                            8 5
                                                                            12 8]
                                                        :circle-color "#ff9800"
                                                        :circle-stroke-width 1.5
                                                        :circle-stroke-color "#ffffff"}})

                            ;; Add labels layer (visible at higher zoom)
                            (.addLayer map-obj
                                       #js {:id "station-labels"
                                            :type "symbol"
                                            :source "stations"
                                            :minzoom 8
                                            :layout #js {:text-field #js ["get" "name"]
                                                         :text-size 11
                                                         :text-offset #js [0 1.5]
                                                         :text-anchor "top"
                                                         :text-max-width 10}
                                            :paint #js {:text-color "#333"
                                                        :text-halo-color "#fff"
                                                        :text-halo-width 1}})

                            ;; Add temperature layer (visible at zoom 10+)
                            (.addLayer map-obj
                                       #js {:id "station-temps"
                                            :type "symbol"
                                            :source "stations"
                                            :minzoom temp-min-zoom
                                            :filter #js ["has" "temp"]
                                            :layout #js {:text-field #js ["get" "temp"]
                                                         :text-size 14
                                                         :text-font #js ["Open Sans Bold"]
                                                         :text-offset #js [0 -1.5]
                                                         :text-anchor "bottom"
                                                         :text-allow-overlap true}
                                            :paint #js {:text-color "#e65100"
                                                        :text-halo-color "#fff"
                                                        :text-halo-width 2}})

                            ;; Fetch temps when map moves (at zoom 10+)
                            (.on map-obj "moveend"
                                 (fn []
                                   (js/console.log "Zoom level:" (.getZoom map-obj))
                                   (fetch-and-update-temps map-obj)))

                            ;; Click handler for markers (must be after layer is added)
                            (.on map-obj "click" "station-circles"
                                 (fn [^js e]
                                   (let [^js feature (aget (.-features e) 0)
                                         ^js geom (.-geometry feature)
                                         coords (.-coordinates geom)
                                         ^js props (.-properties feature)]
                                     (-> (js/maplibregl.Popup.)
                                         (.setLngLat coords)
                                         (.setHTML (str "<div class=\"station-popup\">"
                                                        "<h4>" (.-name props) "</h4>"
                                                        "<div class=\"state\">" (.-state props) "</div>"
                                                        "<a href=\"/weather/station/" (.-wmo props) "\" class=\"view-link\">View station →</a>"
                                                        "</div>"))
                                         (.addTo map-obj)))))

                            ;; Change cursor on hover
                            (.on map-obj "mouseenter" "station-circles"
                                 (fn [] (set! (.. map-obj -_canvas -style -cursor) "pointer")))
                            (.on map-obj "mouseleave" "station-circles"
                                 (fn [] (set! (.. map-obj -_canvas -style -cursor) ""))))))))))))

(defn- init-capitals-map
  "Initialize MapLibre GL map for capitals weather on home page.
   Reads embedded GeoJSON data from #capitals-data script tag.
   Uses different center/zoom for mobile vs desktop."
  []
  (when-let [map-el (.getElementById js/document "capitals-map")]
    (when (exists? js/maplibregl)
      (when-let [data-el (.getElementById js/document "capitals-data")]
        (let [data (js/JSON.parse (.-textContent data-el))
              mobile? (<= (.-innerWidth js/window) 600)
              center (if mobile? #js [145.38 -35.93] #js [134.36 -28.80])
              zoom (if mobile? 3.31 3.06)
              map-obj (js/maplibregl.Map.
                       #js {:container "capitals-map"
                            :style map-style-url
                            :center center
                            :zoom zoom
                            ;; Disable interactions for static display
                            :interactive false
                            :scrollZoom false
                            :boxZoom false
                            :dragRotate false
                            :dragPan false
                            :keyboard false
                            :doubleClickZoom false
                            :touchZoomRotate false
                            :touchPitch false
                            :attributionControl false})]

          ;; Add data when map is ready
          (.on map-obj "load"
               (fn []
                 ;; Create HTML markers for each capital with weather emoji
                 (let [features (.-features data)]
                   (doseq [i (range (.-length features))]
                     (let [^js feature (aget features i)
                           ^js geom (.-geometry feature)
                           coords (.-coordinates geom)
                           ^js props (.-properties feature)
                           icon (or (.-icon props) "🌡️")
                           name (.-name props)
                           temp (.-temp props)
                           ;; Create marker element
                           el (js/document.createElement "div")]
                       (set! (.-className el) "capital-marker")
                       (set! (.-innerHTML el)
                             (str "<div class=\"capital-icon\">" icon "</div>"
                                  "<div class=\"capital-name\">" name "</div>"
                                  "<div class=\"capital-temp\">" (or temp "--") "</div>"))
                       ;; Add marker to map
                       (-> (js/maplibregl.Marker. #js {:element el :anchor "center"})
                           (.setLngLat coords)
                           (.addTo map-obj))))))))))))

;; -----------------------------------------------------------------------------
;; Page-specific Renderers

(defn- render-place
  "Render a place suggestion"
  [{:keys [name type state]} escape-html]
  (str "<span class=\"suggestion-name\">" (escape-html name) "</span>"
       "<span class=\"suggestion-meta\">" (escape-html (or type "")) ", " (escape-html state) "</span>"))

(defn- render-station
  "Render a station suggestion"
  [{:keys [obs_name state]} escape-html]
  (str "<span class=\"suggestion-name\">" (escape-html obs_name) "</span>"
       "<span class=\"suggestion-meta\">" (escape-html state) "</span>"))

;; -----------------------------------------------------------------------------
;; Initialization

(defn init
  "Initialize client-side functionality based on page"
  []
  ;; Place search autocomplete (home page)
  (init-autocomplete
   {:input-id "search"
    :suggestions-id "suggestions"
    :api-endpoint "/weather/api/places"
    :render-fn render-place
    :id-key :slug
    :navigate-fn (fn [{:keys [slug]}]
                   (set! (.-location js/window) (str "/weather/forecast/" slug)))})

  ;; Station search autocomplete (stations page)
  (init-autocomplete
   {:input-id "station-search"
    :suggestions-id "station-suggestions"
    :api-endpoint "/weather/api/stations"
    :render-fn render-station
    :id-key :obs_wmo
    :navigate-fn (fn [{:keys [obs_wmo]}]
                   (set! (.-location js/window) (str "/weather/station/" obs_wmo)))})

  ;; Capitals weather map (home page)
  (init-capitals-map)

  ;; Stations map (stations page)
  (init-stations-map))
