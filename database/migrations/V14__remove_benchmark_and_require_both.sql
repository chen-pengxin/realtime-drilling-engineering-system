-- Commercial comparison is an offline verification concern, not a product
-- workflow. Remove its persistence/menu footprint and enforce the combined
-- spacing + separation-factor system scan for existing installations.

DELETE FROM sys_role_menu
 WHERE menu_id IN (2037, 2038);

DELETE FROM sys_menu
 WHERE menu_id IN (2037, 2038)
    OR perms IN ('drilling:benchmark:list', 'drilling:benchmark:run');

DROP TABLE IF EXISTS drilling_benchmark_run;

SET @constraint_exists := (
    SELECT COUNT(*)
      FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'anti_collision_task'
       AND constraint_name = 'chk_collision_mode'
);
SET @drop_sql := IF(
    @constraint_exists > 0,
    'ALTER TABLE anti_collision_task DROP CHECK chk_collision_mode',
    'SELECT 1'
);
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

ALTER TABLE anti_collision_task
    ADD CONSTRAINT chk_collision_mode
    CHECK (evaluation_mode IN ('BOTH', 'SPACING', 'SEPARATION_FACTOR', 'ERROR_MODEL_DIFFERENCE', 'ERROR_MODEL'));

UPDATE drilling_project_config
   SET algorithm_defaults_json = JSON_SET(
       COALESCE(algorithm_defaults_json, JSON_OBJECT()), '$.evaluationMode', 'BOTH')
 WHERE status = '0' AND effective_to IS NULL;

UPDATE drilling_project
   SET config_json = JSON_SET(COALESCE(config_json, JSON_OBJECT()), '$.evaluationMode', 'BOTH')
 WHERE config_json IS NOT NULL;

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V14', 'V14__remove_benchmark_and_require_both.sql', 'APPLIED',
        'compose-bootstrap', '移除商业软件对标系统功能并统一防碰双方法评价');
