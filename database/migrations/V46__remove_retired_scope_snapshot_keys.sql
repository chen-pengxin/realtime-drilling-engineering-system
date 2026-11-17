-- Retired scope identifiers must not survive in business snapshots either.
UPDATE drilling_center_execution
 SET input_json=JSON_REMOVE(input_json,'$.project_id','$.projectId'),
     config_snapshot_json=JSON_REMOVE(config_snapshot_json,'$.project_id','$.projectId');
DROP PROCEDURE IF EXISTS drilling_remove_retired_snapshot_scope;
DELIMITER $$
CREATE PROCEDURE drilling_remove_retired_snapshot_scope()
BEGIN
 DECLARE i INT DEFAULT 0;
 DECLARE n INT DEFAULT 0;
 SELECT GREATEST(
   COALESCE((SELECT MAX(JSON_LENGTH(detail_json,'$.wells')) FROM drilling_audit_log),0),
   COALESCE((SELECT MAX(JSON_LENGTH(manifest_json,'$.wells')) FROM drilling_engineering_migration),0)) INTO n;
 WHILE i<n DO
   UPDATE drilling_audit_log SET detail_json=JSON_REMOVE(detail_json,CONCAT('$.wells[',i,'].project_id'))
    WHERE JSON_CONTAINS_PATH(detail_json,'one',CONCAT('$.wells[',i,'].project_id'));
   UPDATE drilling_engineering_migration SET manifest_json=JSON_REMOVE(manifest_json,CONCAT('$.wells[',i,'].project_id'))
    WHERE JSON_CONTAINS_PATH(manifest_json,'one',CONCAT('$.wells[',i,'].project_id'));
   SET i=i+1;
 END WHILE;
END$$
DELIMITER ;
CALL drilling_remove_retired_snapshot_scope();
DROP PROCEDURE drilling_remove_retired_snapshot_scope;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V46','V46__remove_retired_scope_snapshot_keys.sql','APPLIED','migration','清除历史快照内停用的工程范围字段');
