-- Australian Places Database with BOM Forecast Locations
-- Generated from OpenStreetMap data
-- BOM forecast locations pre-computed for each place
-- Attribution: Place data (c) OpenStreetMap contributors
--              Weather data (c) Bureau of Meteorology

-- Data source metadata for change detection and UI display
CREATE TABLE IF NOT EXISTS data_sources (
  source TEXT PRIMARY KEY,
  last_modified TEXT,
  sequence_number INTEGER,
  refreshed_at TEXT NOT NULL
) STRICT;

-- BOM forecast locations (for GPS -> nearest forecast queries)
CREATE TABLE IF NOT EXISTS bom_locations (
  aac TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  state TEXT NOT NULL,
  obs_wmo TEXT,
  obs_name TEXT,
  obs_distance_km REAL
);

CREATE INDEX IF NOT EXISTS idx_bom_state ON bom_locations(state);

-- Places with pre-computed nearest BOM forecast location
CREATE TABLE IF NOT EXISTS places (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  subtype TEXT,
  state TEXT,
  osm_id TEXT NOT NULL,
  importance INTEGER DEFAULT 0,

  -- Place coordinates (POI location)
  lat REAL NOT NULL,
  lon REAL NOT NULL,

  -- Reference to nearest BOM forecast location
  bom_aac TEXT NOT NULL REFERENCES bom_locations(aac),
  bom_distance_km REAL NOT NULL
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_name ON places(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_type ON places(type);
CREATE INDEX IF NOT EXISTS idx_state ON places(state);
CREATE INDEX IF NOT EXISTS idx_bom_aac ON places(bom_aac);
