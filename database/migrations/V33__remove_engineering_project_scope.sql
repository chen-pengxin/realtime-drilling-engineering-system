-- V33: remove the former engineering project scope.
-- Engineering hierarchy: block -> field -> well -> wellbore.
-- Existing rows are resolved to field_id before the obsolete scope is dropped.

SET @old_foreign_key_checks := @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- MySQL does not support the MariaDB-only ADD COLUMN IF NOT EXISTS form.
-- Resolve the optional columns through metadata so the upgrade is repeatable.
DROP PROCEDURE IF EXISTS drilling_add_column_v33;
DELIMITER $$
CREATE PROCEDURE drilling_add_column_v33(IN p_table VARCHAR(128), IN p_column VARCHAR(128), IN p_definition VARCHAR(500))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=p_table)
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @v33_sql=CONCAT('ALTER TABLE `',REPLACE(p_table,'`','``'),'` ADD COLUMN `',REPLACE(p_column,'`','``'),'` ',p_definition);
    PREPARE v33_stmt FROM @v33_sql; EXECUTE v33_stmt; DEALLOCATE PREPARE v33_stmt;
  END IF;
END$$
DELIMITER ;
CALL drilling_add_column_v33('drilling_well','field_id','BIGINT NULL');
CALL drilling_add_column_v33('data_file','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_target','field_id','BIGINT NULL');
CALL drilling_add_column_v33('anti_collision_task','field_id','BIGINT NULL');
CALL drilling_add_column_v33('trajectory_plan','field_id','BIGINT NULL');
CALL drilling_add_column_v33('prediction_task','field_id','BIGINT NULL');
CALL drilling_add_column_v33('training_dataset','field_id','BIGINT NULL');
CALL drilling_add_column_v33('training_template','field_id','BIGINT NULL');
CALL drilling_add_column_v33('model_version','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_data_quality_issue','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_realtime_connector','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_alarm','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_audit_log','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_task_event','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_realtime_ingest_event','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_center_snapshot','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_center_execution','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_center_origin','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_center_report','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_import_batch','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_engine_confirmation','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_workspace_draft','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_source_document','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_source_review','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_neighbor_selection','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_engineering_node','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_system_audit','field_id','BIGINT NULL');
CALL drilling_add_column_v33('drilling_incremental_learning','field_id','BIGINT NULL');
DROP PROCEDURE drilling_add_column_v33;

-- Resolve old rows by the field attached to the old project row.
UPDATE drilling_well w JOIN drilling_project p ON p.project_id=w.project_id SET w.field_id=p.field_id WHERE w.field_id IS NULL;
UPDATE data_file x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
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
UPDATE drilling_system_audit x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;
UPDATE drilling_incremental_learning x JOIN drilling_project p ON p.project_id=x.project_id SET x.field_id=p.field_id WHERE x.field_id IS NULL;

-- Preserve configuration and access records under the field scope.
INSERT IGNORE INTO drilling_field_config
  (field_id,config_version,coordinate_system,north_reference,length_unit,angle_unit,tvd_positive,
   origin_north,origin_east,origin_tvd,magnetic_declination,meridian_convergence,
   algorithm_defaults_json,effective_from,effective_to,status,create_by,create_time,update_by,update_time)
SELECT p.field_id,c.config_version,c.coordinate_system,c.north_reference,c.length_unit,c.angle_unit,c.tvd_positive,
       c.origin_north,c.origin_east,c.origin_tvd,c.magnetic_declination,c.meridian_convergence,
       c.algorithm_defaults_json,c.effective_from,c.effective_to,c.status,c.create_by,c.create_time,c.update_by,c.update_time
  FROM drilling_project_config c JOIN drilling_project p ON p.project_id=c.project_id;
INSERT IGNORE INTO drilling_field_member
  (field_id,user_id,role_code,status,create_by,create_time,update_by,update_time)
SELECT p.field_id,m.user_id,m.role_code,m.status,m.create_by,m.create_time,m.update_by,m.update_time
  FROM drilling_project_member m JOIN drilling_project p ON p.project_id=m.project_id;
INSERT IGNORE INTO drilling_field_organization(field_id,dept_id,owner_user_id,update_by,update_time)
SELECT p.field_id,o.dept_id,o.owner_user_id,o.update_by,o.update_time
  FROM drilling_project_organization o JOIN drilling_project p ON p.project_id=o.project_id;
INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id,purpose)
SELECT p.field_id,x.wellbore_id,x.purpose
  FROM drilling_project_wellbore x JOIN drilling_project p ON p.project_id=x.project_id;

