;; Process PBF using osmium export with streaming transducers
;; Pre-computes nearest BOM forecast location for each place
;;
;; Usage: clojure -Sdeps '{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}}' -M scripts/process_pbf_osmium.clj
;;
;; Requires: osmium-tool installed (brew install osmium-tool)

(ns process-pbf-osmium
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(def data-dir "data")
(def pbf-file (str data-dir "/australia.osm.pbf"))
(def sql-file (str data-dir "/places.sql"))
(def bom-locations-file (str data-dir "/bom/forecast_locations.json"))
(def batch-size 100)

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

;; Importance scores for ranking search results
(def importance
  {"city"           100
   "town"           80
   "suburb"         60
   "village"        50
   "national_park"  70
   "ski_resort"     65
   "beach"          40
   "lake"           40
   "mountain"       40
   "park"           30
   "nature_reserve" 35
   "attraction"     45
   "river"          35
   "water"          30
   "hamlet"         20
   "locality"       10
   "neighbourhood"  15})

;; State detection from coordinates
(def state-bounds
  {:WA        [112.9 129.0 -35.2 -13.7]
   :NT        [129.0 138.0 -26.0 -10.9]
   :SA        [129.0 141.0 -38.1 -26.0]
   :QLD       [138.0 153.6 -29.2 -10.7]
   :NSW       [141.0 153.6 -37.5 -28.2]
   :VIC       [140.9 150.0 -39.2 -34.0]
   :TAS       [143.8 148.5 -43.7 -39.6]
   :ACT       [148.7 149.4 -35.9 -35.1]
   :NORFOLK   [167.9 168.1 -29.1 -28.9]
   :LORD-HOWE [159.0 159.2 -31.6 -31.5]
   :CHRISTMAS [105.5 105.8 -10.6 -10.4]
   :COCOS     [96.8  96.9  -12.2 -12.1]
   :MACQUARIE [158.8 159.0 -54.8 -54.4]})

;; osmium tags-filter expressions
(def tag-filters
  [;; Places (nodes only)
   "n/place=city" "n/place=town" "n/place=village" "n/place=suburb"
   "n/place=hamlet" "n/place=locality" "n/place=neighbourhood"
   ;; Natural features (nodes and ways)
   "nw/natural=peak" "nw/natural=beach" "nw/natural=water"
   ;; Leisure (nodes and ways)
   "nw/leisure=park" "nw/leisure=nature_reserve"
   ;; Other
   "nw/boundary=national_park"
   "nw/tourism=attraction"
   "nw/landuse=winter_sports"])

;; ---------------------------------------------------------------------------
;; Haversine distance calculation
;; ---------------------------------------------------------------------------

(def ^:const EARTH_RADIUS_KM 6371.0)

(defn haversine-distance
  "Calculate distance in km between two points using Haversine formula"
  [lat1 lon1 lat2 lon2]
  (let [dlat (Math/toRadians (- lat2 lat1))
        dlon (Math/toRadians (- lon2 lon1))
        lat1-rad (Math/toRadians lat1)
        lat2-rad (Math/toRadians lat2)
        a (+ (Math/pow (Math/sin (/ dlat 2)) 2)
             (* (Math/cos lat1-rad)
                (Math/cos lat2-rad)
                (Math/pow (Math/sin (/ dlon 2)) 2)))
        c (* 2 (Math/asin (Math/sqrt a)))]
    (* EARTH_RADIUS_KM c)))

;; ---------------------------------------------------------------------------
;; BOM forecast locations
;; ---------------------------------------------------------------------------

(defn load-bom-locations
  "Load BOM forecast locations from JSON file"
  [path]
  (println "Loading BOM forecast locations from" path)
  (let [locations (json/read-str (slurp path) :key-fn keyword)]
    (println "  Loaded" (count locations) "forecast locations")
    locations))

(defn find-nearest-bom-location
  "Find the nearest BOM forecast location for given coordinates"
  [bom-locations lat lon]
  (reduce
    (fn [best loc]
      (let [dist (haversine-distance lat lon (:lat loc) (:lon loc))]
        (if (or (nil? best) (< dist (:distance best)))
          {:aac (:aac loc)
           :name (:name loc)
           :lat (:lat loc)
           :lon (:lon loc)
           :distance dist}
          best)))
    nil
    bom-locations))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn in-bounds? [lon lat [min-lon max-lon min-lat max-lat]]
  (and (>= lon min-lon) (<= lon max-lon)
       (>= lat min-lat) (<= lat max-lat)))

