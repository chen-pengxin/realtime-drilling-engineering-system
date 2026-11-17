-- System-wide reference material is not an engineering hierarchy node.
-- The original file is still stored in data_file so its bytes and hash are
-- handled by the same immutable storage rules as engineering documents.
CREATE TABLE IF NOT EXISTS drilling_reference_material (
    reference_id BIGINT NOT NULL AUTO_INCREMENT,
    material_code VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    standard_no VARCHAR(128) NULL,
    material_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    file_id BIGINT NOT NULL,
    page_count INT NULL,
    appendix_tables_json JSON NULL,
    source_note VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reference_id),
    UNIQUE KEY uk_reference_material_code (material_code),
    UNIQUE KEY uk_reference_material_file (file_id),
    CONSTRAINT fk_reference_material_file FOREIGN KEY (file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参考资料，不属于工程层级';

-- Keep the migration ledger authoritative for both fresh and existing
-- databases.  INSERT IGNORE makes this safe to run again after a deployment
-- restart while preserving an audited record already written by Compose.
INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES
    ('V43', 'V43__reference_material_registry.sql', 'APPLIED', 'migration', '系统级参考资料原件与附录表目录');

