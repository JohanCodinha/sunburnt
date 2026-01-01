# ClojureScript on Cloudflare Workers

A technical demo exploring ClojureScript compilation to Cloudflare Workers. The domain application is incidental—the focus is on the mechanics of running ClojureScript in the Workers runtime.

## Key Technical Points

- **Shadow-CLJS** with `:esm` target compiles to ES modules for Workers
- **Reitit** for routing, with a thin Ring-style adapter (`worker.ring`)
- **Advanced compilation** keeps bundle size minimal
- Ring-like request/response maps enable familiar Clojure idioms

## Ring Adapter

The `worker.ring` namespace bridges Cloudflare's Fetch API to Ring conventions:

```clojure
;; Handlers receive Ring-style request maps
(defn my-handler [{:keys [parameters]}]
  (let [{:keys [id]} (:path parameters)]
    {:status 200
     :body {:id id}}))

;; Routes use Reitit with Malli coercion
(def router
  (r/router
    [["/api/items/:id" {:handler item-handler
                        :coercion malli/coercion
                        :parameters {:path [:map [:id :int]]}}]]
    {:compile coercion/compile-request-coercers}))
```

Routes with `:parameters` schemas get automatic validation:
- Invalid params return 400 with `{:error "Invalid parameters" :in :query-params :issues [...]}`
- Coerced params available under `:parameters` key in request

## Project Structure

```
src/main/worker/
├── core.cljs   # Entry point, route definitions
├── ring.cljs   # Ring adapter for Cloudflare
├── bom.cljs    # Example: BOM weather data fetching
└── views.cljs  # HTML templating
```

## Development

```bash
npm install
npm run dev
```

This starts both Shadow-CLJS (watching/compiling) and Wrangler (local Workers runtime) concurrently. The app runs at `http://localhost:8787`.

Individual scripts:
- `npm run watch` — Shadow-CLJS only
- `npm run wrangler` — Wrangler only

## Production

```bash
npm run release   # Optimized build
npm run deploy    # Deploy to Cloudflare
```

## Configuration

- `shadow-cljs.edn` — ClojureScript build config
- `wrangler.toml` — Cloudflare Workers config

## Places Database (D1)

Australian places database for geocoding, sourced from OpenStreetMap. Each place has a pre-computed nearest BOM forecast location for instant weather lookups.

### Data Sources

The places database combines two datasets to create a searchable mapping between interesting places and weather forecast locations:

**IDM00013.dbf** - BOM Forecast Locations (~684 locations)
- DBF file from the Bureau of Meteorology containing official forecast locations across Australia
- Includes location names and geographic coordinates
- Source: `ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00013.dbf`

**australia.osm.pbf** - OpenStreetMap Places (~83,000 places)
- PBF (Protocolbuffer Binary Format) file containing interesting places across Australia
- Includes towns, cities, suburbs, landmarks, and points of interest that users might search for
- Source: Geofabrik `https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf`

**How They're Combined**

The `scripts/process_pbf.clj` script:
1. Extracts all interesting places from the OSM PBF file
2. Parses BOM forecast locations from the DBF file
3. Pre-computes the nearest BOM forecast location for each OSM place using geographic distance calculations
4. Generates `data/places.sql` with each place containing its matched BOM location and distance

This pre-computation enables instant weather lookups—users can search for any place (e.g., "Bondi Beach") and immediately get the nearest BOM forecast location without runtime distance calculations. Average distance between a place and its forecast point is ~35 km.

### Quick Start

```bash
# 1. Download source data
mkdir -p data/bom
curl -o data/bom/IDM00013.dbf ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00013.dbf
curl -L -o data/australia.osm.pbf https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf

# 2. Process PBF to SQL with BOM location matching (requires: brew install osmium-tool)
clojure -M scripts/process_pbf.clj scripts/config.edn

# 3. Seed local D1 database
wrangler d1 execute PLACES_DB --local --file data/places.sql

# 4. Verify
wrangler d1 execute PLACES_DB --local \
  --command "SELECT name, state, bom_name, bom_distance_km FROM places WHERE name LIKE '%Melbourne%' LIMIT 5;"
```

### Database Stats

| Metric | Value |
|--------|-------|
| Total places | ~83,000 |
| BOM forecast locations | ~684 |
| Average distance to forecast | ~35 km |
| Database size | ~21 MB |

### Script

`scripts/process_pbf_osmium.clj` — Extracts places from OSM PBF, parses BOM forecast locations from DBF, and pre-computes nearest BOM location for each place.

### Remote Deployment

```bash
# Create D1 database (first time only)
wrangler d1 create places-db

# Update wrangler.toml with the returned database_id, then:
wrangler d1 execute PLACES_DB --remote --file data/places.sql
```

### Data Attribution

- Place data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licensed under ODbL
- Weather data © [Bureau of Meteorology](http://www.bom.gov.au)
