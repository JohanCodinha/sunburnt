# Multi-Station Observation Data Plan

## Problem Statement

Currently, each BOM forecast location is mapped to a single nearby observation station. Some stations (like Frankston Beach) only report limited data (wind only, no temperature). This results in incomplete current conditions displays.

## Proposed Solution

Pre-compute multiple nearby observation stations for each BOM location, then at runtime query them in order of distance until we have complete observation data. Track the source of each data point for UI transparency.

---

## Phase 1: Database Schema Changes

### New Table: `bom_obs_stations`

Store multiple observation stations per BOM forecast location:

```sql
CREATE TABLE IF NOT EXISTS bom_obs_stations (
  aac TEXT NOT NULL,           -- BOM forecast location AAC code
  obs_wmo TEXT NOT NULL,       -- WMO station ID
  obs_name TEXT NOT NULL,      -- Station name
  obs_lat REAL NOT NULL,       -- Station latitude
  obs_lon REAL NOT NULL,       -- Station longitude
  distance_km REAL NOT NULL,   -- Distance from forecast location
  rank INTEGER NOT NULL,       -- 1 = closest, 2 = second closest, etc.
  PRIMARY KEY (aac, obs_wmo)
);

CREATE INDEX IF NOT EXISTS idx_obs_aac ON bom_obs_stations(aac);
CREATE INDEX IF NOT EXISTS idx_obs_rank ON bom_obs_stations(aac, rank);
```

### Migration Strategy

- Keep existing `obs_wmo`, `obs_name`, `obs_distance_km` columns in `bom_locations` for backwards compatibility during transition
- New table allows flexible number of stations per location (3-5 recommended)
- Can be dropped from `bom_locations` in a future cleanup

---

## Phase 2: Data Pipeline Changes

### File: `scripts/process_pbf.clj`

#### 2.1 New Function: `find-nearest-observation-stations`

Replace `find-nearest-observation-station` (singular) with a version that returns multiple stations:

```clojure
(defn find-nearest-observation-stations
  "Find the N nearest observation stations for given coordinates.
   Returns vector of stations sorted by distance."
  [stations lat lon n]
  (->> stations
       (map (fn [station]
              (assoc station :distance
                     (equirectangular-distance lat lon (:lat station) (:lon station)))))
       (sort-by :distance)
       (take n)
       (map-indexed (fn [idx station] (assoc station :rank (inc idx))))
       vec))
```

#### 2.2 New Function: `bom-obs-station->sql`

Generate INSERT for the new table:

```clojure
(defn bom-obs-station->sql
  "Generate INSERT statement for a BOM observation station mapping"
  [aac {:keys [wmo-id name lat lon distance rank]}]
  (first
    (sql/format {:insert-into :bom_obs_stations
                 :columns [:aac :obs_wmo :obs_name :obs_lat :obs_lon :distance_km :rank]
                 :values [[aac wmo-id name lat lon distance rank]]}
                {:inline true})))
```

#### 2.3 Update `add-nearest-observation-station`

Compute and store multiple stations (keep first one for backwards compat):

```clojure
(defn add-nearest-observation-stations
  "Add nearest observation stations to a BOM location.
   Returns {:bom-loc updated-loc :obs-stations [...]}."
  [obs-stations n {:keys [aac lat lon] :as bom-loc}]
  (let [nearest (find-nearest-observation-stations obs-stations lat lon n)]
    {:bom-loc (if (seq nearest)
                (assoc bom-loc
                       :obs-wmo (:wmo-id (first nearest))
                       :obs-name (:name (first nearest))
                       :obs-distance-km (:distance (first nearest)))
                bom-loc)
     :obs-stations (mapv #(assoc % :aac aac) nearest)}))
```

#### 2.4 Configuration

Add to `config.edn`:

```clojure
:obs-stations-per-location 5  ; Number of observation stations to store per location
```

---

## Phase 3: Backend Changes

### File: `src/main/worker/bom.cljs`

#### 3.1 New Response Format

Change observation response to include source attribution:

```clojure
;; Current format:
{:station "Frankston Beach"
 :temp_c 23.5
 :humidity 65
 ...}

;; New format:
{:temp_c 23.5
 :feels_like_c nil
 :humidity 65
 :wind_speed_kmh 17
 :gust_speed_kmh 28
 :rain_24hr_mm nil
 :sources {:temp_c {:station "Melbourne Airport" :distance_km 45.2}
           :humidity {:station "Frankston Beach" :distance_km 10.8}
           :wind_speed_kmh {:station "Frankston Beach" :distance_km 10.8}
           :gust_speed_kmh {:station "Frankston Beach" :distance_km 10.8}}
 :primary_station "Frankston Beach"}  ; The closest station used
```

#### 3.2 New Function: `fetch-observations-multi`

```clojure
(defn fetch-observations-multi
  "Fetch observations from multiple stations, merging data.
   Prefers data from closer stations. Tracks source of each field.
   stations is a vector of {:obs_wmo :obs_name :distance_km :rank}"
  [state-code stations]
  (if (empty? stations)
    (js/Promise.resolve nil)
    (let [;; Fields we want to collect
          target-fields [:temp_c :feels_like_c :humidity :wind_speed_kmh
                        :gust_speed_kmh :rain_24hr_mm]
          ;; Fetch all stations in parallel
          fetch-promises (mapv #(fetch-observation-by-wmo state-code (:obs_wmo %)) stations)]
      (-> (js/Promise.all (clj->js fetch-promises))
          (.then (fn [results]
                   (let [results-vec (js->clj results :keywordize-keys true)]
                     (merge-observations stations results-vec target-fields))))))))
```

#### 3.3 New Function: `merge-observations`

```clojure
(defn- merge-observations
  "Merge observations from multiple stations.
   For each field, use the first non-nil value (stations ordered by distance).
   Track which station provided each value."
  [stations observations target-fields]
  (let [station-obs-pairs (map vector stations observations)
        ;; For each target field, find first station with non-nil value
        field-values (into {}
                       (for [field target-fields]
                         (let [[station obs] (first (filter (fn [[_ obs]]
                                                              (and obs (some? (get obs field))))
                                                            station-obs-pairs))]
                           (when obs
                             [field {:value (get obs field)
                                     :station (:obs_name station)
                                     :distance_km (:distance_km station)}]))))
        ;; Build final response
        values (into {} (for [[k v] field-values] [k (:value v)]))
        sources (into {} (for [[k v] field-values]
                          [k {:station (:station v) :distance_km (:distance_km v)}]))
        primary (first (filter some? observations))]
    (when (seq values)
      (assoc values
             :sources sources
             :primary_station (:station primary)
             :observation_time (:observation_time primary)))))
```

### File: `src/main/worker/core.cljs`

#### 3.4 Update Database Query

Modify `find-place-by-slug` to also fetch observation stations:

```clojure
(defn- find-obs-stations-for-aac
  "Find observation stations for a BOM AAC code, ordered by rank."
  [^js db aac]
  (-> (.prepare db "SELECT obs_wmo, obs_name, obs_lat, obs_lon, distance_km, rank
                    FROM bom_obs_stations
                    WHERE aac = ?
                    ORDER BY rank ASC
                    LIMIT 5")
      (.bind aac)
      (.all)
      (.then (fn [^js result]
               (js->clj (.-results result) :keywordize-keys true)))))
```

#### 3.5 API vs SSR Behavior

**API endpoint (`/api/forecast`)**: Returns raw observation data from the single nearest station only. No merging, no truncation - just the full station data as-is. This keeps the API simple and predictable.

**SSR pages (`/forecast/:slug`)**: Uses multi-station merging to provide the best possible data to users, with source attribution.

#### 3.6 Update `forecast-page-handler`

Pass stations list to the observation fetch:

```clojure
;; Change from:
(obs-promise (if-let [wmo (:obs_wmo place)]
               (bom/fetch-observation-by-wmo state wmo)
               (js/Promise.resolve nil)))

;; To:
(stations-promise (find-obs-stations-for-aac db (:bom_aac place)))
;; Then chain:
(obs-promise (-> stations-promise
                 (.then (fn [stations]
                          (bom/fetch-observations-multi state stations)))))
```

---

## Phase 4: Frontend Changes

### File: `src/main/worker/views.cljs`

#### 4.1 Update `current-conditions` Component

Add inline source attribution:

```clojure
(defn- detail-with-source
  "Render a detail value with inline source attribution"
  [label value unit source]
  [:div.detail
   [:div.detail-label label]
   [:div.detail-value (str value unit)]
   (when source
     [:div.detail-source (str "from " (:station source))])])

(defn current-conditions
  "Render current observation data with source attribution"
  [{:keys [primary_station temp_c feels_like_c humidity wind_dir wind_speed_kmh
           gust_speed_kmh rain_24hr_mm sources]}]
  [:div.current-conditions
   [:h3 (str "Current Conditions" (when primary_station (str " — " primary_station)))]
   [:div.current-temps
    [:span.temp-main (if (some? temp_c) (str temp_c "°") "--")]
    (when (some? feels_like_c)
      [:span.feels-like (str "Feels like " feels_like_c "°")])]
   [:div.current-details
    (when (some? humidity)
      (detail-with-source "Humidity" humidity "%" (get sources :humidity)))
    (when (some? wind_speed_kmh)
      (detail-with-source "Wind" (str (or wind_dir "") " " wind_speed_kmh) " km/h"
                          (get sources :wind_speed_kmh)))
    (when (some? gust_speed_kmh)
      (detail-with-source "Gusts" gust_speed_kmh " km/h" (get sources :gust_speed_kmh)))
    (when (some? rain_24hr_mm)
      (detail-with-source "Rain (24h)" rain_24hr_mm " mm" (get sources :rain_24hr_mm)))]])
```

### File: `public/styles.css`

#### 4.2 Add Styles for Source Attribution

```css
/* Inline source attribution */
.detail-source {
  font-size: 0.75em;
  color: var(--text-muted);
  margin-top: 2px;
}
```

---

## Phase 5: Testing & Validation

### 5.1 Test Cases

1. **Location with full-data station**: Melbourne CBD should get all data from one station
2. **Location with limited station**: Mornington should merge temp from Melbourne Airport with wind from Frankston Beach
3. **Location with no nearby stations**: Should gracefully show "--" for all fields
4. **Source attribution**: Hover should show correct station name and distance

### 5.2 Verification Queries

```sql
-- Check stations per location
SELECT aac, COUNT(*) as station_count
FROM bom_obs_stations
GROUP BY aac
ORDER BY station_count DESC
LIMIT 10;

-- Check Mornington's observation stations
SELECT * FROM bom_obs_stations
WHERE aac = 'VIC_PT110'
ORDER BY rank;
```

---

## Implementation Order

1. **Schema**: Add new `bom_obs_stations` table
2. **Pipeline**: Update `process_pbf.clj` to compute and store multiple stations
3. **Regenerate**: Run pipeline to populate new table
4. **Backend**: Update observation fetching logic
5. **Frontend**: Add source attribution to UI
6. **Cleanup**: (Future) Remove redundant columns from `bom_locations`

---

## Performance Considerations

- **Parallel fetches**: All observation stations are fetched in parallel (no serial delay)
- **Early termination**: Could optimize to stop fetching once all fields are filled (future enhancement)
- **Caching**: BOM observation feeds have 30-min intervals; could cache responses (future enhancement)
- **Database**: Single additional query per page load (negligible overhead)

---

## Decisions

1. **Stations to store**: 5 per location
2. **Maximum distance threshold**: 100km (skip stations beyond this)
3. **Fallback behavior**: Show "--" if no data available, hide field if no station has it
4. **UI**: Show station name inline (e.g., "23° from Melbourne Airport")
5. **API behavior**: Return raw data from nearest station only (no merging/truncation) - the multi-station merging is for SSR pages only
