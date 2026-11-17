-- V35: restore field-scoped indexes after the retired engineering scope was
-- removed. V33 intentionally dropped indexes containing project_id; these
-- indexes keep field queries selective without reintroducing that scope.

SET FOREIGN_KEY_CHECKS=0;
DROP PROCEDURE IF EXISTS drilling_add_field_index;
DELIMITER $$
CREATE PROCEDURE drilling_add_field_index(IN p_table VARCHAR(128), IN p_index VARCHAR(128), IN p_columns VARCHAR(500))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema=DATABASE() AND table_name=p_table)
     AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=p_table AND column_name='field_id')
     AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                     WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_index) THEN
    SET @field_index_ddl=CONCAT('ALTER TABLE `',REPLACE(p_table,'`','``'),'` ADD KEY `',
      REPLACE(p_index,'`','``'),'` (',p_columns,')');
    PREPARE field_index_stmt FROM @field_index_ddl;
    EXECUTE field_index_stmt;
    DEALLOCATE PREPARE field_index_stmt;
  END IF;
END$$
DELIMITER ;

CALL drilling_add_field_index('data_file','idx_file_field_category','field_id,file_category');
CALL drilling_add_field_index('drilling_target','idx_target_field','field_id');
CALL drilling_add_field_index('anti_collision_task','idx_collision_field_status','field_id,status');
CALL drilling_add_field_index('trajectory_plan','idx_plan_field_status','field_id,status');
CALL drilling_add_field_index('prediction_task','idx_prediction_field','field_id');
CALL drilling_add_field_index('training_dataset','idx_dataset_field','field_id');
CALL drilling_add_field_index('training_template','idx_template_field','field_id');
CALL drilling_add_field_index('training_task','idx_training_field_status','field_id,status');
CALL drilling_add_field_index('model_version','idx_model_field_name_version','field_id,model_name,version_no');
CALL drilling_add_field_index('drilling_data_quality_issue','idx_quality_field_status','field_id,status');
CALL drilling_add_field_index('drilling_realtime_connector','idx_connector_field_status','field_id,status');
CALL drilling_add_field_index('drilling_alarm','idx_alarm_field_status','field_id,status,create_time');
CALL drilling_add_field_index('drilling_audit_log','idx_audit_field_time','field_id,create_time');
CALL drilling_add_field_index('drilling_task_event','idx_task_event_field_time','field_id,create_time');
CALL drilling_add_field_index('drilling_realtime_ingest_event','idx_realtime_field_status','field_id,well_id,status,event_id');
CALL drilling_add_field_index('drilling_center_snapshot','idx_center_snapshot_field','field_id');
CALL drilling_add_field_index('drilling_center_execution','idx_center_execution_field','field_id,source_id,status');
CALL drilling_add_field_index('drilling_center_origin','idx_center_origin_field','field_id');
CALL drilling_add_field_index('drilling_center_report','idx_center_report_field','field_id,status,create_time');
CALL drilling_add_field_index('drilling_import_batch','idx_import_batch_field_status','field_id,status,create_time');
CALL drilling_add_field_index('drilling_engine_confirmation','idx_engine_confirmation_field_user','field_id,user_id,config_hash');
CALL drilling_add_field_index('drilling_source_review','idx_source_review_field_status','field_id,status,create_time');
CALL drilling_add_field_index('drilling_engineering_node','idx_engineering_node_field_type','field_id,node_type,status');
CALL drilling_add_field_index('drilling_system_audit','idx_system_audit_field_time','field_id,create_time');

DROP PROCEDURE drilling_add_field_index;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V35','V35__field_scope_indexes.sql','APPLIED','migration','恢复井场范围查询索引，不重新引入工程项目字段');
SET FOREIGN_KEY_CHECKS=1;
