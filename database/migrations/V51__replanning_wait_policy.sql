-- Replanning stop policy: native convergence/iteration limits or the
-- engineering-configured maximum wait (30 s is only the initial default).
-- This migration also normalizes installations that already applied V50.
UPDATE drilling_engineering_config
   SET algorithm_defaults_json = JSON_REMOVE(
       JSON_SET(algorithm_defaults_json,
         '$.planning.taskTimeoutSeconds',30,
         '$.planning.realtimeBudgetSeconds',30,
         '$.planning.realtimeExecutionTimeoutSeconds',30,
         '$.planning.nativeOptions.solver.max_seconds_per_run',30,
         '$.planning.nativeOptions.solver.optimization_starts',1),
       '$.planning.realtimeSolverBudgetSeconds');

UPDATE drilling_field_config
   SET algorithm_defaults_json = JSON_REMOVE(
       JSON_SET(algorithm_defaults_json,
         '$.planning.taskTimeoutSeconds',30,
         '$.planning.realtimeBudgetSeconds',30,
         '$.planning.realtimeExecutionTimeoutSeconds',30,
         '$.planning.nativeOptions.solver.max_seconds_per_run',30,
         '$.planning.nativeOptions.solver.optimization_starts',1),
       '$.planning.realtimeSolverBudgetSeconds')
 WHERE effective_to IS NULL;

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V51','V51__replanning_wait_policy.sql','APPLIED','application',
     '再规划等待收敛、迭代或函数评估上限，最长等待时间由工程参数配置');
