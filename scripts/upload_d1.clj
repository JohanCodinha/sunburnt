#!/usr/bin/env clojure -M

;; Upload places database to Cloudflare D1
;; Requires: wrangler (npm install -g wrangler)
;; Usage: clojure -M scripts/upload_d1.clj [--local]

(require '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io])

(def sql-dir "sql")
(def data-dir "data")
(def schema-file (str sql-dir "/schema.sql"))
(def places-file (str data-dir "/places.sql"))
(def db-name "PLACES_DB")

(defn file-exists? [path]
  (.exists (io/file path)))

(defn file-size-mb [path]
  (-> (io/file path) .length (/ 1024 1024) long))

(defn check-wrangler! []
  (let [{:keys [exit]} (sh "which" "wrangler")]
    (when-not (zero? exit)
      (println "Error: wrangler not found!")
      (println "Install with: npm install -g wrangler")
      (System/exit 1))))

(defn check-files! []
  (when-not (file-exists? schema-file)
    (println "Error: Schema file not found:" schema-file)
    (System/exit 1))
  (when-not (file-exists? places-file)
    (println "Error: Places SQL file not found:" places-file)
    (println "Run process_places.clj first")
    (System/exit 1)))

(defn upload! [local?]
  (println "Uploading to D1 database:" db-name)
  (when local?
    (println "(Running in local mode)"))
  (println)

  ;; Create/reset schema
  (println "Creating schema...")
  (let [args (if local?
               ["wrangler" "d1" "execute" db-name "--local" "--file" schema-file]
               ["wrangler" "d1" "execute" db-name "--file" schema-file])
        {:keys [exit out err]} (apply sh args)]
    (when-not (empty? out) (println out))
    (when-not (empty? err) (println err))
    (when-not (zero? exit)
      (println "Error creating schema")
      (System/exit 1)))
  (println "Schema created.")
  (println)

  ;; Upload places data
  (println "Uploading places data (this may take a few minutes)...")
  (println (str "File size: " (file-size-mb places-file) " MB"))

  (let [args (if local?
               ["wrangler" "d1" "execute" db-name "--local" "--file" places-file]
               ["wrangler" "d1" "execute" db-name "--file" places-file])
        {:keys [exit out err]} (apply sh args)]
    (when-not (empty? out) (println out))
    (when-not (empty? err) (println err))
    (when-not (zero? exit)
      (println "Error uploading places data")
      (System/exit 1)))

  (println)
  (println "Upload complete!")

  ;; Verify
  (println)
  (println "Verifying upload...")
  (let [args (if local?
               ["wrangler" "d1" "execute" db-name "--local"
                "--command" "SELECT type, COUNT(*) as count FROM places GROUP BY type ORDER BY count DESC;"]
               ["wrangler" "d1" "execute" db-name
                "--command" "SELECT type, COUNT(*) as count FROM places GROUP BY type ORDER BY count DESC;"])
        {:keys [out err]} (apply sh args)]
    (when-not (empty? out) (println out))
    (when-not (empty? err) (println err))))

(defn -main [& args]
  (let [local? (some #(= % "--local") args)]
    (check-wrangler!)
    (check-files!)
    (upload! local?)))

(apply -main *command-line-args*)
