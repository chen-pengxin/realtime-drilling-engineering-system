-- Preserve legacy well IDs for historical task references after evidence-backed
-- identity reconciliation. Canonical wells alone receive new bores/imports.
CREATE TABLE IF NOT EXISTS drilling_well_alias (
 alias_well_id BIGINT PRIMARY KEY,
 canonical_well_id BIGINT NOT NULL,
 alias_code VARCHAR(128) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 evidence_json JSON NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(alias_well_id) REFERENCES drilling_well(well_id),
 FOREIGN KEY(canonical_well_id) REFERENCES drilling_well(well_id),
 CHECK(alias_well_id <> canonical_well_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
