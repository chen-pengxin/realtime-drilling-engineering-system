-- Automatic document parsing is a proposal.  A human must confirm the
-- proposed engineering identity and neighbour bindings before new data can
-- become an evaluation input.
CREATE TABLE IF NOT EXISTS drilling_source_review (
 review_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 source_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 proposed_json JSON NOT NULL,
 confirmed_json JSON NULL,
 reviewed_by VARCHAR(64) NULL,
 reviewed_at DATETIME NULL,
 review_note VARCHAR(500) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_source_review(source_id),
 KEY idx_source_review_project(project_id,status),
 FOREIGN KEY(project_id) REFERENCES drilling_project(project_id),
 FOREIGN KEY(source_id) REFERENCES drilling_source_document(source_id),
 CHECK(status IN ('PENDING','CONFIRMED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The records already present when this migration is installed are the
-- existing engineering archive.  They are accepted as confirmed data.  New
-- records created by the source upload APIs are inserted as PENDING and open
-- the confirmation dialog before they can produce a trajectory version.
INSERT IGNORE INTO drilling_source_review(project_id,source_id,status,proposed_json,confirmed_json,reviewed_by,reviewed_at,review_note)
 SELECT project_id,source_id,'CONFIRMED',COALESCE(metadata_json,JSON_OBJECT()),COALESCE(metadata_json,JSON_OBJECT()),'system-import',NOW(),'既有工程资料已完成确认；新增资料导入后需人工确认。'
 FROM drilling_source_document;
