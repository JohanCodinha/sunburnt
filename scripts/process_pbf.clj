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
   [java.lang ProcessBuilder ProcessBuilder$Redirect]
   [javax.xml.parsers SAXParserFactory]
   [org.apache.commons.net.ftp FTPClient FTPReply]
   [org.xml.sax Attributes]
   [org.xml.sax.helpers DefaultHandler]))

(def importance
  {"city"           100
   "town"           80
   "suburb"         60
   "village"        50
   "national_park"  70
   "ski_resort"     65
   "water_park"     50
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
   "leisure=park" "leisure=nature_reserve" "leisure=water_park"
   "boundary=national_park"
   "tourism=attraction"
   "landuse=winter_sports"])

(def ^:const EARTH_RADIUS_KM 6371.0)
(def ^:const DEG_TO_RAD (/ Math/PI 180.0))

(defn slugify
  "Convert a place name and state to a URL-friendly slug.
   Example: 'Harcourt North' + 'VIC' → 'harcourt-north-vic'"
  [name state]
  (-> (str name "-" state)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn unique-slug
  "Generate a unique slug, appending type or numeric suffix if needed.
   seen-slugs is an atom containing a set of already-used slugs."
  [seen-slugs name state type]
  (let [base-slug (slugify name state)]
    (if-not (contains? @seen-slugs base-slug)
      (do (swap! seen-slugs conj base-slug)
          base-slug)
      ;; Try with type appended
      (let [typed-slug (str base-slug "-" (str/replace (or type "") #"[^a-z0-9]+" "-"))]
        (if-not (contains? @seen-slugs typed-slug)
          (do (swap! seen-slugs conj typed-slug)
              typed-slug)
          ;; Append numeric suffix
          (loop [n 2]
            (let [numbered-slug (str typed-slug "-" n)]
              (if-not (contains? @seen-slugs numbered-slug)
                (do (swap! seen-slugs conj numbered-slug)
                    numbered-slug)
                (recur (inc n))))))))))

;; BOM observation feed URLs by state
(def observation-feeds
  {"VIC" "http://reg.bom.gov.au/fwo/IDV60920.xml"
   "NSW" "http://reg.bom.gov.au/fwo/IDN60920.xml"
   "ACT" "http://reg.bom.gov.au/fwo/IDN60920.xml"
   "QLD" "http://reg.bom.gov.au/fwo/IDQ60920.xml"
   "SA"  "http://reg.bom.gov.au/fwo/IDS60920.xml"
   "WA"  "http://reg.bom.gov.au/fwo/IDW60920.xml"
   "TAS" "http://reg.bom.gov.au/fwo/IDT60920.xml"
   "NT"  "http://reg.bom.gov.au/fwo/IDD60920.xml"})

(defn parse-geofabrik-state
  "Pure function to parse Geofabrik state.txt content.
  Returns {:sequence int :timestamp string} or nil if parsing fails."
  [content]
  (when content
    (let [lines (str/split-lines content)
          props (into {}
                      (for [line lines
                            :when (str/includes? line "=")
                            :let [[k v] (str/split line #"=" 2)]]
                        [(keyword k) v]))]
      {:sequence (some-> (:sequenceNumber props) parse-long)
       :timestamp (:timestamp props)})))

(defn epoch-millis->rfc1123
  "Pure function to convert epoch milliseconds to RFC 1123 date-time string."
  [millis]
  (-> (java.time.Instant/ofEpochMilli millis)
      (.atZone java.time.ZoneOffset/UTC)
      (.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)))

(defn epoch-millis->iso8601
  "Pure function to convert epoch milliseconds to ISO-8601 string."
  [millis]
  (-> (java.time.Instant/ofEpochMilli millis)
      (.atZone java.time.ZoneOffset/UTC)
      (.format java.time.format.DateTimeFormatter/ISO_INSTANT)))

(defn get-ftp-last-modified
  "Get last modified time of an FTP file using MDTM command.
   Returns RFC 1123 date string or nil if unavailable.
   Uses Apache Commons Net FTPClient which properly supports MDTM,
   unlike Java's built-in FtpURLConnection which returns 0."
  [url-str]
  (let [url (java.net.URL. url-str)
        host (.getHost url)
        port (let [p (.getPort url)] (if (pos? p) p 21))
        path (.getPath url)
        client (FTPClient.)]
    (try
      (.connect client host port)
      (when-not (FTPReply/isPositiveCompletion (.getReplyCode client))
        (throw (ex-info "FTP connection refused" {:host host})))
      (when-not (.login client "anonymous" "anonymous@example.com")
        (throw (ex-info "FTP login failed" {:host host})))
      (when-let [mtime (.getModificationTime client path)]
        ;; Parse MDTM response (format: YYYYMMDDhhmmss)
        (let [formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
              parsed (java.time.LocalDateTime/parse mtime formatter)
              instant (.toInstant parsed java.time.ZoneOffset/UTC)]
          (epoch-millis->rfc1123 (.toEpochMilli instant))))
      (catch Exception e
        (println "Warning: Failed to get FTP last-modified:" (.getMessage e))
        nil)
      (finally
        (when (.isConnected client)
          (try (.logout client) (catch Exception _))
          (.disconnect client))))))

(defn parse-d1-metadata
  "Pure function to parse D1 wrangler JSON output.
  Returns map of {source-name metadata-map} or nil if json-output is nil."
  [json-output]
  (when json-output
    ;; wrangler returns [{:results [...], :success true, ...}]
    (-> (json/read-str json-output :key-fn keyword)
        first
        :results
        (->> (map (juxt :source identity))
             (into {})))))

(defn check-for-updates
  "Pure check to determine if any data sources have changed.
  Caller is responsible for fetching data and passing it in.
  Args: {:current-meta map
     :osm-state    {:sequence int :timestamp string}|nil
     :bom-last-mod string|nil
     :pbf-last-mod string|nil}
  Returns {:changed? bool
       :details  {:osm {:current int :stored int :changed? bool}
          :bom-dbf {:current string|nil :stored string|nil :changed? bool}}
       :sources  {:osm {:sequence int :timestamp string :pbf-modified string|nil}
          :bom-dbf {:last-modified string|nil}}}"
  [{:keys [current-meta osm-state bom-last-mod pbf-last-mod]}]
  (let [stored-osm-seq (some-> (get current-meta "osm") :sequence_number)
    stored-bom-mod (some-> (get current-meta "bom-dbf") :last_modified)
    osm-seq (:sequence osm-state)
    osm-changed? (or (nil? current-meta)
         (nil? (get current-meta "osm"))
         (not= osm-seq stored-osm-seq))
    bom-changed? (or (nil? current-meta)
         (nil? (get current-meta "bom-dbf"))
         (not= bom-last-mod stored-bom-mod))]
    {:changed? (or osm-changed? bom-changed?)
     :details {:osm {:current osm-seq
                     :stored stored-osm-seq
                     :changed? osm-changed?}
               :bom-dbf {:current bom-last-mod
                         :stored stored-bom-mod
                         :changed? bom-changed?}}
     :sources {:osm {:sequence osm-seq
                     :timestamp (:timestamp osm-state)
                     :pbf-modified pbf-last-mod}
               :bom-dbf {:last-modified bom-last-mod}}}))

(defn now-iso8601
  "Get current time as ISO-8601 string with milliseconds."
  []
  (-> (java.time.Instant/now)
      (.atZone java.time.ZoneOffset/UTC)
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))))

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

(defn parse-observation-stations
  "Parse BOM observation XML from an InputStream to extract station metadata.
   Returns a vector of {:wmo-id :name :lat :lon}"
  [input-stream]
  (let [stations (atom [])
        handler (proxy [DefaultHandler] []
                  (startElement [uri local-name qname ^Attributes attrs]
                    (when (= qname "station")
                      (let [wmo-id (.getValue attrs "wmo-id")
                            name   (.getValue attrs "stn-name")
                            lat    (.getValue attrs "lat")
                            lon    (.getValue attrs "lon")]
                        (when (and wmo-id lat lon)
                          (swap! stations conj
                                 {:wmo-id wmo-id
                                  :name   name
                                  :lat    (Double/parseDouble lat)
                                  :lon    (Double/parseDouble lon)}))))))]
        (doto (.newSAXParser (SAXParserFactory/newInstance))
          (.parse input-stream handler))
    @stations))

(defn find-nearest-observation-stations
  "Find the N nearest observation stations for given coordinates.
   Returns vector of stations sorted by distance, filtered by max-distance-km.
   Each station has :distance and :rank added."
  [stations lat lon n max-distance-km]
  (->> stations
       (map (fn [station]
              (assoc station :distance
                     (equirectangular-distance lat lon (:lat station) (:lon station)))))
       (filter #(<= (:distance %) max-distance-km))
       (sort-by :distance)
       (take n)
       (map-indexed (fn [idx station] (assoc station :rank (inc idx))))
       vec))

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
      (= leisure "water_park")     {:type "water_park"}

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
  [{:keys [name slug lat lon type subtype state osm-id importance
           bom-aac bom-distance-km]}]
  [name slug type subtype state osm-id (or importance 0)
   lat lon bom-aac bom-distance-km])

(defn batch->sql
  "Build a single INSERT statement for a batch of places"
  [places]
  (first
    (sql/format {:insert-into :places
                 :columns [:name :slug :type :subtype :state :osm_id :importance
                           :lat :lon :bom_aac :bom_distance_km]
                 :values (mapv place->values-row places)}
                {:inline true})))

(defn bom-location->sql
  "Generate INSERT statement for a BOM location using HoneySQL"
  [{:keys [aac name lat lon state obs-wmo obs-name obs-distance-km]}]
  (first
    (sql/format {:insert-into :bom_locations
                 :columns [:aac :name :lat :lon :state :obs_wmo :obs_name :obs_distance_km]
                 :values [[aac name lat lon state obs-wmo obs-name obs-distance-km]]}
                {:inline true})))

(defn bom-obs-station->sql
  "Generate INSERT statement for a BOM observation station mapping"
  [aac {:keys [wmo-id name lat lon distance rank]}]
  (first
    (sql/format {:insert-into :bom_obs_stations
                 :columns [:aac :obs_wmo :obs_name :obs_lat :obs_lon :distance_km :rank]
                 :values [[aac wmo-id name lat lon distance rank]]}
                {:inline true})))

(def bom-dbf-row->location
  "Transducer for transforming DBF rows into BOM location maps (precis=1 only)"
  (comp (take-while some?)
        (map dbf-row->location)
        (filter #(= 1 (:precis %)))
        (map #(dissoc % :precis))))

(defn xf-add-slug
  "Add unique slug to each place. seen-slugs is an atom for deduplication."
  [seen-slugs]
  (map (fn [{:keys [name state type] :as place}]
         (assoc place :slug (unique-slug seen-slugs name state type)))))

(defn geojson-line->places
  "Transducer pipeline for processing GeoJSON features into places with BOM locations"
  [bom-locations seen-slugs batch-size]
  (comp (map parse-geojson-line)
        (filter some?)
        (keep feature->place)
        (remove #(str/blank? (:name %)))
        (xf-determine-type importance)
        (xf-add-bom-location bom-locations)
        (xf-add-slug seen-slugs)
        (partition-all batch-size)))

(defn add-nearest-observation-stations
  "Add nearest observation stations to a BOM location.
   Returns {:bom-loc updated-loc :obs-stations [...]} where obs-stations
   are the N nearest stations within max-distance-km."
  [obs-stations n max-distance-km {:keys [aac lat lon] :as bom-loc}]
  (let [nearest (find-nearest-observation-stations obs-stations lat lon n max-distance-km)]
    {:bom-loc (if (seq nearest)
                (assoc bom-loc
                       :obs-wmo (:wmo-id (first nearest))
                       :obs-name (:name (first nearest))
                       :obs-distance-km (:distance (first nearest)))
                bom-loc)
     :obs-stations (mapv #(assoc % :aac aac) nearest)}))

(defn metadata->sql
  "Generate SQL INSERT statements for data source metadata."
  [sources refreshed-at]
  (let [{:keys [osm bom-dbf]} sources]
    (str
     ;; OSM metadata
     (first (sql/format
             {:insert-into :data_sources
              :values [{:source "osm"
                        :last_modified (:pbf-modified osm)
                        :sequence_number (:sequence osm)
                        :refreshed_at refreshed-at}]
              :on-conflict [:source]
              :do-update-set [:last_modified :sequence_number :refreshed_at]}
             {:inline true}))
     ";\n"
     ;; BOM DBF metadata
     (first (sql/format
             {:insert-into :data_sources
              :values [{:source "bom-dbf"
                        :last_modified (:last-modified bom-dbf)
                        :sequence_number nil
                        :refreshed_at refreshed-at}]
              :on-conflict [:source]
              :do-update-set [:last_modified :refreshed_at]}
             {:inline true}))
     ";\n")))

(defn build-d1-query-command
  "Build wrangler d1 execute command for querying data sources.
  Returns command vector suitable for sh/sh."
  [local?]
  (concat ["wrangler" "d1" "execute" "places-db"]
          (if local? ["--local"] ["--remote"])
          ["--json" "--command" "SELECT * FROM data_sources"]))

(defn build-osmium-filter-command
  "Build osmium tags-filter command.
  Returns command vector suitable for sh/sh."
  [pbf-file filtered-pbf]
  (concat ["osmium" "tags-filter" pbf-file]
          tag-filters
          ["-o" filtered-pbf "--overwrite"]))

(defn build-osmium-export-command
  "Build osmium export command for ProcessBuilder"
  [filtered-pbf]
  ["osmium" "export" "-f" "geojsonseq" "-o" "-" filtered-pbf])

(defn sh!
  "Execute shell command and return :out on success.
  Throws ex-info if exit code is non-zero.
  Takes a command as a sequence of strings."
  [cmd]
  (let [result (apply sh/sh cmd)]
    (if (zero? (:exit result))
      (:out result)
      (throw (ex-info "Shell command failed"
                      {:command cmd
                       :exit-code (:exit result)
                       :stderr (:err result)})))))

(defn sh-silent
  "Execute shell command and return :out on success, nil on failure.
  Use for queries where failure is acceptable (e.g., table doesn't exist yet)."
  [cmd]
  (let [result (apply sh/sh cmd)]
    (when (zero? (:exit result))
      (:out result))))

(defn process! [{:keys [pbf-file pbf-url sql-file bom-dbf-url batch-size force? local? osm-state-url
                        obs-stations-per-location obs-max-distance-km] :as config}]
  (let [file (io/file pbf-file)]
    (when-not (.exists file)
      (io/make-parents file)
      (let [conn (.openConnection (java.net.URL. pbf-url))]
        (with-open [in (.getInputStream conn)
                    out (io/output-stream file)]
          (io/copy in out)))))
  (let [;; Query D1 database (may be empty/missing on first run)
        current-meta (-> (build-d1-query-command local?)
                         sh-silent
                         parse-d1-metadata)
        ;; Fetch and parse Geofabrik state 
        osm-state (-> osm-state-url slurp parse-geofabrik-state)
        ;; Get BOM DBF last-modified from FTP 
        bom-last-mod (get-ftp-last-modified bom-dbf-url)
        ;; Get local PBF file modified time
        pbf-last-mod (let [file (io/file pbf-file)]
                       (when (.exists file)
                         (epoch-millis->iso8601 (.lastModified file))))
        update-result (check-for-updates {:current-meta current-meta
                                          :osm-state osm-state
                                          :bom-last-mod bom-last-mod
                                          :pbf-last-mod pbf-last-mod})]
    (println "Checking for data source updates...")
    (println "  OSM sequence:" (get-in update-result [:details :osm :current])
             "(stored:" (get-in update-result [:details :osm :stored]) ")"
             (if (get-in update-result [:details :osm :changed?]) "[CHANGED]" "[unchanged]"))
    (println "  BOM DBF last-modified:" (get-in update-result [:details :bom-dbf :current])
             "(stored:" (get-in update-result [:details :bom-dbf :stored]) ")"
             (if (get-in update-result [:details :bom-dbf :changed?]) "[CHANGED]" "[unchanged]"))
    (when (or force? (:changed? update-result))
      ;; Proceed with processing
      (let [refreshed-at (now-iso8601)
            sources (:sources update-result)
            filtered-pbf (str/replace pbf-file #"\.osm\.pbf$" ".filtered.osm.pbf")
            _ (sh! (build-osmium-filter-command pbf-file filtered-pbf))
            osmium-geojsonseq-export-process (-> (ProcessBuilder. (build-osmium-export-command filtered-pbf))
                                                 (.redirectError ProcessBuilder$Redirect/INHERIT)
                                                 (.start))
            _ (println "Fetching BOM observation stations...")
            obs-stations (->> (vals observation-feeds)
                              (map #(future
                                      (with-open [stream (.openStream (java.net.URL. %))]
                                        (parse-observation-stations stream))))
                              doall
                              (mapcat deref)
                              ;; Dedupe by WMO ID (stations appear in multiple state feeds)
                              (reduce (fn [acc station]
                                        (if (contains? acc (:wmo-id station))
                                          acc
                                          (assoc acc (:wmo-id station) station)))
                                      {})
                              vals
                              (into []))]
        (println "Streaming BOM forecast locations from" bom-dbf-url "...")
        (with-open [bom-stream (.openStream (java.net.URL. bom-dbf-url))
                    bom-reader (DBFReader. bom-stream)
                    geojson-reader (io/reader (.getInputStream osmium-geojsonseq-export-process))
                    writer (io/writer sql-file)]
          (let [bom-locations-raw (into [] bom-dbf-row->location (repeatedly #(.nextRow bom-reader)))
                n-stations (or obs-stations-per-location 5)
                max-dist (or obs-max-distance-km 100)
                _ (println "Computing nearest" n-stations "observation stations (within" max-dist "km) for" (count bom-locations-raw) "BOM locations...")
                results (mapv (partial add-nearest-observation-stations obs-stations n-stations max-dist)
                              bom-locations-raw)
                bom-locations (mapv :bom-loc results)
                all-obs-stations (mapcat :obs-stations results)]
            ;; schema.sql is in same directory as config file
            (let [config-dir (.getParent (io/file (first *command-line-args*)))]
              (.write writer (slurp (io/file config-dir "schema.sql"))))
            (.write writer (metadata->sql sources refreshed-at))
            (run! #(.write writer (str (bom-location->sql %) ";\n"))
                  bom-locations)
            (println "Writing" (count all-obs-stations) "observation station mappings...")
            (run! (fn [{:keys [aac] :as station}]
                    (.write writer (str (bom-obs-station->sql aac station) ";\n")))
                  all-obs-stations)
            (println "Processing OSM places...")
            (let [seen-slugs (atom #{})]
              (run! #(.write writer (str (batch->sql %) ";\n"))
                    (sequence (geojson-line->places bom-locations seen-slugs batch-size)
                              (line-seq geojson-reader)))
              (println "Generated" (count @seen-slugs) "unique slugs"))))
        (let [exit-code (.waitFor osmium-geojsonseq-export-process)]
          (when-not (zero? exit-code)
            (throw (ex-info "osmium export process failed"
                            {:exit-code exit-code
                             :command ["osmium" "export" "-f" "geojsonseq" "-o" "-" filtered-pbf]}))))
        (println "\nDone! SQL written to" sql-file)
        (println "Refreshed at:" refreshed-at)))))

(when-let [config-file (first *command-line-args*)]
  (process! (edn/read-string (slurp config-file))))
