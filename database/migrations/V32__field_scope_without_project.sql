-- V32: 工程归属统一到区块 -> 井场 -> 井 -> 井眼。
-- 旧库中的作业范围只作为迁移来源，业务代码从本版本开始只读取 field_id。
-- 为了允许在线升级，本迁移保留旧列一段时间；旧列不再参与接口、权限或工程树。

DROP PROCEDURE IF EXISTS drilling_add_column_v32;
DELIMITER $$
CREATE PROCEDURE drilling_add_column_v32(IN p_table VARCHAR(128), IN p_column VARCHAR(128), IN p_definition VARCHAR(500))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=p_table)
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @v32_sql=CONCAT('ALTER TABLE `',REPLACE(p_table,'`','``'),'` ADD COLUMN `',REPLACE(p_column,'`','``'),'` ',p_definition);
    PREPARE v32_stmt FROM @v32_sql; EXECUTE v32_stmt; DEALLOCATE PREPARE v32_stmt;
  END IF;
END$$
DELIMITER ;
CALL drilling_add_column_v32('drilling_field','coordinate_system','VARCHAR(128) NOT NULL DEFAULT ''LOCAL_ENU''');
CALL drilling_add_column_v32('drilling_field','length_unit','VARCHAR(16) NOT NULL DEFAULT ''m''');
CALL drilling_add_column_v32('drilling_field','angle_unit','VARCHAR(16) NOT NULL DEFAULT ''deg''');
CALL drilling_add_column_v32('drilling_field','tvd_positive','CHAR(1) NOT NULL DEFAULT ''D''');
CALL drilling_add_column_v32('drilling_field','default_sf_threshold','DECIMAL(8,3) NOT NULL DEFAULT 1.500');
CALL drilling_add_column_v32('drilling_field','config_json','JSON NULL');
CALL drilling_add_column_v32('drilling_field','update_by','VARCHAR(64) NULL');
CALL drilling_add_column_v32('drilling_field','update_time','DATETIME NULL');

