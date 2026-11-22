-- 区块、井场基础资料表单字段。status 仍仅用于软删除；施工状态和启用状态单独保存。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='province')=0, 'ALTER TABLE drilling_block ADD COLUMN province VARCHAR(64) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='authorized_user')=0, 'ALTER TABLE drilling_block ADD COLUMN authorized_user VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='coordinate_system')=0, 'ALTER TABLE drilling_block ADD COLUMN coordinate_system VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='wellhead_n')=0, 'ALTER TABLE drilling_block ADD COLUMN wellhead_n DECIMAL(16,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='wellhead_e')=0, 'ALTER TABLE drilling_block ADD COLUMN wellhead_e DECIMAL(16,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='magnetic_declination')=0, 'ALTER TABLE drilling_block ADD COLUMN magnetic_declination DECIMAL(10,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='meridian_convergence')=0, 'ALTER TABLE drilling_block ADD COLUMN meridian_convergence DECIMAL(10,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='construction_status')=0, 'ALTER TABLE drilling_block ADD COLUMN construction_status VARCHAR(32) NOT NULL DEFAULT ''UNPLANNED''', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='enabled')=0, 'ALTER TABLE drilling_block ADD COLUMN enabled CHAR(1) NOT NULL DEFAULT ''1''', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='province')=0, 'ALTER TABLE drilling_field ADD COLUMN province VARCHAR(64) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='authorized_user')=0, 'ALTER TABLE drilling_field ADD COLUMN authorized_user VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='coordinate_system')=0, 'ALTER TABLE drilling_field ADD COLUMN form_coordinate_system VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='wellhead_n')=0, 'ALTER TABLE drilling_field ADD COLUMN wellhead_n DECIMAL(16,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='wellhead_e')=0, 'ALTER TABLE drilling_field ADD COLUMN wellhead_e DECIMAL(16,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='magnetic_declination')=0, 'ALTER TABLE drilling_field ADD COLUMN magnetic_declination DECIMAL(10,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='meridian_convergence')=0, 'ALTER TABLE drilling_field ADD COLUMN meridian_convergence DECIMAL(10,6) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='construction_status')=0, 'ALTER TABLE drilling_field ADD COLUMN construction_status VARCHAR(32) NOT NULL DEFAULT ''UNPLANNED''', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='enabled')=0, 'ALTER TABLE drilling_field ADD COLUMN enabled CHAR(1) NOT NULL DEFAULT ''1''', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V64','V64__catalog_form_fields.sql','APPLIED','application','区块与井场表单字段和必填资料状态');
