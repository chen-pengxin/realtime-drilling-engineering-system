-- Replanning now races all configured solvers and returns the first valid
-- result. Migrate the former SLSQP-only default once; explicit later choices
-- remain available to operators.
UPDATE drilling_engineering_config
   SET algorithm_defaults_json = JSON_SET(
       COALESCE(algorithm_defaults_json, JSON_OBJECT()),
       '$.planning.solver', 'ALL')
 WHERE JSON_UNQUOTE(JSON_EXTRACT(algorithm_defaults_json, '$.planning.solver')) = 'SLSQP';

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V65','V65__parallel_solver_default.sql','APPLIED','application','再规划默认并行竞速，采用首个有效算法结果');
