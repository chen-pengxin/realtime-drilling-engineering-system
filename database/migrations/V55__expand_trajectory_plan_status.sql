-- V55: allow the complete native planner terminal status values.
-- FEASIBLE_NOT_CONVERGED is 22 characters and cannot be stored in the
-- original VARCHAR(16) column. Keep this migration additive and idempotent
-- so existing databases are upgraded without changing any plan records.
ALTER TABLE trajectory_plan
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'QUEUED';

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V55','V55__expand_trajectory_plan_status.sql','APPLIED','application',
     '允许保存原生规划终态 FEASIBLE_NOT_CONVERGED、TIMEOUT 等完整状态');
