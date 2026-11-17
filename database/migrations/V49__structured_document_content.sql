-- Source content is separate from approved calculation versions. A parser
-- revision appends a snapshot; it never changes an original or a trajectory.
CREATE TABLE IF NOT EXISTS drilling_reference_material (
 reference_id BIGINT AUTO_INCREMENT PRIMARY KEY,material_code VARCHAR(128) NOT NULL,
 title VARCHAR(255) NOT NULL,standard_no VARCHAR(128) NULL,material_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
 file_id BIGINT NOT NULL,page_count INT NULL,appendix_tables_json JSON NULL,source_note VARCHAR(500) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',create_by VARCHAR(64) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_reference_material_code(material_code),UNIQUE KEY uk_reference_material_file(file_id),
 FOREIGN KEY(file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_document_parse (
 parse_id BIGINT AUTO_INCREMENT PRIMARY KEY,source_id BIGINT NULL,reference_id BIGINT NULL,
 parser_version VARCHAR(80) NOT NULL,source_sha256 CHAR(64) NOT NULL,content_sha256 CHAR(64) NOT NULL,
 parse_status VARCHAR(24) NOT NULL,manifest_json JSON NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_source_parse(source_id,parser_version,content_sha256),
 UNIQUE KEY uk_reference_parse(reference_id,parser_version,content_sha256),
 FOREIGN KEY(source_id) REFERENCES drilling_source_document(source_id),
 FOREIGN KEY(reference_id) REFERENCES drilling_reference_material(reference_id),
 CHECK((source_id IS NOT NULL AND reference_id IS NULL) OR (source_id IS NULL AND reference_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变文档解析快照';
CREATE TABLE IF NOT EXISTS drilling_document_section (
 section_id BIGINT AUTO_INCREMENT PRIMARY KEY,parse_id BIGINT NOT NULL,locator VARCHAR(500) NOT NULL,
 section_order INT NOT NULL,title VARCHAR(1000) NOT NULL,content_type VARCHAR(16) NOT NULL,
 semantic_type VARCHAR(40) NOT NULL,payload_json JSON NOT NULL,
 UNIQUE KEY uk_parse_section(parse_id,locator),KEY idx_parse_order(parse_id,section_order),
 FOREIGN KEY(parse_id) REFERENCES drilling_document_parse(parse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档正文表格插图及来源定位';
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
 VALUES('V49','V49__structured_document_content.sql','APPLIED','application','保存多源文档全部正文、表格、单位、合并表头、插图位置及解析版次');
