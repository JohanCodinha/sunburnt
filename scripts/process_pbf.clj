(ns process-pbf
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [honey.sql :as sql])
  (:import
   [com.linuxense.javadbf DBFReader]
   [java.io FileInputStream]
   [java.lang ProcessBuilder ProcessBuilder$Redirect]))

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

;; osmium tags-filter expressions (filter which OSM objects to include)
(def tag-filters
  ["place=city" "place=town" "place=village" "place=suburb"
   "place=hamlet" "place=locality" "place=neighbourhood"
   "natural=peak" "natural=beach" "natural=water"
   "leisure=park" "leisure=nature_reserve"
   "boundary=national_park"
   "tourism=attraction"
   "landuse=winter_sports"])

(def ^:const EARTH_RADIUS_KM 6371.0)
(def ^:const DEG_TO_RAD (/ Math/PI 180.0))

(defn equirectangular-distance
  "Fast approximation for distance in km between two points.
  Accurate to <0.5% error for distances up to ~100km.
  Much faster than Haversine (~5-10x) for finding nearest locations."
  [lat1 lon1 lat2 lon2]
  (let [lat-avg-rad (* (/ (+ lat1 lat2) 2.0) DEG_TO_RAD)
        x (* (- lon2 lon1) (Math/cos lat-avg-rad))
        y (- lat2 lat1)
        distance-deg (Math/sqrt (+ (* x x) (* y y)))]
    (* EARTH_RADIUS_KM distance-deg DEG_TO_RAD)))

(defn dbf-row->location
  "Convert a DBF row to a location map"
  [row]
  {:aac   (.getString row "AAC")
   :name  (.getString row "PT_NAME")
   :lat   (.getDouble row "LAT")
   :lon   (.getDouble row "LON")
   :state (.getString row "STATE_CODE")
   :precis (.getInt row "PRECIS_FLG")})

(defn find-nearest-bom-location
  "Find the nearest BOM forecast location for given coordinates"
  [bom-locations lat lon]
  (reduce
    (fn [best loc]
      (let [dist (equirectangular-distance lat lon (:lat loc) (:lon loc))]
        (if (or (nil? best) (< dist (:distance best)))
          {:aac (:aac loc)
           :state (:state loc)
           :distance dist}
          best)))
    nil
    bom-locations))

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

(defn parse-geojson-line
  "Parse a GeoJSONSeq line (may have leading RS character)"
  [line]
  (some-> line
          (str/replace-first "\u001e" "")
          not-empty
          (json/read-str :key-fn keyword)))

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

(defn xf-determine-type
  "Add type based on tags"
  [importance-map]
  (comp
    (map (fn [{:keys [tags] :as place}]
           (when-let [{:keys [type subtype]} (determine-type tags)]
             (assoc place
                    :type type
                    :subtype subtype
                    :importance (get importance-map type 0)))))
    (filter some?)))

(defn xf-add-bom-location
  "Find and add nearest BOM forecast location (includes state)"
  [bom-locations]
  (map (fn [{:keys [lat lon] :as place}]
         (let [nearest (find-nearest-bom-location bom-locations lat lon)]
           (assoc place
                  :state (:state nearest)
                  :bom-aac (:aac nearest)
                  :bom-distance-km (:distance nearest))))))

(defn place->values-row
  "Convert a place map to a values row for batch insert"
  [{:keys [name lat lon type subtype state osm-id importance
           bom-aac bom-distance-km]}]
  [name type subtype state osm-id (or importance 0)
   lat lon bom-aac bom-distance-km])

(defn batch->sql
  "Build a single INSERT statement for a batch of places"
  [places]
  (first
    (sql/format {:insert-into :places
                 :columns [:name :type :subtype :state :osm_id :importance
                           :lat :lon :bom_aac :bom_distance_km]
                 :values (mapv place->values-row places)}
                {:inline true})))

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

  -- Reference to nearest BOM forecast location
  bom_aac TEXT NOT NULL REFERENCES bom_locations(aac),
  bom_distance_km REAL NOT NULL
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_name ON places(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_type ON places(type);
CREATE INDEX IF NOT EXISTS idx_state ON places(state);
CREATE INDEX IF NOT EXISTS idx_bom_aac ON places(bom_aac);

")

(defn bom-location->sql
  "Generate INSERT statement for a BOM location using HoneySQL"
  [{:keys [aac name lat lon state]}]
  (first
    (sql/format {:insert-into :bom_locations
                 :columns [:aac :name :lat :lon :state]
                 :values [[aac name lat lon state]]}
                {:inline true})))

(def bom-dbf-row->location
  "Transducer for transforming DBF rows into BOM location maps (precis=1 only)"
  (comp (take-while some?)
        (map dbf-row->location)
        (filter #(= 1 (:precis %)))
        (map #(dissoc % :precis))))

(defn geojson-line->places
  "Transducer pipeline for processing GeoJSON features into places with BOM locations"
  [bom-locations batch-size]
  (comp (map parse-geojson-line)
        (filter some?)
        (keep feature->place)
        (remove #(str/blank? (:name %)))
        (xf-determine-type importance)
        (xf-add-bom-location bom-locations)
        (partition-all batch-size)))

(defn process! [config]
  (let [{:keys [pbf-file sql-file bom-dbf-file batch-size]} config
        filtered-pbf (str/replace pbf-file #"\.osm\.pbf$" ".filtered.osm.pbf")
        _ (apply sh/sh "osmium" "tags-filter" pbf-file (concat tag-filters ["-o" filtered-pbf "--overwrite"]))
        process (-> (ProcessBuilder. ["osmium" "export" "-f" "geojsonseq" "-o" "-" filtered-pbf])
                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                    (.start))]
    (with-open [bom-reader (DBFReader. (FileInputStream. bom-dbf-file))
                geojson-reader (io/reader (.getInputStream process))
                writer (io/writer sql-file)]
      (let [bom-locations (into [] bom-dbf-row->location (repeatedly #(.nextRow bom-reader)))]
        (.write writer sql-header)
        (run! #(.write writer (str (bom-location->sql %) ";\n"))
              bom-locations)
        (run! #(.write writer (str (batch->sql %) ";\n"))
              (sequence (geojson-line->places bom-locations batch-size)
                        (line-seq geojson-reader)))))
    (let [exit-code (.waitFor process)]
      (when-not (zero? exit-code)
        (throw (ex-info "osmium export process failed"
                        {:exit-code exit-code
                         :command ["osmium" "export" "-f" "geojsonseq" "-o" "-" filtered-pbf]}))))))

(when-let [config-file (first *command-line-args*)]
  (process! (edn/read-string (slurp config-file))))