(defn detect-state [lon lat]
  (cond
    (in-bounds? lon lat (:ACT state-bounds)) "ACT"
    (in-bounds? lon lat (:NORFOLK state-bounds)) "NORFOLK"
    (in-bounds? lon lat (:LORD-HOWE state-bounds)) "LORD_HOWE"
    (in-bounds? lon lat (:CHRISTMAS state-bounds)) "CHRISTMAS"
    (in-bounds? lon lat (:COCOS state-bounds)) "COCOS"
    (in-bounds? lon lat (:MACQUARIE state-bounds)) "MACQUARIE"
    (in-bounds? lon lat (:TAS state-bounds)) "TAS"
    (in-bounds? lon lat (:VIC state-bounds)) "VIC"
    (in-bounds? lon lat (:NSW state-bounds)) "NSW"
    (in-bounds? lon lat (:QLD state-bounds)) "QLD"
    (in-bounds? lon lat (:SA state-bounds)) "SA"
    (in-bounds? lon lat (:NT state-bounds)) "NT"
    (in-bounds? lon lat (:WA state-bounds)) "WA"
    :else nil))

(defn determine-type
  "Determine place type and subtype from OSM tags"
  [tags]
  (let [place    (:place tags)
        natural  (:natural tags)
        leisure  (:leisure tags)
        boundary (:boundary tags)
        tourism  (:tourism tags)
        waterway (:waterway tags)
        landuse  (:landuse tags)
        water    (:water tags)]
    (cond
      ;; Place tags
      (#{"city" "town" "village" "suburb" "hamlet" "locality" "neighbourhood"} place)
      {:type place}

      ;; Natural features
      (= natural "beach") {:type "beach"}
      (= natural "peak")  {:type "mountain"}
      (and (= natural "water")
           (or (= water "lake") (= water "reservoir")))
      {:type "lake" :subtype water}
      (= natural "water") {:type "water" :subtype water}

      ;; Leisure
      (= leisure "park")           {:type "park"}
      (= leisure "nature_reserve") {:type "nature_reserve"}

      ;; Other
      (= boundary "national_park") {:type "national_park"}
      (= tourism "attraction")     {:type "attraction"}
      (= waterway "river")         {:type "river"}
      (= landuse "winter_sports")  {:type "ski_resort"}

      :else nil)))

(defn escape-sql [s]
  (when s
    (-> s
        (str/replace "'" "''")
        (str/replace "\n" " ")
        (str/replace "\r" ""))))

(defn sql-str
  "Wrap value in quotes for SQL, or return NULL"
  [v]
  (if v (str "'" (escape-sql (str v)) "'") "NULL"))

(defn sql-num
  "Return number or NULL for SQL"
  [v]
  (if v (str v) "NULL"))

;; ---------------------------------------------------------------------------
;; Geometry helpers
;; ---------------------------------------------------------------------------

(defn flatten-coords
  "Flatten nested coordinate arrays to list of [lon lat] pairs"
  [geom-type coordinates]
  (case geom-type
    "Point"        [coordinates]
    "Polygon"      (first coordinates)
    "MultiPolygon" (mapcat first coordinates)
    []))

(defn polygon-centroid
  "Simple centroid calculation for polygon coordinates"
  [coords]
  (let [n (count coords)]
    (when (pos? n)
      [(/ (reduce + (map first coords)) n)
       (/ (reduce + (map second coords)) n)])))

(defn geometry->centroid
  "Get centroid [lon lat] from geometry"
  [{:keys [type coordinates]}]
  (case type
    "Point"        coordinates
    "Polygon"      (polygon-centroid (first coordinates))
    "MultiPolygon" (polygon-centroid (mapcat first coordinates))
    nil))

;; ---------------------------------------------------------------------------
;; GeoJSON parsing
;; ---------------------------------------------------------------------------

(defn parse-geojson-line
  "Parse a GeoJSONSeq line (may have leading RS character)"
  [line]
  (try
    (let [clean (if (str/starts-with? line "\u001e")
                  (subs line 1)
                  line)]
      (when (and clean (not (str/blank? clean)))
        (json/read-str clean :key-fn keyword)))
    (catch Exception e
      (binding [*out* *err*]
        (println "Failed to parse JSON line:" (.getMessage e)))
      nil)))

(defn feature->place
  "Convert GeoJSON feature to place map"
  [{:keys [properties geometry id] :as feature}]
  (when (and feature geometry)
    (let [geom-type (:type geometry)
          centroid  (geometry->centroid geometry)]
      (when centroid
        {:name      (:name properties)
         :lon       (first centroid)
         :lat       (second centroid)
         :tags      (dissoc properties :name)
         :osm-id    (or id "unknown")
         :geom-type geom-type}))))

;; ---------------------------------------------------------------------------
;; Transducers
;; ---------------------------------------------------------------------------

(def xf-parse-features
  "Transducer: parse JSON lines to features"
  (comp
    (map parse-geojson-line)
    (filter some?)))

(def xf-to-places
  "Transducer: convert features to place maps"
  (comp
    (map feature->place)
    (filter some?)
    (filter :name)
    (filter #(not (str/blank? (:name %))))))

(defn xf-determine-type
  "Transducer: add type based on tags"
  [importance-map]
  (comp
    (map (fn [{:keys [tags] :as place}]
           (when-let [{:keys [type subtype]} (determine-type tags)]
             (assoc place
                    :type type
                    :subtype subtype
                    :importance (get importance-map type 0)))))
    (filter some?)))

(def xf-add-state
  "Transducer: add state from coordinates"
  (map (fn [{:keys [lon lat] :as place}]
         (assoc place :state (detect-state lon lat)))))

(defn xf-add-bom-location
  "Transducer: find and add nearest BOM forecast location"
  [bom-locations]
  (map (fn [{:keys [lat lon] :as place}]
         (let [nearest (find-nearest-bom-location bom-locations lat lon)]
           (assoc place
                  :bom-aac (:aac nearest)
                  :bom-name (:name nearest)
                  :bom-lat (:lat nearest)
                  :bom-lon (:lon nearest)
                  :bom-distance-km (:distance nearest))))))

(defn xf-add-id
  "Stateful transducer: add sequential IDs"
  []
  (fn [rf]
    (let [counter (volatile! 0)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result place]
         (let [id (vswap! counter inc)]
           (rf result (assoc place :id id))))))))

(defn xf-log-progress
  "Stateful transducer: log progress every n items"
  [n]
  (fn [rf]
    (let [counter (volatile! 0)]
      (fn
        ([] (rf))
        ([result]
         (println (str "  Total: " @counter " places"))
         (rf result))
        ([result item]
         (vswap! counter inc)
         (when (zero? (mod @counter n))
           (println (str "  Processed " @counter " places...")))
         (rf result item))))))

;; ---------------------------------------------------------------------------
;; SQL output
;; ---------------------------------------------------------------------------

(defn place->sql-values
  "Convert place map to SQL VALUES tuple string"
  [{:keys [id name lat lon type subtype state osm-id importance
           bom-aac bom-name bom-lat bom-lon bom-distance-km]}]
  (format "(%d,%s,%s,%s,%s,%s,%d,%.6f,%.6f,%s,%s,%.6f,%.6f,%.2f)"
          id
          (sql-str name)
          (sql-str type)
          (sql-str subtype)
          (sql-str state)
          (sql-str osm-id)
          (or importance 0)
          lat
          lon
          (sql-str bom-aac)
          (sql-str bom-name)
          bom-lat
          bom-lon
          bom-distance-km))

(defn write-batch!
  "Write places as individual INSERT statements for D1 compatibility"
  [^java.io.Writer writer places]
  (doseq [place places]
    (.write writer "INSERT INTO places (id,name,type,subtype,state,osm_id,importance,lat,lon,bom_aac,bom_name,bom_lat,bom_lon,bom_distance_km) VALUES ")
    (.write writer (place->sql-values place))
    (.write writer ";\n")))

(def sql-header
  "-- Australian Places Database with BOM Forecast Locations
-- Generated from OpenStreetMap data
-- BOM forecast locations pre-computed for each place
-- Attribution: Place data (c) OpenStreetMap contributors
--              Weather data (c) Bureau of Meteorology

-- BOM forecast locations (for GPS -> nearest forecast queries)
CREATE TABLE IF NOT EXISTS bom_locations (
  aac TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  state TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bom_state ON bom_locations(state);

-- Places with pre-computed nearest BOM forecast location
CREATE TABLE IF NOT EXISTS places (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  subtype TEXT,
  state TEXT,
  osm_id TEXT NOT NULL,
  importance INTEGER DEFAULT 0,

  -- Place coordinates (POI location)
  lat REAL NOT NULL,
  lon REAL NOT NULL,

  -- Pre-computed nearest BOM forecast location
  bom_aac TEXT NOT NULL,        -- e.g., 'VIC_PT023'
  bom_name TEXT NOT NULL,       -- e.g., 'Castlemaine'
  bom_lat REAL NOT NULL,        -- Forecast location latitude
  bom_lon REAL NOT NULL,        -- Forecast location longitude
  bom_distance_km REAL NOT NULL -- Distance to forecast location
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_name ON places(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_type ON places(type);
CREATE INDEX IF NOT EXISTS idx_state ON places(state);
CREATE INDEX IF NOT EXISTS idx_bom_aac ON places(bom_aac);

")

(defn bom-location->sql
  "Generate INSERT statement for a BOM location"
  [{:keys [aac name lat lon state]}]
  (format "INSERT INTO bom_locations VALUES ('%s',%s,%.6f,%.6f,'%s');"
          aac
          (sql-str name)
          lat
          lon
          state))

(defn write-bom-locations!
  "Write BOM locations to SQL file"
  [writer bom-locations]
  (println "Writing BOM locations...")
  (doseq [loc bom-locations]
    (.write writer (bom-location->sql loc))
    (.write writer "\n"))
  (.write writer "\n"))

;; ---------------------------------------------------------------------------
;; Streaming from osmium subprocess
;; ---------------------------------------------------------------------------

(def filtered-pbf (str data-dir "/filtered.osm.pbf"))

(defn run-tags-filter!
  "Run osmium tags-filter to extract matching features"
  [input-pbf output-pbf filters]
  (println "Filtering PBF with osmium tags-filter...")
  (println "  Filters:" (count filters))
  (let [cmd (concat ["osmium" "tags-filter" input-pbf]
                    filters
                    ["-o" output-pbf "--overwrite"])
        pb (doto (ProcessBuilder. (vec cmd))
             (.redirectError ProcessBuilder$Redirect/INHERIT)
             (.redirectOutput ProcessBuilder$Redirect/INHERIT))
        process (.start pb)
        exit-code (.waitFor process)]
    (when (not= exit-code 0)
      (throw (ex-info "osmium tags-filter failed" {:exit-code exit-code})))
    (println "  Filtered PBF:" (.length (io/file output-pbf)) "bytes")
    output-pbf))

(defn start-osmium-export
  "Start osmium export process on filtered PBF, return Process"
  [pbf-path]
  (println "Starting osmium export...")
  (let [cmd ["osmium" "export"
             "-f" "geojsonseq"
             "-o" "-"
             pbf-path]
        pb (doto (ProcessBuilder. cmd)
             (.redirectError ProcessBuilder$Redirect/INHERIT))]
    (.start pb)))

(defn line-reducible
  "Create a reducible over lines from a BufferedReader.
   Enables efficient transducer processing without loading all lines."
  [^java.io.BufferedReader reader]
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (loop [acc init]
        (if (reduced? acc)
          @acc
          (if-let [line (.readLine reader)]
            (recur (rf acc line))
            acc))))))

;; ---------------------------------------------------------------------------
;; Stats collection
;; ---------------------------------------------------------------------------

(defn stats-rf
  "Reducing function that collects stats while writing batches"
  [writer]
  (fn
    ([] {:places 0 :batches 0 :by-type {} :by-state {} :avg-bom-distance 0.0})
    ([stats] stats)
    ([stats batch]
     (write-batch! writer batch)
     (reduce
       (fn [s place]
         (-> s
             (update :places inc)
             (update-in [:by-type (:type place)] (fnil inc 0))
             (update-in [:by-state (:state place)] (fnil inc 0))
             (update :total-bom-distance (fnil + 0.0) (:bom-distance-km place))))
       (update stats :batches inc)
       batch))))

(defn print-stats [stats]
  (println)
  (println "Processing complete!")
  (println (str "Places extracted: " (:places stats)))
  (println (str "Batches written: " (:batches stats)))
  (when (pos? (:places stats))
    (println (format "Average distance to BOM forecast: %.1f km"
                     (/ (:total-bom-distance stats 0.0) (:places stats)))))
  (println)
  (println "By type:")
  (doseq [[type cnt] (sort-by val > (:by-type stats))]
    (println (str "  " type ": " cnt)))
  (println)
  (println "By state:")
  (doseq [[state cnt] (sort-by val > (dissoc (:by-state stats) nil))]
    (println (str "  " state ": " cnt)))
  (when-let [unknown (get-in stats [:by-state nil])]
    (println (str "  (unknown): " unknown))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn process-with-osmium! []
  (println "Processing PBF with osmium streaming...")
  (println "Input:" pbf-file)
  (println "Output:" sql-file)
  (println)

  (when-not (.exists (io/file pbf-file))
    (println "Error: PBF file not found:" pbf-file)
    (System/exit 1))

  (when-not (.exists (io/file bom-locations-file))
    (println "Error: BOM locations file not found:" bom-locations-file)
    (println "Run the BOM extraction first to create this file.")
    (System/exit 1))

  ;; Load BOM forecast locations
  (let [bom-locations (load-bom-locations bom-locations-file)
        start-time (System/currentTimeMillis)]

    ;; Step 1: Filter PBF to extract only features we care about
    (run-tags-filter! pbf-file filtered-pbf tag-filters)

    ;; Step 2: Export filtered PBF to GeoJSONSeq and process
    (let [process (start-osmium-export filtered-pbf)

          ;; Combined transducer pipeline
          xf-pipeline (comp
                        xf-parse-features
                        xf-to-places
                        (xf-determine-type importance)
                        xf-add-state
                        (xf-add-bom-location bom-locations)
                        (xf-add-id)
                        (xf-log-progress 10000)
                        (partition-all batch-size))]

      (println "Streaming from osmium export...")
      (println "  (Finding nearest BOM location for each place)")

      (let [stats (with-open [reader (io/reader (.getInputStream process))
                              writer (io/writer sql-file)]
                    ;; Write SQL header with schema
                    (.write writer sql-header)

                    ;; Write BOM locations table
                    (write-bom-locations! writer bom-locations)

                    ;; Process stream with transducers
                    (transduce
                      xf-pipeline
                      (stats-rf writer)
                      (line-reducible reader)))]

        ;; Wait for osmium to finish
        (let [exit-code (.waitFor process)]
          (when (not= exit-code 0)
            (println "Warning: osmium exited with code" exit-code)))

        ;; Print stats
        (print-stats stats)

        ;; Show timing and file sizes
        (let [elapsed (/ (- (System/currentTimeMillis) start-time) 1000.0)
              sql-size (-> (io/file sql-file) .length (/ 1024 1024))]
          (println)
          (println (format "SQL file size: %.1f MB" (double sql-size)))
          (println (format "Total time: %.1f seconds" elapsed)))))))

(process-with-osmium!)
