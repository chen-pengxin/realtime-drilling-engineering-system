-- 防碰模式约束升级。
-- 用于已经执行旧版 V4__drilling_contracts.sql 的数据库；不要回放旧 V4。
-- 该脚本先删除同名旧约束，再建立包含 ERROR_MODEL_DIFFERENCE 的约束。

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
