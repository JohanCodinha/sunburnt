-- Australian Places Database Schema for D1
-- Data sources:
--   Places: OpenStreetMap (ODbL license)
--   Forecasts: Bureau of Meteorology
-- Attribution:
--   Place data (c) OpenStreetMap contributors
--   Weather data (c) Bureau of Meteorology

-- BOM forecast locations (522 public forecast points)
-- Used for GPS -> nearest forecast queries
DROP TABLE IF EXISTS bom_locations;

CREATE TABLE bom_locations (
    aac TEXT PRIMARY KEY,      -- e.g., 'VIC_PT012'
    name TEXT NOT NULL,        -- e.g., 'Castlemaine'
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    state TEXT NOT NULL        -- e.g., 'VIC'
);

CREATE INDEX idx_bom_state ON bom_locations(state);

-- Places with pre-computed nearest BOM forecast location
DROP TABLE IF EXISTS places;

CREATE TABLE places (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,           -- 'city', 'town', 'beach', 'park', etc.
    subtype TEXT,                 -- 'lake', 'reservoir', etc.
    state TEXT,                   -- 'VIC', 'NSW', 'QLD', etc.
    osm_id TEXT NOT NULL,         -- For attribution/debugging
    importance INTEGER DEFAULT 0, -- For ranking (derived from type)

    -- Place coordinates (POI centroid)
    lat REAL NOT NULL,
    lon REAL NOT NULL,

    -- Pre-computed nearest BOM forecast location
    bom_aac TEXT NOT NULL,        -- e.g., 'VIC_PT012'
    bom_name TEXT NOT NULL,       -- e.g., 'Castlemaine'
    bom_lat REAL NOT NULL,        -- Forecast location latitude
    bom_lon REAL NOT NULL,        -- Forecast location longitude
    bom_distance_km REAL NOT NULL -- Distance to forecast location
);

CREATE INDEX idx_name ON places(name COLLATE NOCASE);
CREATE INDEX idx_type ON places(type);
CREATE INDEX idx_state ON places(state);
CREATE INDEX idx_bom_aac ON places(bom_aac);