-- A field can have several old project rows. Merge their retained state before
-- changing primary keys so the resulting field-scoped records stay unique.
CREATE TEMPORARY TABLE v33_neighbor_selection AS
SELECT p.field_id,x.reference_wellbore_id,x.neighbor_wellbore_id,
       MAX(x.included) included,MAX(x.version_policy) version_policy,MAX(x.pinned_version_id) pinned_version_id
  FROM drilling_neighbor_selection x JOIN drilling_project p ON p.project_id=x.project_id
 GROUP BY p.field_id,x.reference_wellbore_id,x.neighbor_wellbore_id;
TRUNCATE TABLE drilling_neighbor_selection;
DROP PROCEDURE IF EXISTS drilling_drop_v33_fk;
DELIMITER $$
CREATE PROCEDURE drilling_drop_v33_fk(IN p_table VARCHAR(128), IN p_fk VARCHAR(128))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name=p_table AND constraint_name=p_fk AND constraint_type='FOREIGN KEY') THEN
    SET @v33_fk_sql=CONCAT('ALTER TABLE `',REPLACE(p_table,'`','``'),'` DROP FOREIGN KEY `',REPLACE(p_fk,'`','``'),'`');
    PREPARE v33_fk_stmt FROM @v33_fk_sql; EXECUTE v33_fk_stmt; DEALLOCATE PREPARE v33_fk_stmt;
  END IF;
END$$
DELIMITER ;
CALL drilling_drop_v33_fk('drilling_neighbor_selection','drilling_neighbor_selection_ibfk_1');
ALTER TABLE drilling_neighbor_selection DROP PRIMARY KEY,
  MODIFY field_id BIGINT NOT NULL,
  ADD PRIMARY KEY(field_id,reference_wellbore_id,neighbor_wellbore_id);
INSERT INTO drilling_neighbor_selection
  (field_id,reference_wellbore_id,neighbor_wellbore_id,included,version_policy,pinned_version_id)
SELECT field_id,reference_wellbore_id,neighbor_wellbore_id,included,version_policy,pinned_version_id
  FROM v33_neighbor_selection;
DROP TEMPORARY TABLE v33_neighbor_selection;

CREATE TEMPORARY TABLE v33_workspace_draft (
  field_id BIGINT NOT NULL, user_id BIGINT NOT NULL, task_type VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL, payload_json JSON NOT NULL, update_time DATETIME NULL
);
INSERT INTO v33_workspace_draft(field_id,user_id,task_type,title,payload_json,update_time)
SELECT field_id,user_id,task_type,title,payload_json,update_time
  FROM (SELECT x.field_id,x.user_id,x.task_type,x.title,x.payload_json,x.update_time,
               ROW_NUMBER() OVER(PARTITION BY x.field_id,x.user_id,x.task_type ORDER BY x.update_time DESC) row_rank
          FROM drilling_workspace_draft x WHERE x.field_id IS NOT NULL) ranked
 WHERE row_rank=1;
TRUNCATE TABLE drilling_workspace_draft;
CALL drilling_drop_v33_fk('drilling_workspace_draft','drilling_workspace_draft_ibfk_1');
ALTER TABLE drilling_workspace_draft DROP PRIMARY KEY,
  MODIFY field_id BIGINT NOT NULL,
  ADD PRIMARY KEY(field_id,user_id,task_type);
INSERT INTO drilling_workspace_draft(field_id,user_id,task_type,title,payload_json,update_time)
SELECT field_id,user_id,task_type,title,payload_json,update_time FROM v33_workspace_draft;
DROP TEMPORARY TABLE v33_workspace_draft;

CREATE TEMPORARY TABLE v33_incremental_learning (
  field_id BIGINT NOT NULL, policy_json JSON NOT NULL, state_json JSON NOT NULL,
  update_time DATETIME NULL
);
INSERT INTO v33_incremental_learning(field_id,policy_json,state_json,update_time)
SELECT field_id,policy_json,state_json,update_time
  FROM (SELECT x.field_id,x.policy_json,x.state_json,x.update_time,
               ROW_NUMBER() OVER(PARTITION BY x.field_id ORDER BY x.update_time DESC) row_rank
          FROM drilling_incremental_learning x WHERE x.field_id IS NOT NULL) ranked
 WHERE row_rank=1;
