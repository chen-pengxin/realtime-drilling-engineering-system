-- Shared assets, independent design/actual heads and immutable source provenance.
-- Existing task/version primary keys remain valid; no historical record is deleted.
CREATE TABLE IF NOT EXISTS drilling_well_catalog (
 well_id BIGINT PRIMARY KEY, field_id BIGINT NOT NULL, identity_key VARCHAR(128) NOT NULL,
 coordinate_reference_json JSON NULL,
 UNIQUE KEY uk_catalog_identity(field_id,identity_key),
 FOREIGN KEY(well_id) REFERENCES drilling_well(well_id), FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_wellbore (
 wellbore_id BIGINT AUTO_INCREMENT PRIMARY KEY, well_id BIGINT NOT NULL, parent_wellbore_id BIGINT NULL,
 wellbore_code VARCHAR(128) NOT NULL, wellbore_name VARCHAR(200) NOT NULL,
 engineering_status VARCHAR(32) NOT NULL DEFAULT 'UNPLANNED', reference_json JSON NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NULL,
 UNIQUE KEY uk_wellbore(well_id,wellbore_code), FOREIGN KEY(well_id) REFERENCES drilling_well(well_id),
 FOREIGN KEY(parent_wellbore_id) REFERENCES drilling_wellbore(wellbore_id),
 CHECK(engineering_status IN ('UNPLANNED','PLANNED','DRILLING','SUSPENDED','COMPLETED','ABANDONED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_field_wellbore (
 field_id BIGINT NOT NULL,wellbore_id BIGINT NOT NULL,purpose VARCHAR(16) NOT NULL DEFAULT 'SUBJECT',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(field_id,wellbore_id),FOREIGN KEY(field_id) REFERENCES drilling_field(field_id),
 FOREIGN KEY(wellbore_id) REFERENCES drilling_wellbore(wellbore_id), CHECK(purpose IN ('SUBJECT','NEIGHBOR','REFERENCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_trajectory_version (
 survey_version_id BIGINT PRIMARY KEY,wellbore_id BIGINT NOT NULL,trajectory_kind VARCHAR(16) NOT NULL,
 source_locator VARCHAR(255) NOT NULL DEFAULT '',content_sha256 CHAR(64) NOT NULL,
 reference_json JSON NOT NULL,quality_status VARCHAR(32) NOT NULL DEFAULT 'REVIEW_REQUIRED',
 label VARCHAR(255) NULL,revision_note VARCHAR(500) NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_trajectory_content(wellbore_id,trajectory_kind,content_sha256),
 FOREIGN KEY(survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
 FOREIGN KEY(wellbore_id) REFERENCES drilling_wellbore(wellbore_id), CHECK(trajectory_kind IN ('DESIGN','ACTUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_trajectory_head (
 wellbore_id BIGINT NOT NULL,trajectory_kind VARCHAR(16) NOT NULL,survey_version_id BIGINT NOT NULL,
 PRIMARY KEY(wellbore_id,trajectory_kind),FOREIGN KEY(wellbore_id) REFERENCES drilling_wellbore(wellbore_id),
 FOREIGN KEY(survey_version_id) REFERENCES drilling_trajectory_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_neighbor_selection (
 field_id BIGINT NOT NULL,reference_wellbore_id BIGINT NOT NULL,neighbor_wellbore_id BIGINT NOT NULL,
 included BOOLEAN NOT NULL DEFAULT TRUE,version_policy VARCHAR(16) NOT NULL DEFAULT 'CURRENT_ACTUAL',pinned_version_id BIGINT NULL,
 PRIMARY KEY(field_id,reference_wellbore_id,neighbor_wellbore_id),
 FOREIGN KEY(field_id) REFERENCES drilling_field(field_id),
 FOREIGN KEY(reference_wellbore_id) REFERENCES drilling_wellbore(wellbore_id),
 FOREIGN KEY(neighbor_wellbore_id) REFERENCES drilling_wellbore(wellbore_id),
 FOREIGN KEY(pinned_version_id) REFERENCES drilling_survey_version(survey_version_id),
 CHECK(reference_wellbore_id<>neighbor_wellbore_id),CHECK(version_policy IN ('CURRENT_ACTUAL','CURRENT_DESIGN','PINNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_source_document (
 source_id BIGINT AUTO_INCREMENT PRIMARY KEY,file_id BIGINT NOT NULL,field_id BIGINT NOT NULL,
 source_path VARCHAR(1024) NOT NULL,source_key CHAR(64) NOT NULL,source_format VARCHAR(32) NOT NULL,
 parse_status VARCHAR(32) NOT NULL,metadata_json JSON NULL,diagnostics_json JSON NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_source(field_id,source_key),FOREIGN KEY(file_id) REFERENCES data_file(file_id),FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_source_extraction (
 extraction_id BIGINT AUTO_INCREMENT PRIMARY KEY,source_id BIGINT NOT NULL,locator VARCHAR(255) NOT NULL,
 payload_json JSON NOT NULL,import_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',survey_version_id BIGINT NULL,
 UNIQUE KEY uk_extraction(source_id,locator),FOREIGN KEY(source_id) REFERENCES drilling_source_document(source_id),
 FOREIGN KEY(survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_source_review (
 review_id BIGINT AUTO_INCREMENT PRIMARY KEY, field_id BIGINT NOT NULL, source_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', proposed_json JSON NOT NULL, confirmed_json JSON NULL,
 reviewed_by VARCHAR(64) NULL, reviewed_at DATETIME NULL, review_note VARCHAR(500) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_source_review(source_id),
 KEY idx_source_review_field(field_id,status), FOREIGN KEY(field_id) REFERENCES drilling_field(field_id),
 FOREIGN KEY(source_id) REFERENCES drilling_source_document(source_id), CHECK(status IN ('PENDING','CONFIRMED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing archive entries are confirmed during bootstrap.  The Java upload
-- endpoint creates PENDING rows for files imported after bootstrap.
INSERT IGNORE INTO drilling_source_review(field_id,source_id,status,proposed_json,confirmed_json,reviewed_by,reviewed_at,review_note)
 SELECT field_id,source_id,'CONFIRMED',COALESCE(metadata_json,JSON_OBJECT()),COALESCE(metadata_json,JSON_OBJECT()),'system-import',NOW(),'既有工程资料已完成确认；新增资料导入后需人工确认。' FROM drilling_source_document;
CREATE TABLE IF NOT EXISTS drilling_import_batch (
 batch_id BIGINT AUTO_INCREMENT PRIMARY KEY,field_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 manifest_json JSON NOT NULL,summary_json JSON NULL,create_by VARCHAR(64),create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engine_confirmation (
 confirmation_id BIGINT AUTO_INCREMENT PRIMARY KEY,field_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 session_hash CHAR(64) NOT NULL,config_hash CHAR(64) NOT NULL,config_version INT NOT NULL,snapshot_json JSON NOT NULL,
 confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_confirmation(field_id,user_id,session_hash,config_hash),FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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

INSERT IGNORE INTO drilling_well_catalog(well_id,field_id,identity_key)
 SELECT w.well_id,w.field_id,w.well_code FROM drilling_well w WHERE w.status<>'ARCHIVED' AND w.field_id IS NOT NULL;
INSERT IGNORE INTO drilling_wellbore(well_id,wellbore_code,wellbore_name,engineering_status)
 SELECT w.well_id,'MAIN',w.well_name,CASE WHEN w.status='COMPLETED' THEN 'COMPLETED' WHEN w.status='DRILLING' THEN 'DRILLING' ELSE 'UNKNOWN' END FROM drilling_well w
 WHERE w.status<>'ARCHIVED' AND NOT EXISTS(SELECT 1 FROM drilling_wellbore b WHERE b.well_id=w.well_id);
INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id)
 SELECT w.field_id,b.wellbore_id FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id WHERE w.status<>'ARCHIVED' AND w.field_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS drilling_trajectory_revision (
 survey_version_id BIGINT PRIMARY KEY,revision_type VARCHAR(16) NOT NULL,parent_version_id BIGINT NULL,
 changed_from_md DECIMAL(16,6) NULL,changed_to_md DECIMAL(16,6) NULL,summary_json JSON NULL,
 FOREIGN KEY(survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
 FOREIGN KEY(parent_version_id) REFERENCES drilling_survey_version(survey_version_id),
 CHECK(revision_type IN ('FULL','INCREMENTAL','RESURVEY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_workspace_draft (
 field_id BIGINT NOT NULL,user_id BIGINT NOT NULL,task_type VARCHAR(32) NOT NULL,title VARCHAR(128) NOT NULL,
 payload_json JSON NOT NULL,update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(field_id,user_id,task_type),FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
