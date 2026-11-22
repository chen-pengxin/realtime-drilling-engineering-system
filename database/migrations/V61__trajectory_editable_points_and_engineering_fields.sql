-- 工程项目管理补充字段与轨迹靶点数据。
-- 原始文件、解析结果、轨迹版本仍分别保留；本表只保存结构化靶点。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_block' AND column_name='location')=0, 'ALTER TABLE drilling_block ADD COLUMN location VARCHAR(255) NULL', 'SELECT 1');
PREPARE drilling_schema_stmt FROM @ddl; EXECUTE drilling_schema_stmt; DEALLOCATE PREPARE drilling_schema_stmt;
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
  target_point_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  field_id BIGINT NOT NULL,
  wellbore_id BIGINT NOT NULL,
  survey_version_id BIGINT NOT NULL,
  target_no VARCHAR(64) NOT NULL,
  md DECIMAL(16,6) NOT NULL,
  north DECIMAL(16,6) NULL,
  east DECIMAL(16,6) NULL,
  tvd DECIMAL(16,6) NULL,
  inclination DECIMAL(10,6) NULL,
  azimuth DECIMAL(10,6) NULL,
  tolerance DECIMAL(16,6) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  create_by VARCHAR(64) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NULL,
  UNIQUE KEY uk_trajectory_target_point (survey_version_id,target_no),
  KEY idx_trajectory_target_point (field_id,wellbore_id,survey_version_id,status),
  CONSTRAINT fk_target_point_version FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
  CONSTRAINT fk_target_point_bore FOREIGN KEY (wellbore_id) REFERENCES drilling_wellbore(wellbore_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实钻轨迹靶点';

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V61','V61__trajectory_editable_points_and_engineering_fields.sql','APPLIED','application','工程基础字段、轨迹靶点及设计节点编辑所需结构');