TRUNCATE TABLE drilling_incremental_learning;
-- The legacy primary key is project_id.  Drop it before removing that
-- column; MySQL does not allow a primary-key column to be dropped in place.
ALTER TABLE drilling_incremental_learning DROP PRIMARY KEY;
-- The legacy table may still contain a required project_id column.  It must
-- be removed before the field-scoped row is inserted; the generic cleanup at
-- the end of this migration also handles installations where this table was
-- already field-scoped.
DROP PROCEDURE IF EXISTS drilling_drop_incremental_project_column;
DELIMITER $$
CREATE PROCEDURE drilling_drop_incremental_project_column()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=DATABASE()
               AND table_name='drilling_incremental_learning'
               AND column_name='project_id') THEN
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema=DATABASE()
                 AND table_name='drilling_incremental_learning'
                 AND column_name='project_id'
                 AND index_name <> 'PRIMARY') THEN
      SET @v33_incremental_index=(SELECT index_name FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='drilling_incremental_learning'
          AND column_name='project_id' AND index_name <> 'PRIMARY' LIMIT 1);
      SET @v33_incremental_index_sql=CONCAT('ALTER TABLE drilling_incremental_learning DROP INDEX `',
        REPLACE(@v33_incremental_index,'`','``'),'`');
      PREPARE v33_incremental_index_stmt FROM @v33_incremental_index_sql;
      EXECUTE v33_incremental_index_stmt;
      DEALLOCATE PREPARE v33_incremental_index_stmt;
    END IF;
    ALTER TABLE drilling_incremental_learning DROP COLUMN project_id;
  END IF;
END$$
DELIMITER ;
CALL drilling_drop_incremental_project_column();
DROP PROCEDURE drilling_drop_incremental_project_column;
ALTER TABLE drilling_incremental_learning
  MODIFY field_id BIGINT NOT NULL,
  ADD PRIMARY KEY(field_id);
INSERT INTO drilling_incremental_learning(field_id,policy_json,state_json,update_time)
SELECT field_id,policy_json,state_json,update_time FROM v33_incremental_learning;
DROP TEMPORARY TABLE v33_incremental_learning;
DROP PROCEDURE drilling_drop_v33_fk;

