-- Source engineering hierarchy and business revisions. Legacy snapshots and
-- task IDs are retained; files are representations, not trajectory revisions.
CREATE TABLE IF NOT EXISTS drilling_engineering_node (
 node_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 node_key CHAR(64) NOT NULL UNIQUE,
 field_id BIGINT NOT NULL,
 parent_id BIGINT NULL,
 node_type VARCHAR(16) NOT NULL,
 node_name VARCHAR(255) NOT NULL,
 node_code VARCHAR(128) NULL,
 well_id BIGINT NULL,
 wellbore_id BIGINT NULL,
 entity_kind VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
 properties_json JSON NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 row_version INT NOT NULL DEFAULT 1,
 create_by VARCHAR(64) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NULL,
 FOREIGN KEY(field_id) REFERENCES drilling_field(field_id),
 FOREIGN KEY(parent_id) REFERENCES drilling_engineering_node(node_id),
 FOREIGN KEY(well_id) REFERENCES drilling_well(well_id),
 FOREIGN KEY(wellbore_id) REFERENCES drilling_wellbore(wellbore_id),
 KEY idx_eng_parent(parent_id), KEY idx_eng_field(field_id,node_type),
 CHECK(node_type IN ('BLOCK','FIELD','WELL','WELLBORE','PLAN','SURVEY','INBOX')),
 CHECK(entity_kind IN ('WELL','PLANNED_RELIEF','UNKNOWN')),
 CHECK(status IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_revision (
 revision_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 node_id BIGINT NOT NULL,
 revision_key CHAR(64) NOT NULL,
 business_revision VARCHAR(128) NOT NULL,
 outcome_name VARCHAR(255) NOT NULL,
 phase VARCHAR(24) NOT NULL,
 usage_state VARCHAR(24) NOT NULL DEFAULT 'HISTORICAL',
 parent_revision_id BIGINT NULL,
 revision_note VARCHAR(1000) NULL,
 source_date DATE NULL,
 properties_json JSON NULL,
 row_version INT NOT NULL DEFAULT 1,
 create_by VARCHAR(64) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NULL,
 UNIQUE KEY uk_eng_revision(node_id,revision_key),
 FOREIGN KEY(node_id) REFERENCES drilling_engineering_node(node_id),
 FOREIGN KEY(parent_revision_id) REFERENCES drilling_engineering_revision(revision_id),
 CHECK(usage_state IN ('DRAFT','HISTORICAL','ACTIVE','WITHDRAWN','FORBIDDEN','UNASSIGNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_revision_data (
 revision_id BIGINT NOT NULL,
 survey_version_id BIGINT NOT NULL,
 data_label VARCHAR(255) NOT NULL,
 PRIMARY KEY(revision_id,survey_version_id),
 FOREIGN KEY(revision_id) REFERENCES drilling_engineering_revision(revision_id),
 FOREIGN KEY(survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_head (
 node_id BIGINT PRIMARY KEY,
 revision_id BIGINT NOT NULL,
 survey_version_id BIGINT NULL,
 activated_by VARCHAR(64) NULL,
 activated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(node_id) REFERENCES drilling_engineering_node(node_id),
 FOREIGN KEY(revision_id) REFERENCES drilling_engineering_revision(revision_id),
 FOREIGN KEY(survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_document (
 document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 document_key CHAR(64) NOT NULL UNIQUE,
 node_id BIGINT NOT NULL,
 revision_id BIGINT NULL,
 document_role VARCHAR(32) NOT NULL,
 document_title VARCHAR(255) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(node_id) REFERENCES drilling_engineering_node(node_id),
 FOREIGN KEY(revision_id) REFERENCES drilling_engineering_revision(revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_document_file (
 document_file_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 document_id BIGINT NOT NULL,
 source_id BIGINT NOT NULL,
 edition_no INT NOT NULL,
 representation_format VARCHAR(32) NOT NULL,
 file_label VARCHAR(255) NOT NULL,
 source_date DATE NULL,
 usage_state VARCHAR(24) NOT NULL DEFAULT 'HISTORICAL',
 properties_json JSON NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_eng_document_source(document_id,source_id),
 FOREIGN KEY(document_id) REFERENCES drilling_engineering_document(document_id),
 FOREIGN KEY(source_id) REFERENCES drilling_source_document(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_engineering_migration (
 migration_key VARCHAR(128) PRIMARY KEY,
 manifest_json JSON NOT NULL,
 applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Dataset names are source metadata only.  The engineering tree is created
-- by the importer under the selected block and field and never creates a
-- synthetic source node.

-- Fill only missing/null file types from the explicit engineering result node.
UPDATE drilling_engineering_document_file df
 JOIN drilling_engineering_document d ON d.document_id=df.document_id
 JOIN drilling_engineering_node n ON n.node_id=d.node_id
 SET df.properties_json=JSON_SET(COALESCE(df.properties_json,JSON_OBJECT()),'$.trajectory_kind',IF(n.node_type='PLAN','DESIGN','ACTUAL'))
 WHERE d.document_role IN ('TRAJECTORY_DATA','TRAJECTORY_REPORT') AND n.node_type IN ('PLAN','SURVEY')
 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),'null') IN ('','null');

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes) VALUES('V47','V47__separate_trajectory_numbers_and_file_types.sql','APPLIED','application','Independent design and actual version numbering; explicit file classification');