CREATE TABLE IF NOT EXISTS drilling_field_config (
  field_id BIGINT NOT NULL,
  config_version INT NOT NULL,
  coordinate_system VARCHAR(128) NOT NULL DEFAULT 'LOCAL_ENU',
  north_reference VARCHAR(32) NOT NULL DEFAULT 'TRUE_NORTH',
  length_unit VARCHAR(16) NOT NULL DEFAULT 'm',
  angle_unit VARCHAR(16) NOT NULL DEFAULT 'deg',
  tvd_positive CHAR(1) NOT NULL DEFAULT 'D',
  origin_north DECIMAL(16,6) NOT NULL DEFAULT 0,
  origin_east DECIMAL(16,6) NOT NULL DEFAULT 0,
  origin_tvd DECIMAL(16,6) NOT NULL DEFAULT 0,
  magnetic_declination DECIMAL(10,6) NOT NULL DEFAULT 0,
  meridian_convergence DECIMAL(10,6) NOT NULL DEFAULT 0,
  algorithm_defaults_json JSON NOT NULL,
  effective_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_to DATETIME NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  create_by VARCHAR(64) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY(field_id, config_version),
  KEY idx_field_config_current(field_id,status,effective_to),
  CONSTRAINT fk_field_config_field FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS drilling_field_organization (
  field_id BIGINT NOT NULL PRIMARY KEY,
  dept_id BIGINT NULL,
  owner_user_id BIGINT NULL,
  update_by VARCHAR(64) DEFAULT '',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_field_department(dept_id), KEY idx_field_owner(owner_user_id),
  CONSTRAINT fk_field_org_field FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS drilling_field_member (
  field_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role_code VARCHAR(24) NOT NULL DEFAULT 'VIEWER',
  status CHAR(1) NOT NULL DEFAULT '0',
  create_by VARCHAR(64) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY(field_id,user_id),
  CONSTRAINT fk_field_member_field FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS drilling_field_wellbore (
  field_id BIGINT NOT NULL,
  wellbore_id BIGINT NOT NULL,
  purpose VARCHAR(16) NOT NULL DEFAULT 'SUBJECT',
  PRIMARY KEY(field_id,wellbore_id),
  CONSTRAINT fk_field_wellbore_field FOREIGN KEY(field_id) REFERENCES drilling_field(field_id),
  CONSTRAINT fk_field_wellbore_bore FOREIGN KEY(wellbore_id) REFERENCES drilling_wellbore(wellbore_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Add the canonical scope column to every table that previously carried the
-- removed range identifier. Existing tables without such a column are left
-- untouched; this makes the script safe for installations with optional
-- centers enabled at different times.
CALL drilling_add_column_v32('drilling_well','field_id','BIGINT NULL');
CALL drilling_add_column_v32('data_file','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_target','field_id','BIGINT NULL');
CALL drilling_add_column_v32('anti_collision_task','field_id','BIGINT NULL');
CALL drilling_add_column_v32('trajectory_plan','field_id','BIGINT NULL');
CALL drilling_add_column_v32('prediction_task','field_id','BIGINT NULL');
CALL drilling_add_column_v32('training_dataset','field_id','BIGINT NULL');
CALL drilling_add_column_v32('training_template','field_id','BIGINT NULL');
CALL drilling_add_column_v32('model_version','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_data_quality_issue','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_realtime_connector','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_alarm','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_audit_log','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_task_event','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_realtime_ingest_event','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_center_snapshot','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_center_execution','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_center_origin','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_center_report','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_import_batch','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_engine_confirmation','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_workspace_draft','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_source_document','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_source_review','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_neighbor_selection','field_id','BIGINT NULL');
CALL drilling_add_column_v32('drilling_engineering_node','field_id','BIGINT NULL');

-- The legacy table is only read here to resolve the old rows into their
-- owning field. No new row receives a legacy range identifier afterwards.
UPDATE drilling_well w JOIN drilling_project p ON p.project_id=w.project_id SET w.field_id=p.field_id WHERE w.field_id IS NULL;
UPDATE data_file f JOIN drilling_project p ON p.project_id=f.project_id SET f.field_id=p.field_id WHERE f.field_id IS NULL;
UPDATE drilling_target x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE anti_collision_task x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE trajectory_plan x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE prediction_task x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE training_dataset x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE training_template x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE model_version x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_data_quality_issue x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_realtime_connector x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_alarm x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_audit_log x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_task_event x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_realtime_ingest_event x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_center_snapshot x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_center_execution x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_center_origin x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_center_report x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_import_batch x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_engine_confirmation x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_workspace_draft x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_source_document x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_source_review x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_neighbor_selection x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_engineering_node x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;

INSERT IGNORE INTO drilling_field_config(field_id,config_version,coordinate_system,north_reference,length_unit,angle_unit,tvd_positive,origin_north,origin_east,origin_tvd,magnetic_declination,meridian_convergence,algorithm_defaults_json,status,create_by)
SELECT p.field_id,c.config_version,c.coordinate_system,c.north_reference,c.length_unit,c.angle_unit,c.tvd_positive,c.origin_north,c.origin_east,c.origin_tvd,c.magnetic_declination,c.meridian_convergence,c.algorithm_defaults_json,c.status,c.create_by
FROM drilling_project_config c JOIN drilling_project p ON p.project_id=c.project_id;
INSERT IGNORE INTO drilling_field_organization(field_id,dept_id,owner_user_id,update_by,update_time)
SELECT p.field_id,o.dept_id,o.owner_user_id,o.update_by,o.update_time FROM drilling_project_organization o JOIN drilling_project p ON p.project_id=o.project_id;
INSERT IGNORE INTO drilling_field_member(field_id,user_id,role_code,status,create_by,create_time,update_by,update_time)
SELECT p.field_id,m.user_id,m.role_code,m.status,m.create_by,m.create_time,m.update_by,m.update_time FROM drilling_project_member m JOIN drilling_project p ON p.project_id=m.project_id;
INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id,purpose)
SELECT p.field_id,x.wellbore_id,x.purpose FROM drilling_project_wellbore x JOIN drilling_project p ON p.project_id=x.project_id;

UPDATE drilling_field f JOIN drilling_field_config c ON c.field_id=f.field_id AND c.status='0' AND c.effective_to IS NULL
SET f.coordinate_system=c.coordinate_system,f.length_unit=c.length_unit,f.angle_unit=c.angle_unit,
    f.tvd_positive=c.tvd_positive,f.config_json=c.algorithm_defaults_json
WHERE NOT EXISTS (SELECT 1 FROM drilling_field_config newer WHERE newer.field_id=c.field_id AND newer.config_version>c.config_version);

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V32','V32__field_scope_without_project.sql','APPLIED','migration','工程业务范围统一为井场 field_id；旧范围字段仅保留迁移兼容');
DROP PROCEDURE drilling_add_column_v32;
