-- Every product anti-collision task evaluates spacing and separation factor.
-- Normalize historical task rows before tightening the persisted contract.

UPDATE anti_collision_task
   SET evaluation_mode = 'BOTH'
 WHERE evaluation_mode <> 'BOTH';

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
    MODIFY COLUMN evaluation_mode VARCHAR(32) NOT NULL DEFAULT 'BOTH',
    ADD CONSTRAINT chk_collision_mode CHECK (evaluation_mode = 'BOTH');

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V15', 'V15__collision_mode_both_only.sql', 'APPLIED',
        'compose-bootstrap', '产品防碰任务固定同时执行井间距法和分离系数法');
