# Places Database Pipeline

## Overview

We extract Australian place names from OpenStreetMap and pre-compute the nearest BOM (Bureau of Meteorology) forecast location for each place. This enables efficient weather lookups without runtime spatial calculations.

## Architecture

```
australia.osm.pbf (868 MB)
        ↓
   [osmium tags-filter]
   Filter to places, beaches, parks, etc.
        ↓
   filtered.osm.pbf (50 MB)
        ↓
   [osmium export → geojsonseq]
   Stream features with geometry
        ↓
   [Clojure transducers]
   - Parse GeoJSON
   - Extract centroid from polygons
   - Determine place type
   - Find nearest BOM forecast location (haversine)
   - Generate SQL
        ↓
   places.sql (21 MB)
        ↓
   [wrangler d1 execute]
        ↓
   Cloudflare D1 database
```

## Data Sources

### OpenStreetMap (via Geofabrik)
- **File**: `australia.osm.pbf` (868 MB)
- **URL**: https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf
- **Updates**: Daily

### BOM Forecast Locations
- **Source**: `ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00013.zip`
- **Format**: Shapefile with AAC codes, names, and coordinates
- **Count**: 522 public forecast locations

## Database Schema

### `bom_locations` (522 rows)
For GPS → nearest forecast queries.

```sql
CREATE TABLE bom_locations (
  aac TEXT PRIMARY KEY,    -- e.g., 'VIC_PT012'
  name TEXT NOT NULL,      -- e.g., 'Castlemaine'
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  state TEXT NOT NULL
);
```

### `places` (83,797 rows)
Places with pre-computed nearest BOM forecast.

```sql
CREATE TABLE places (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  subtype TEXT,
  state TEXT,
  osm_id TEXT NOT NULL,
  importance INTEGER DEFAULT 0,

  -- Place coordinates (POI centroid)
  lat REAL NOT NULL,
  lon REAL NOT NULL,

  -- Pre-computed nearest BOM forecast location
  bom_aac TEXT NOT NULL,
  bom_name TEXT NOT NULL,
  bom_lat REAL NOT NULL,
  bom_lon REAL NOT NULL,
  bom_distance_km REAL NOT NULL
);
```

## Query Patterns

### 1. Place Name → Weather Forecast
```sql
SELECT bom_aac, bom_name, bom_lat, bom_lon
FROM places
WHERE name = 'Harcourt'
-- Returns: VIC_PT012, Castlemaine, -37.0811, 144.2392
```
Then fetch `IDV10753.xml` and parse only `VIC_PT012`.

### 2. GPS Coordinates → Nearest Forecast
```sql
SELECT aac, name, lat, lon,
  ROUND(111.32 * SQRT(
    POW(lat - :user_lat, 2) +
    POW((lon - :user_lon) * COS(:user_lat * 3.14159 / 180), 2)
  ), 1) as distance_km
FROM bom_locations
ORDER BY distance_km
LIMIT 1
```

## Place Types Extracted

| Type | Count | Source Tags |
|------|-------|-------------|
| park | 25,944 | `leisure=park` |
| mountain | 16,486 | `natural=peak` |
| locality | 9,422 | `place=locality` |
| hamlet | 5,021 | `place=hamlet` |
| nature_reserve | 4,947 | `leisure=nature_reserve` |
| lake | 4,673 | `natural=water` + `water=lake` |
| suburb | 4,257 | `place=suburb` |
| water | 3,942 | `natural=water` |
| attraction | 3,520 | `tourism=attraction` |
| village | 1,848 | `place=village` |
| beach | 1,820 | `natural=beach` |
| town | 1,172 | `place=town` |
| neighbourhood | 665 | `place=neighbourhood` |
| city | 70 | `place=city` |
| ski_resort | 10 | `landuse=winter_sports` |

## Running the Pipeline

### Prerequisites
```bash
brew install osmium-tool
```

### Download OSM Data
```bash
clojure -M scripts/download_osm.clj
```

### Extract BOM Forecast Locations
```bash
mkdir -p data/bom
curl -s ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00013.zip -o data/bom/IDM00013.zip
unzip -o data/bom/IDM00013.zip -d data/bom/

# Extract public forecast locations to JSON
python3 -c "
import struct, json
with open('data/bom/IDM00013.dbf', 'rb') as f:
    f.read(4)
    num_records = struct.unpack('<I', f.read(4))[0]
    header_size = struct.unpack('<H', f.read(2))[0]
    record_size = struct.unpack('<H', f.read(2))[0]
    f.seek(32)
    fields = []
    while True:
        fd = f.read(32)
        if fd[0] == 0x0D: break
        name = fd[:11].rstrip(b'\x00').decode('ascii')
        ftype = chr(fd[11])
        length = fd[16]
        fields.append((name, ftype, length))
    f.seek(header_size)
    locations = []
    for i in range(num_records):
        record = f.read(record_size)
        pos = 1
        row = {}
        for name, ftype, length in fields:
            value = record[pos:pos+length].decode('latin-1').strip()
            row[name] = value
            pos += length
        if row.get('PUBLIC_FLG') == '1':
            locations.append({
                'aac': row['AAC'], 'name': row['PT_NAME'],
                'lat': float(row['LAT']), 'lon': float(row['LON']),
                'state': row['STATE_CODE']
            })
    with open('data/bom/forecast_locations.json', 'w') as out:
        json.dump(locations, out, indent=2)
    print(f'Saved {len(locations)} locations')
"
```

### Process PBF and Generate SQL
```bash
clojure -Sdeps '{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}}' \
  -M scripts/process_pbf_osmium.clj
```

### Load into D1
```bash
# Local development
npx wrangler d1 execute places-db --local --file=data/places.sql

# Production
npx wrangler d1 execute places-db --remote --file=data/places.sql
```

## Metrics

| Metric | Value |
|--------|-------|
| Source PBF | 868 MB |
| Filtered PBF | 50 MB |
| Places extracted | 83,797 |
| BOM forecast locations | 522 |
| Avg distance to forecast | 35.3 km |
| SQL file size | 21.1 MB |
| Processing time | ~30 seconds |

## Why Pre-compute?

### Before (runtime spatial queries)
```
User: "weather in Harcourt"
  → Find Harcourt coords
  → Download 7 state XML files (~MB)
  → Parse all, find nearest station
  → Return weather
```

### After (pre-computed)
```
User: "weather in Harcourt"
  → Lookup → bom_aac: VIC_PT012 (Castlemaine)
  → Fetch IDV10753.xml, parse only VIC_PT012
  → Return weather
```

Benefits:
- Single targeted XML fetch instead of 7
- No runtime spatial calculations
- Smaller responses, faster parsing
- GPS → forecast in 1ms (522 row scan)
