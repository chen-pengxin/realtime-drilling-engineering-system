-- Engineering policies are shared; source coordinate/tool budgets stay in immutable versions.
CREATE TABLE IF NOT EXISTS drilling_engineering_config (
 config_version BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 algorithm_defaults_json JSON NOT NULL,
 create_by VARCHAR(64) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE sys_menu SET menu_name='工程参数管理', remark='工程统一参数及井眼版本参数'
 WHERE url='/drilling/basic/config' AND menu_name<>'工程参数管理';
INSERT INTO drilling_engineering_config(algorithm_defaults_json,create_by)
 SELECT JSON_SET(c.algorithm_defaults_json,'$.prediction.timeoutSeconds',1,
 '$.planning.taskTimeoutSeconds',30,'$.planning.minDoglegDegPer30m',0,
 '$.planning.realtimeBudgetSeconds',30,
 '$.planning.realtimeExecutionTimeoutSeconds',30,
 '$.planning.nativeOptions.solver.max_seconds_per_run',30,
 '$.planning.nativeOptions.solver.optimization_starts',1), 'engineering-scope-migration'
 FROM drilling_field_config c
 WHERE c.status='0' AND c.effective_to IS NULL AND JSON_CONTAINS_PATH(c.algorithm_defaults_json,'one','$.errorModel.settings')
 AND NOT EXISTS(SELECT 1 FROM drilling_engineering_config)
 ORDER BY c.create_time DESC,c.field_id,c.config_version DESC LIMIT 1;
UPDATE drilling_engineering_config
   SET algorithm_defaults_json=JSON_REMOVE(JSON_SET(algorithm_defaults_json,
       '$.prediction.timeoutSeconds',1,
       '$.planning.taskTimeoutSeconds',30,
       '$.planning.minDoglegDegPer30m',0,
       '$.planning.realtimeBudgetSeconds',30,
       '$.planning.realtimeExecutionTimeoutSeconds',30,
       '$.planning.nativeOptions.solver.max_seconds_per_run',30,
       '$.planning.nativeOptions.solver.optimization_starts',1),
       '$.planning.realtimeSolverBudgetSeconds')
 WHERE create_by='engineering-scope-migration';
UPDATE sys_menu SET menu_name='工程参数管理',remark='工程统一参数及井眼版本参数'
 WHERE url='/drilling/basic/config' AND menu_name<>'工程参数管理';
UPDATE sys_menu SET menu_name='工程项目管理',remark='区块、井场、井、井眼及其资料与版本管理'
 WHERE url='/drilling/basic/catalog' AND menu_name<>'工程项目管理';
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V57','V57__engineering_project_management_labels.sql','APPLIED','application','工程项目管理与工程参数管理菜单名称');

-- Existing trajectory files receive the generated display name on startup;
-- original_name remains unchanged for preview and download.
UPDATE data_file f
JOIN drilling_source_document s ON s.file_id=f.file_id
JOIN drilling_engineering_document_file df ON df.source_id=s.source_id
JOIN drilling_engineering_document d ON d.document_id=df.document_id
JOIN drilling_engineering_node n ON n.node_id=d.node_id
JOIN drilling_well w ON w.well_id=n.well_id
SET f.display_name=CONCAT(w.well_name,'_',CASE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),CASE n.node_type WHEN 'PLAN' THEN 'DESIGN' WHEN 'SURVEY' THEN 'ACTUAL' END) WHEN 'DESIGN' THEN '设计方案' WHEN 'ACTUAL' THEN '实测测斜' ELSE '工程资料' END,'_',df.edition_no)
WHERE d.document_role IN ('TRAJECTORY_DATA','TRAJECTORY_REPORT');
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V60','V60__trajectory_system_display_names.sql','APPLIED','application','统一轨迹资料系统名称，原始文件名不变');

-- V60/V61 are also applied at startup; use information_schema guards because
-- the MySQL version used by the project does not support ADD COLUMN IF NOT EXISTS.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='location')=0, 'ALTER TABLE drilling_block ADD COLUMN location VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;

-- V64: fields used by the block/field base-information forms.  Soft-delete
-- status remains independent from construction status and enabled state.
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
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='operator_name')=0, 'ALTER TABLE drilling_block ADD COLUMN operator_name VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='structural_position')=0, 'ALTER TABLE drilling_block ADD COLUMN structural_position VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='remark')=0, 'ALTER TABLE drilling_block ADD COLUMN remark VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='source_reference')=0, 'ALTER TABLE drilling_block ADD COLUMN source_reference VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='location')=0, 'ALTER TABLE drilling_field ADD COLUMN location VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='structural_position')=0, 'ALTER TABLE drilling_field ADD COLUMN structural_position VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='remark')=0, 'ALTER TABLE drilling_field ADD COLUMN remark VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_field' AND column_name='source_reference')=0, 'ALTER TABLE drilling_field ADD COLUMN source_reference VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_well' AND column_name='location')=0, 'ALTER TABLE drilling_well ADD COLUMN location VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_well' AND column_name='operator_name')=0, 'ALTER TABLE drilling_well ADD COLUMN operator_name VARCHAR(128) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_well' AND column_name='structural_position')=0, 'ALTER TABLE drilling_well ADD COLUMN structural_position VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_well' AND column_name='remark')=0, 'ALTER TABLE drilling_well ADD COLUMN remark VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_well' AND column_name='source_reference')=0, 'ALTER TABLE drilling_well ADD COLUMN source_reference VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
CREATE TABLE IF NOT EXISTS drilling_trajectory_target_point (
 target_point_id BIGINT PRIMARY KEY AUTO_INCREMENT, field_id BIGINT NOT NULL, wellbore_id BIGINT NOT NULL,
 survey_version_id BIGINT NOT NULL, target_no VARCHAR(64) NOT NULL, md DECIMAL(16,6) NOT NULL,
 north DECIMAL(16,6) NULL, east DECIMAL(16,6) NULL, tvd DECIMAL(16,6) NULL,
 inclination DECIMAL(10,6) NULL, azimuth DECIMAL(10,6) NULL, tolerance DECIMAL(16,6) NULL, remark VARCHAR(500) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', create_by VARCHAR(64) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NULL,
 UNIQUE KEY uk_trajectory_target_point (survey_version_id,target_no),
 KEY idx_trajectory_target_point (field_id,wellbore_id,survey_version_id,status),
 CONSTRAINT fk_target_point_version FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
 CONSTRAINT fk_target_point_bore FOREIGN KEY (wellbore_id) REFERENCES drilling_wellbore(wellbore_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V61','V61__trajectory_editable_points_and_engineering_fields.sql','APPLIED','application','工程基础字段、轨迹靶点及设计节点编辑所需结构');

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_target_point' AND column_name='metrics_json')=0, 'ALTER TABLE drilling_trajectory_target_point ADD COLUMN metrics_json JSON NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_target_point' AND column_name='remark')=0, 'ALTER TABLE drilling_trajectory_target_point ADD COLUMN remark VARCHAR(500) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
