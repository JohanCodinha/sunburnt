;; Process PBF directly to batched SQL (no intermediate files)
;; Usage: clojure -Sdeps '{:deps {org.openstreetmap.osmosis/osmosis-pbf {:mvn/version "0.49.2"}}}' -M scripts/process_pbf.clj

(ns process-pbf
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [crosby.binary.osmosis OsmosisReader]
           [org.openstreetmap.osmosis.core.task.v0_6 Sink]
           [org.openstreetmap.osmosis.core.container.v0_6 NodeContainer]
           [org.openstreetmap.osmosis.core.domain.v0_6 Node Tag]
           [java.io File FileInputStream]))

(def data-dir "data")
(def pbf-file (str data-dir "/australia.osm.pbf"))
(def sql-file (str data-dir "/places.sql"))
(def batch-size 1000)

;; Importance scores
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

(defn get-tags
  "Extract tags as a map from OSM entity"
  [entity]
  (into {}
        (map (fn [^Tag t] [(.getKey t) (.getValue t)])
             (.getTags entity))))

(defn determine-type
  "Determine place type and subtype from OSM tags"
  [tags]
  (let [place    (get tags "place")
        natural  (get tags "natural")
        leisure  (get tags "leisure")
        boundary (get tags "boundary")
        tourism  (get tags "tourism")
        waterway (get tags "waterway")
        landuse  (get tags "landuse")
        water    (get tags "water")]
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

(defn place->sql-values
  "Convert place map to SQL VALUES tuple string"
  [{:keys [id name lat lon type subtype state osm-id importance]}]
  (format "(%d,'%s',%f,%f,'%s',%s,%s,'%s',%d)"
          id
          (escape-sql name)
          lat
          lon
          type
          (if subtype (str "'" (escape-sql subtype) "'") "NULL")
          (if state (str "'" state "'") "NULL")
          osm-id
          importance))

(defn write-batch!
  "Write a batch of places as a single INSERT statement"
  [^java.io.Writer writer places]
  (when (seq places)
    (.write writer "INSERT INTO places (id,name,lat,lon,type,subtype,state,osm_id,importance) VALUES\n")
    (.write writer (str/join ",\n" (map place->sql-values places)))
    (.write writer ";\n\n")))

(defn process-pbf! []
  (println "Processing PBF directly to SQL...")
  (println "Input:" pbf-file)
  (println "Output:" sql-file)
  (println "Batch size:" batch-size)
  (println)

  (when-not (.exists (io/file pbf-file))
    (println "Error: PBF file not found:" pbf-file)
    (System/exit 1))

  (let [stats (atom {:nodes 0 :places 0 :by-type {} :by-state {}})
        idx (atom 0)
        batch (atom [])
        writer (io/writer sql-file)]

    ;; Write header
    (.write writer "-- Australian Places Database\n")
    (.write writer "-- Generated from OpenStreetMap data\n")
    (.write writer "-- Attribution: Place data (c) OpenStreetMap contributors\n\n")

    (println "Reading PBF file...")

    ;; Create sink to receive entities
    (let [sink (reify Sink
                 (initialize [_ _])
                 (complete [_])
                 (close [_])
                 (process [_ container]
                   (when (instance? NodeContainer container)
                     (let [^Node node (.getEntity ^NodeContainer container)
                           tags (get-tags node)
                           name (get tags "name")]

                       (swap! stats update :nodes inc)

                       ;; Progress
                       (when (zero? (mod (:nodes @stats) 1000000))
                         (println (str "  Processed " (:nodes @stats) " nodes, "
                                       (:places @stats) " places...")))

                       ;; Extract place if valid
                       (when (and name (not (str/blank? name)))
                         (when-let [{:keys [type subtype]} (determine-type tags)]
                           (let [lon (.getLongitude node)
                                 lat (.getLatitude node)
                                 place {:id         @idx
                                        :name       name
                                        :lat        lat
                                        :lon        lon
                                        :type       type
                                        :subtype    subtype
                                        :state      (detect-state lon lat)
                                        :osm-id     (str "node/" (.getId node))
                                        :importance (get importance type 0)}]

                             (swap! idx inc)
                             (swap! stats update :places inc)
                             (swap! stats update-in [:by-type type] (fnil inc 0))
                             (when (:state place)
                               (swap! stats update-in [:by-state (:state place)] (fnil inc 0)))

                             ;; Add to batch
                             (swap! batch conj place)

                             ;; Write batch if full
                             (when (>= (count @batch) batch-size)
                               (write-batch! writer @batch)
                               (reset! batch [])))))))))]

      ;; Read PBF file
      (let [reader (OsmosisReader. (File. pbf-file))]
        (.setSink reader sink)
        (.run reader)))

    ;; Write remaining batch
    (write-batch! writer @batch)
    (.close writer)

    ;; Print summary
    (println)
    (println "Processing complete!")
    (println (str "Nodes processed: " (:nodes @stats)))
    (println (str "Places extracted: " (:places @stats)))
    (println)
    (println "By type:")
    (doseq [[type cnt] (sort-by val > (:by-type @stats))]
      (println (str "  " type ": " cnt)))
    (println)
    (println "By state:")
    (doseq [[state cnt] (sort-by val > (:by-state @stats))]
      (println (str "  " state ": " cnt)))

    ;; Show SQL file size
    (let [size (-> (io/file sql-file) .length (/ 1024 1024) int)]
      (println)
      (println (str "SQL file size: " size " MB")))))

(process-pbf!)
