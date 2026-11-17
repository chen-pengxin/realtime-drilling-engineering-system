-- Six field-scoped business centers. Original task/version/alert tables remain authoritative.
CREATE TABLE IF NOT EXISTS drilling_center_snapshot (
 task_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL, field_id BIGINT NOT NULL,
 input_json JSON NOT NULL, config_snapshot_json JSON NULL,
 started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL,
 PRIMARY KEY(task_type,source_id), KEY idx_center_snapshot_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_center_execution (
 execution_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, field_id BIGINT NOT NULL,
 well_id BIGINT NULL, task_type VARCHAR(32) NOT NULL, source_id BIGINT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'RUNNING', input_json JSON NOT NULL,
 config_snapshot_json JSON NULL, result_json JSON NULL, error_message VARCHAR(1000) NULL,
 create_by VARCHAR(64) NOT NULL, create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finish_time DATETIME(3) NULL, KEY idx_center_execution(field_id,source_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_center_origin (
 origin_type VARCHAR(32) NOT NULL, origin_key VARCHAR(190) NOT NULL,
 field_id BIGINT NOT NULL, resource_id BIGINT NOT NULL,
 PRIMARY KEY(origin_type,origin_key), KEY idx_center_origin_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_center_report (
 report_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, field_id BIGINT NOT NULL,
 title VARCHAR(128) NOT NULL, report_type VARCHAR(32) NOT NULL, source_key VARCHAR(128) NULL,
 format VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'READY',
 object_key VARCHAR(512) NOT NULL, content_type VARCHAR(128) NOT NULL,
 file_size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, snapshot_json JSON NOT NULL,
 create_by VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 archived_by VARCHAR(64) NULL, archived_at DATETIME NULL,
 KEY idx_center_report_field(field_id,status,create_time),
 CONSTRAINT fk_center_report_field FOREIGN KEY(field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_connector_run (
 run_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, connector_id BIGINT NOT NULL,
 field_id BIGINT NOT NULL, outcome VARCHAR(16) NOT NULL, record_count INT NOT NULL DEFAULT 0,
 survey_version_id BIGINT NULL, message VARCHAR(500) NULL,
 create_by VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_connector_run(connector_id,create_time),
 CONSTRAINT fk_connector_run FOREIGN KEY(connector_id) REFERENCES drilling_realtime_connector(connector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time)
VALUES(7100,'业务管理',7000,4,'/drilling/centers','','M','0','1','','list','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,builtin) VALUES(7100,'/drilling/centers',TRUE);

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7101,'告警中心',7100,1,'/drilling/centers/alerts','','C','0','1','drilling:alerts:list','warning','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7101,'/drilling/centers/alerts','alerts',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7120,'告警确认',7101,1,'#','','F','1','1','drilling:alerts:ack','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7120,'alerts',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7121,'告警解除',7101,2,'#','','F','1','1','drilling:alerts:resolve','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7121,'alerts',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7122,'告警导出',7101,3,'#','','F','1','1','drilling:alerts:export','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7122,'alerts',TRUE);
UPDATE drilling_role_policy SET modules_json=JSON_ARRAY_APPEND(modules_json,'$','alerts') WHERE account_tier IN ('ADMIN','ENGINEER','GUEST') AND NOT JSON_CONTAINS(modules_json,JSON_QUOTE('alerts')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY_APPEND(rm.modules_json,'$','alerts') WHERE r.role_key IN ('admin','engineer','guest') AND NOT JSON_CONTAINS(rm.modules_json,JSON_QUOTE('alerts')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=7101 OR m.parent_id=7101 WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7102,'任务历史',7100,2,'/drilling/centers/history','','C','0','1','drilling:history:list','job','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7102,'/drilling/centers/history','history',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7130,'任务记录导出',7102,1,'#','','F','1','1','drilling:history:export','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7130,'history',TRUE);
UPDATE drilling_role_policy SET modules_json=JSON_ARRAY_APPEND(modules_json,'$','history') WHERE account_tier IN ('ADMIN','ENGINEER','GUEST') AND NOT JSON_CONTAINS(modules_json,JSON_QUOTE('history')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY_APPEND(rm.modules_json,'$','history') WHERE r.role_key IN ('admin','engineer','guest') AND NOT JSON_CONTAINS(rm.modules_json,JSON_QUOTE('history')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=7102 OR m.parent_id=7102 WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7104,'报告中心',7100,4,'/drilling/centers/reports','','C','0','1','drilling:reports:list','documentation','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7104,'/drilling/centers/reports','reports',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7150,'报告生成',7104,1,'#','','F','1','1','drilling:reports:create','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7150,'reports',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7151,'报告下载',7104,2,'#','','F','1','1','drilling:reports:download','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7151,'reports',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7152,'报告归档',7104,3,'#','','F','1','1','drilling:reports:archive','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7152,'reports',TRUE);
UPDATE drilling_role_policy SET modules_json=JSON_ARRAY_APPEND(modules_json,'$','reports') WHERE account_tier IN ('ADMIN','ENGINEER','GUEST') AND NOT JSON_CONTAINS(modules_json,JSON_QUOTE('reports')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY_APPEND(rm.modules_json,'$','reports') WHERE r.role_key IN ('admin','engineer','guest') AND NOT JSON_CONTAINS(rm.modules_json,JSON_QUOTE('reports')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=7104 OR m.parent_id=7104 WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7105,'连接器管理',7100,5,'/drilling/centers/connectors','','C','0','1','drilling:connectors:list','link','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7105,'/drilling/centers/connectors','connectors',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7160,'连接器配置',7105,1,'#','','F','1','1','drilling:connectors:save','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7160,'connectors',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7161,'连接器读取',7105,2,'#','','F','1','1','drilling:connectors:run','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7161,'connectors',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7162,'连接器导出',7105,3,'#','','F','1','1','drilling:connectors:export','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7162,'connectors',TRUE);
UPDATE drilling_role_policy SET modules_json=JSON_ARRAY_APPEND(modules_json,'$','connectors') WHERE account_tier IN ('ADMIN','ENGINEER','GUEST') AND NOT JSON_CONTAINS(modules_json,JSON_QUOTE('connectors')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY_APPEND(rm.modules_json,'$','connectors') WHERE r.role_key IN ('admin','engineer','guest') AND NOT JSON_CONTAINS(rm.modules_json,JSON_QUOTE('connectors')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=7105 OR m.parent_id=7105 WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7106,'版本与变更记录',7100,6,'/drilling/centers/versions','','C','0','1','drilling:versions:list','time','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7106,'/drilling/centers/versions','versions',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7170,'版本记录导出',7106,1,'#','','F','1','1','drilling:versions:export','#','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,module_key,builtin) VALUES(7170,'versions',TRUE);
UPDATE drilling_role_policy SET modules_json=JSON_ARRAY_APPEND(modules_json,'$','versions') WHERE account_tier IN ('ADMIN','ENGINEER','GUEST') AND NOT JSON_CONTAINS(modules_json,JSON_QUOTE('versions')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY_APPEND(rm.modules_json,'$','versions') WHERE r.role_key IN ('admin','engineer','guest') AND NOT JSON_CONTAINS(rm.modules_json,JSON_QUOTE('versions')) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=7106 OR m.parent_id=7106 WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,7100 FROM sys_role r WHERE r.role_key='engineer' AND r.del_flag='0' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V20');
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes) VALUES('V20','V20__drilling_business_centers.sql','APPLIED','application','Six field business centers');

-- Import diagnostics remain internal; no separate quality-management menu.
DELETE FROM sys_role_menu WHERE menu_id IN (7103,7140,7141,7142);
DELETE FROM drilling_menu_setting WHERE module_key='quality';
DELETE FROM sys_menu WHERE menu_id IN (7140,7141,7142,7103);
UPDATE drilling_role_policy
   SET modules_json=JSON_REMOVE(modules_json, JSON_UNQUOTE(JSON_SEARCH(modules_json,'one','quality')))
 WHERE JSON_CONTAINS(modules_json,JSON_QUOTE('quality'))
   AND JSON_SEARCH(modules_json,'one','quality') IS NOT NULL;
UPDATE drilling_role_module
   SET modules_json=JSON_REMOVE(modules_json, JSON_UNQUOTE(JSON_SEARCH(modules_json,'one','quality')))
 WHERE JSON_CONTAINS(modules_json,JSON_QUOTE('quality'))
   AND JSON_SEARCH(modules_json,'one','quality') IS NOT NULL;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V23','V23__remove_quality_management_menu.sql','APPLIED','application','Removed user-facing quality management menu');
