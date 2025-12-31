#!/usr/bin/env clojure -M

;; Download Australia OSM data from Geofabrik
;; Usage: clojure -M scripts/download_osm.clj

(require '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io])

(def data-dir "data")
(def osm-url "https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf")
(def output-file (str data-dir "/australia.osm.pbf"))

(defn file-size-mb [path]
  (-> (io/file path) .length (/ 1024 1024) long))

(defn download! []
  (println "Downloading Australia OSM data from Geofabrik...")
  (println "URL:" osm-url)
  (println "Output:" output-file)
  (println "This may take 5-10 minutes (~868MB)...")
  (println)

  (.mkdirs (io/file data-dir))

  (let [{:keys [exit err]} (sh "curl" "-L" "-o" output-file "--progress-bar" osm-url)]
    (when-not (zero? exit)
      (println "Error downloading:" err)
      (System/exit 1)))

  (println)
  (println "Download complete!")
  (println (str "File size: " (file-size-mb output-file) " MB")))

(download!)
