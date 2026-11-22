SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_target_point' AND column_name='remark')=0, 'ALTER TABLE drilling_trajectory_target_point ADD COLUMN remark VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V62','V62__trajectory_target_point_notes.sql','APPLIED','application','补充实钻轨迹入靶数据备注字段');
