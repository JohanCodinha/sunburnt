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
