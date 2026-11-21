-- Engineering policies are shared; source coordinate/tool budgets stay in immutable versions.
CREATE TABLE IF NOT EXISTS drilling_engineering_config (
 config_version BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 algorithm_defaults_json JSON NOT NULL,
 create_by VARCHAR(64) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE sys_menu SET menu_name='工程参数', remark='工程统一参数及井眼版本参数'
 WHERE url='/drilling/basic/config' AND menu_name<>'工程参数';
INSERT INTO drilling_engineering_config(algorithm_defaults_json,create_by)
 SELECT JSON_SET(c.algorithm_defaults_json,'$.prediction.timeoutSeconds',1,
 '$.planning.taskTimeoutSeconds',30,'$.planning.minDoglegDegPer30m',0,
 '$.planning.realtimeBudgetSeconds',30,
 '$.planning.realtimeExecutionTimeoutSeconds',30,
 '$.planning.nativeOptions.solver.max_seconds_per_run',30,
 '$.planning.nativeOptions.solver.optimization_starts',1), 'engineering-scope-migration'
 FROM drilling_field_config c
 WHERE c.status='0' AND c.effective_to IS NULL AND JSON_CONTAINS_PATH(c.algorithm_defaults_json,'one','$.errorModel.settings')
 AND NOT EXISTS(SELECT 1 FROM drilling_engineering_config)
 ORDER BY c.create_time DESC,c.field_id,c.config_version DESC LIMIT 1;
UPDATE drilling_engineering_config
   SET algorithm_defaults_json=JSON_REMOVE(JSON_SET(algorithm_defaults_json,
       '$.prediction.timeoutSeconds',1,
       '$.planning.taskTimeoutSeconds',30,
       '$.planning.minDoglegDegPer30m',0,
       '$.planning.realtimeBudgetSeconds',30,
       '$.planning.realtimeExecutionTimeoutSeconds',30,
       '$.planning.nativeOptions.solver.max_seconds_per_run',30,
       '$.planning.nativeOptions.solver.optimization_starts',1),
       '$.planning.realtimeSolverBudgetSeconds')
 WHERE create_by='engineering-scope-migration';
UPDATE sys_menu SET menu_name='工程参数',remark='工程统一参数及井眼版本参数'
 WHERE url='/drilling/basic/config' AND menu_name<>'工程参数';
