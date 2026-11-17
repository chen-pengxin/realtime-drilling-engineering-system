-- Display names are mutable UI metadata, independent of immutable originals.
SET @display_name_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='data_file' AND column_name='display_name');
SET @display_name_sql = IF(@display_name_exists=0,'ALTER TABLE data_file ADD COLUMN display_name VARCHAR(255) NULL AFTER original_name','SELECT 1');
PREPARE display_name_stmt FROM @display_name_sql;
EXECUTE display_name_stmt;
DEALLOCATE PREPARE display_name_stmt;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes) VALUES('V48','V48__independent_file_display_names.sql','APPLIED','application','Keep original filenames unchanged; display names are independent UI metadata');
