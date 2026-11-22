ALTER TABLE drilling_target ADD COLUMN well_id BIGINT NULL;
ALTER TABLE drilling_target ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE drilling_target ADD KEY idx_target_field_well (field_id, well_id, status);
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V59','V59__wellbore_targets.sql','APPLIED','application','为井眼工程项目管理提供靶区数据版本管理字段');
