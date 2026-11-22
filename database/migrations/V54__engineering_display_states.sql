START TRANSACTION;

INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,remark)
SELECT s.ord,s.label,s.code,'drilling_ui_task_state','0','运行监控、任务历史'
FROM (
  SELECT 8 AS ord, '成功' AS label, 'SUCCEEDED' AS code
  UNION ALL SELECT 9,'已终止','STOPPED'
  UNION ALL SELECT 10,'任务丢失','LOST'
  UNION ALL SELECT 11,'未配置模型','MODEL_NOT_CONFIGURED'
  UNION ALL SELECT 12,'约束不可行','INFEASIBLE'
  UNION ALL SELECT 13,'可选方案','BEST_EFFORT'
) s
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type='drilling_ui_task_state' AND d.dict_value=s.code);

UPDATE sys_dict_data SET remark='防碰评价、实时大屏、告警记录'
WHERE dict_type='drilling_ui_risk_level' AND remark='防碰评价、实时大屏';

INSERT IGNORE INTO drilling_schema_version (version_code,script_name,status,applied_by,notes)
VALUES ('V54','V54__engineering_display_states.sql','APPLIED','migration','任务历史和告警记录使用统一显示字典');
COMMIT;
