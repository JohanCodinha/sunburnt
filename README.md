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

Australian places database for geocoding, sourced from OpenStreetMap.

### Quick Start

```bash
# 1. Download OSM data (~868MB, takes 5-10 min)
clojure -M scripts/download_osm.clj

# 2. Process PBF to SQL (~2 min)
clojure -Sdeps '{:deps {org.openstreetmap.osmosis/osmosis-pbf {:mvn/version "0.49.2"}}}' \
  -M scripts/process_pbf.clj

# 3. Seed local D1 database
wrangler d1 execute PLACES_DB --local --file sql/schema.sql
wrangler d1 execute PLACES_DB --local --file data/places.sql

# 4. Verify
wrangler d1 execute PLACES_DB --local \
  --command "SELECT name, type, state FROM places WHERE name LIKE '%Melbourne%' LIMIT 5;"
```

### Database Stats

| Metric | Value |
|--------|-------|
| Total places | ~42,000 |
| Database size | ~3 MB |
| Types | cities, towns, suburbs, villages, mountains, beaches, parks |

### Scripts

| Script | Purpose |
|--------|---------|
| `scripts/download_osm.clj` | Download Australia PBF from Geofabrik |
| `scripts/process_pbf.clj` | Convert PBF directly to batched SQL |
| `scripts/upload_d1.clj` | Upload to D1 (local or remote) |

### Remote Deployment

```bash
# Create D1 database (first time only)
wrangler d1 create places-db

# Update wrangler.toml with the returned database_id, then:
wrangler d1 execute PLACES_DB --remote --file sql/schema.sql
wrangler d1 execute PLACES_DB --remote --file data/places.sql
```

### Data Attribution

Place data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licensed under ODbL.
