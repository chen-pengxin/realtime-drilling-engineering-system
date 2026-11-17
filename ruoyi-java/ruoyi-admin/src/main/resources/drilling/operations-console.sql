-- RuoYi operations adapted for the drilling console. Safe to execute repeatedly.
CREATE TABLE IF NOT EXISTS drilling_system_audit (
 event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 actor_user_id BIGINT NULL, actor_name VARCHAR(64) NOT NULL DEFAULT '',
 action VARCHAR(255) NOT NULL, field_id BIGINT NULL, well_id BIGINT NULL,
 survey_version_id BIGINT NULL, task_id VARCHAR(128) NULL,
 request_id VARCHAR(64) NULL, resource_path VARCHAR(500) NOT NULL,
 http_method VARCHAR(10) NOT NULL, outcome VARCHAR(16) NOT NULL,
 detail_json LONGTEXT NULL, client_ip VARCHAR(128) NULL,
 duration_ms BIGINT NOT NULL DEFAULT 0, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_system_audit_time(create_time), KEY idx_system_audit_field(field_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_revoked_session (
 session_key CHAR(64) NOT NULL PRIMARY KEY,
 revoked_by BIGINT NOT NULL, revoked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL, KEY idx_revoked_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_maintenance_result (
 result_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_code VARCHAR(64) NOT NULL, outcome VARCHAR(16) NOT NULL,
 summary_json LONGTEXT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_maintenance_task(task_code,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS sys_notice_read (
 read_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, notice_id INT NOT NULL,
 user_id BIGINT NOT NULL, read_time DATETIME NOT NULL,
 UNIQUE KEY uk_user_notice(user_id,notice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '系统名称','drilling.system.name','实时钻井系统','Y','system',NOW(),'登录后导航与浏览器标题'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='drilling.system.name');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '会话空闲超时（分钟）','drilling.session.timeoutMinutes','30','Y','system',NOW(),'5～240分钟，对后续请求生效'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='drilling.session.timeoutMinutes');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '临时导出文件保留天数','drilling.maintenance.retentionDays','30','Y','system',NOW(),'1～365天，仅清理下载临时目录'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='drilling.maintenance.retentionDays');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '监控刷新间隔（秒）','drilling.monitor.refreshSeconds','30','Y','system',NOW(),'10～300秒'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='drilling.monitor.refreshSeconds');
INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,remark)
SELECT '钻井数据来源','drilling_data_source','0','system',NOW(),'固定协议编码，允许维护显示名称'
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_type WHERE dict_type='drilling_data_source');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT 1,'文件导入','FILE','drilling_data_source','Y','0','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='drilling_data_source' AND dict_value='FILE');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT 2,'WITS TCP','WITS_TCP','drilling_data_source','N','0','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='drilling_data_source' AND dict_value='WITS_TCP');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT 3,'串口','SERIAL','drilling_data_source','N','0','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='drilling_data_source' AND dict_value='SERIAL');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT 4,'WITSML','WITSML','drilling_data_source','N','0','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='drilling_data_source' AND dict_value='WITSML');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT 5,'SQL 数据源','SQL','drilling_data_source','N','0','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='drilling_data_source' AND dict_value='SQL');
-- Hide only the unchanged framework demo announcements; preserve real announcements.
UPDATE sys_notice SET status='1' WHERE (notice_id=1 AND notice_title='温馨提醒：2018-07-01 若依新版本发布啦')
 OR (notice_id=2 AND notice_title='维护通知：2018-07-01 若依系统凌晨维护')
 OR (notice_id=3 AND notice_title='若依开源框架介绍');
