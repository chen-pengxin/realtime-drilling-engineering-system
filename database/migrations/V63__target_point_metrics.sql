SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_target_point' AND column_name='metrics_json')=0, 'ALTER TABLE drilling_trajectory_target_point ADD COLUMN metrics_json JSON NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