-- Drop foreign keys to drilling_project and secondary project indexes using metadata.
DROP PROCEDURE IF EXISTS drilling_drop_project_fks;
DELIMITER $$
CREATE PROCEDURE drilling_drop_project_fks()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(128);
  DECLARE fk VARCHAR(128);
  DECLARE cur CURSOR FOR SELECT DISTINCT TABLE_NAME,CONSTRAINT_NAME
    FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
      AND REFERENCED_TABLE_NAME='drilling_project';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
  OPEN cur;
  fk_loop: LOOP
    FETCH cur INTO tbl,fk;
    IF done=1 THEN LEAVE fk_loop; END IF;
    SET @ddl=CONCAT('ALTER TABLE `',REPLACE(tbl,'`','``'),'` DROP FOREIGN KEY `',REPLACE(fk,'`','``'),'`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END$$
DELIMITER ;
CALL drilling_drop_project_fks();
DROP PROCEDURE drilling_drop_project_fks;

DROP PROCEDURE IF EXISTS drilling_drop_project_indexes;
DELIMITER $$
CREATE PROCEDURE drilling_drop_project_indexes()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(128);
  DECLARE idx VARCHAR(128);
  DECLARE cur CURSOR FOR SELECT DISTINCT TABLE_NAME,INDEX_NAME
    FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
      AND COLUMN_NAME='project_id' AND INDEX_NAME<>'PRIMARY' AND TABLE_NAME<>'drilling_project';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
  OPEN cur;
  idx_loop: LOOP
    FETCH cur INTO tbl,idx;
    IF done=1 THEN LEAVE idx_loop; END IF;
    SET @ddl=CONCAT('ALTER TABLE `',REPLACE(tbl,'`','``'),'` DROP INDEX `',REPLACE(idx,'`','``'),'`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END$$
DELIMITER ;
CALL drilling_drop_project_indexes();
DROP PROCEDURE drilling_drop_project_indexes;

-- Move any records attached to synthetic project/site nodes into a hidden
-- field-level source inbox, then delete those obsolete engineering nodes.
CREATE TEMPORARY TABLE v33_obsolete_nodes AS
SELECT node_id,field_id FROM drilling_engineering_node
 WHERE node_type IN ('PROJECT','SOURCE_PROJECT','SITE');
INSERT IGNORE INTO drilling_engineering_node
  (node_key,field_id,parent_id,node_type,node_name,node_code,entity_kind,properties_json,status,create_by)
SELECT SHA2(CONCAT('V33-FIELD-INBOX:',n.field_id),256),n.field_id,NULL,'INBOX',
       '未归类来源资料',NULL,'UNKNOWN',JSON_OBJECT('hidden_from_engineering_tree',TRUE),'ACTIVE','migration'
  FROM (SELECT DISTINCT field_id FROM v33_obsolete_nodes WHERE field_id IS NOT NULL) n
 WHERE NOT EXISTS(SELECT 1 FROM drilling_engineering_node i WHERE i.field_id=n.field_id AND i.node_type='INBOX');
CREATE TEMPORARY TABLE v33_obsolete_node_map AS
SELECT o.node_id old_node_id,o.field_id,MIN(i.node_id) inbox_node_id
  FROM v33_obsolete_nodes o JOIN drilling_engineering_node i
    ON i.field_id=o.field_id AND i.node_type='INBOX'
 GROUP BY o.node_id,o.field_id;
UPDATE drilling_engineering_node child JOIN v33_obsolete_node_map m ON m.old_node_id=child.parent_id
   SET child.parent_id=NULL;
DELETE h FROM drilling_engineering_head h JOIN v33_obsolete_node_map m ON m.old_node_id=h.node_id;
UPDATE drilling_engineering_revision r JOIN v33_obsolete_node_map m ON m.old_node_id=r.node_id
   SET r.revision_key=SHA2(CONCAT('V33:',r.revision_id),256),r.node_id=m.inbox_node_id;
UPDATE drilling_engineering_document d JOIN v33_obsolete_node_map m ON m.old_node_id=d.node_id
   SET d.node_id=m.inbox_node_id;
DELETE n FROM drilling_engineering_node n JOIN v33_obsolete_node_map m ON m.old_node_id=n.node_id;
DROP TEMPORARY TABLE v33_obsolete_node_map;
DROP TEMPORARY TABLE v33_obsolete_nodes;

-- Replace the legacy node-type check.  The constraint name is generated by
-- MySQL, so resolve it from metadata instead of assuming a server suffix.
DROP PROCEDURE IF EXISTS drilling_replace_engineering_node_check;
DELIMITER $$
CREATE PROCEDURE drilling_replace_engineering_node_check()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE cname VARCHAR(128);
  DECLARE cur CURSOR FOR
    SELECT tc.CONSTRAINT_NAME
      FROM information_schema.TABLE_CONSTRAINTS tc
      JOIN information_schema.CHECK_CONSTRAINTS cc
        ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
     WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='drilling_engineering_node'
       AND tc.CONSTRAINT_TYPE='CHECK' AND cc.CHECK_CLAUSE LIKE '%node_type%';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
  OPEN cur;
  check_loop: LOOP
    FETCH cur INTO cname;
    IF done=1 THEN LEAVE check_loop; END IF;
    SET @ddl=CONCAT('ALTER TABLE drilling_engineering_node DROP CHECK `',REPLACE(cname,'`','``'),'`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
  ALTER TABLE drilling_engineering_node ADD CONSTRAINT chk_engineering_node_type
    CHECK(node_type IN ('BLOCK','FIELD','WELL','WELLBORE','PLAN','SURVEY','INBOX'));
END$$
DELIMITER ;
CALL drilling_replace_engineering_node_check();
DROP PROCEDURE drilling_replace_engineering_node_check;

-- Remove project_id from every remaining table. Optional tables are selected
-- from metadata, so a disabled feature does not make the upgrade fail.
DROP PROCEDURE IF EXISTS drilling_drop_project_columns;
DELIMITER $$
CREATE PROCEDURE drilling_drop_project_columns()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(128);
  DECLARE cur CURSOR FOR SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='project_id'
      AND TABLE_NAME NOT IN ('drilling_project','drilling_project_config',
                             'drilling_project_member','drilling_project_organization',
                             'drilling_project_wellbore');
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
  OPEN cur;
  col_loop: LOOP
    FETCH cur INTO tbl;
    IF done=1 THEN LEAVE col_loop; END IF;
    SET @ddl=CONCAT('ALTER TABLE `',REPLACE(tbl,'`','``'),'` DROP COLUMN `project_id`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END$$
DELIMITER ;
CALL drilling_drop_project_columns();
DROP PROCEDURE drilling_drop_project_columns;

-- Restore canonical uniqueness after indexes that mentioned project_id were
-- removed. Existing duplicates must be reconciled before this statement.
ALTER TABLE drilling_well ADD UNIQUE KEY uk_well_field_code(field_id,well_code);
ALTER TABLE drilling_source_document ADD UNIQUE KEY uk_source_field(field_id,source_key);

DROP TABLE IF EXISTS drilling_project_wellbore;
DROP TABLE IF EXISTS drilling_project_member;
DROP TABLE IF EXISTS drilling_project_organization;
DROP TABLE IF EXISTS drilling_project_config;
DROP TABLE IF EXISTS drilling_project;

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V33','V33__remove_engineering_project_scope.sql','APPLIED','migration','工程范围只保留区块、井场、井、井眼');

SET FOREIGN_KEY_CHECKS=@old_foreign_key_checks;
