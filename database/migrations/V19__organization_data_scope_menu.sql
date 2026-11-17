-- V19: organization, project data scopes and navigation. Re-runnable; preserve edited settings.
CREATE TABLE IF NOT EXISTS drilling_project_organization (
 project_id BIGINT NOT NULL PRIMARY KEY, dept_id BIGINT NULL, owner_user_id BIGINT NULL,
 update_by VARCHAR(64) DEFAULT '', update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 KEY idx_project_department(dept_id), KEY idx_project_owner(owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS drilling_menu_setting (
 menu_id BIGINT NOT NULL PRIMARY KEY, route_path VARCHAR(160) NULL UNIQUE,
 module_key VARCHAR(32) DEFAULT '', status CHAR(1) NOT NULL DEFAULT '0', builtin BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE sys_role r JOIN drilling_role_module rm ON rm.role_id=r.role_id SET r.data_scope='6'
 WHERE rm.account_tier IN ('ENGINEER','GUEST') AND NOT EXISTS (SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO drilling_project_organization(project_id,dept_id,owner_user_id)
 SELECT p.project_id,u.dept_id,COALESCE(u.user_id,(SELECT MIN(m.user_id) FROM drilling_project_member m WHERE m.project_id=p.project_id AND m.role_code='OWNER' AND m.status='0'))
 FROM drilling_project p LEFT JOIN sys_user u ON u.login_name COLLATE utf8mb4_unicode_ci=p.create_by COLLATE utf8mb4_unicode_ci AND u.del_flag='0';
UPDATE sys_dept SET dept_name=CASE dept_name WHEN '若依科技' THEN '钻井工程中心' WHEN '深圳总公司' THEN '钻井工程一部' WHEN '长沙分公司' THEN '钻井工程二部' WHEN '研发部门' THEN '工程技术组' WHEN '市场部门' THEN '项目协调组' WHEN '测试部门' THEN '质量管理组' WHEN '财务部门' THEN '综合管理组' WHEN '运维部门' THEN '系统运维组' ELSE dept_name END WHERE NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
UPDATE sys_dept SET leader=IF(leader='若依','',leader),phone=IF(phone IN ('15888888888','15666666666'),'',phone),email=IF(email='ry@qq.com','',email) WHERE NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
UPDATE sys_post SET post_name=CASE post_name WHEN '董事长' THEN '工程负责人' WHEN '项目经理' THEN '钻井项目经理' WHEN '人力资源' THEN '综合管理岗' WHEN '普通员工' THEN '钻井工程师' ELSE post_name END WHERE NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7000,'钻井工程',0,1,'/drilling','','M','0','1','','chart','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7000,'/drilling','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7001,'实时大屏',7000,1,'/drilling/realtime','','C','0','1','','monitor','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7001,'/drilling/realtime','realtime',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7002,'任务中心',7000,2,'/drilling/tasks','','M','0','1','','job','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7002,'/drilling/tasks','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7003,'防碰评价',7002,1,'/drilling/tasks/anticollision','','C','0','1','','form','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7003,'/drilling/tasks/anticollision','anticollision',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7004,'井眼轨迹预测',7002,2,'/drilling/tasks/prediction','','M','0','1','','job','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7004,'/drilling/tasks/prediction','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7005,'预测展示',7004,1,'/drilling/tasks/prediction/overview','','C','0','1','','monitor','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7005,'/drilling/tasks/prediction/overview','prediction',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7006,'模型仓库',7004,2,'/drilling/tasks/prediction/models','','C','0','1','','table','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7006,'/drilling/tasks/prediction/models','models',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7007,'轨迹再规划',7002,3,'/drilling/tasks/replanning','','C','0','1','','edit','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7007,'/drilling/tasks/replanning','replanning',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7008,'基础功能',7000,3,'/drilling/basic','','M','0','1','','tool','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7008,'/drilling/basic','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7009,'工程资料目录',7008,1,'/drilling/basic/catalog','','C','0','1','','tree','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7009,'/drilling/basic/catalog','catalog',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7010,'井与测斜数据',7008,2,'/drilling/basic/surveys','','C','0','1','','table','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7010,'/drilling/basic/surveys','surveys',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7011,'实时数据接入',7008,3,'/drilling/basic/realtime-data','','C','0','1','','monitor','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7011,'/drilling/basic/realtime-data','realtime-data',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7012,'系统配置',7008,4,'/drilling/basic/config','','C','0','1','','edit','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7012,'/drilling/basic/config','config',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7013,'文件与审计',7008,5,'/drilling/basic/files','','C','0','1','','documentation','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7013,'/drilling/basic/files','files',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7014,'系统管理',0,2,'/access','','M','0','1','','system','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7014,'/access','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7015,'用户管理',7014,1,'/access/users','','C','0','1','','user','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7015,'/access/users','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7016,'角色管理',7014,2,'/access/roles','','C','0','1','','peoples','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7016,'/access/roles','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7017,'部门管理',7014,3,'/access/departments','','C','0','1','','tree','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7017,'/access/departments','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7018,'岗位管理',7014,4,'/access/posts','','C','0','1','','post','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7018,'/access/posts','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7019,'数据权限',7014,5,'/access/data-scope','','C','0','1','','lock','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7019,'/access/data-scope','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7020,'菜单管理',7014,6,'/access/menus','','C','0','1','','tree-table','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7020,'/access/menus','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7021,'登录日志',7014,7,'/access/login-logs','','C','0','1','','logininfor','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7021,'/access/login-logs','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7022,'在线用户',7014,8,'/access/sessions','','C','0','1','','online','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7022,'/access/sessions','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7023,'操作日志',7014,9,'/access/audit','','C','0','1','','log','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7023,'/access/audit','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7024,'字典管理',7014,10,'/access/dictionaries','','C','0','1','','dict','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7024,'/access/dictionaries','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7025,'参数配置',7014,11,'/access/settings','','C','0','1','','edit','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7025,'/access/settings','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7026,'通知公告',7014,12,'/access/notices','','C','0','1','','message','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7026,'/access/notices','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7027,'运行维护',0,3,'/operations','','M','0','1','','monitor','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7027,'/operations','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7028,'运行监控',7027,1,'/operations/monitor','','C','0','1','','server','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7028,'/operations/monitor','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7029,'后台维护任务',7027,2,'/operations/maintenance','','C','0','1','','job','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin) VALUES(7029,'/operations/maintenance','',TRUE);
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7030,'用户与组织',7014,1,'/access/organization','','M','0','1','','tree','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,builtin) VALUES(7030,'/access/organization',TRUE);
UPDATE sys_menu SET parent_id=7030 WHERE menu_id IN (7015,7017,7018) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7031,'角色与权限',7014,2,'/access/permissions','','M','0','1','','lock','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,builtin) VALUES(7031,'/access/permissions',TRUE);
UPDATE sys_menu SET parent_id=7031 WHERE menu_id IN (7016,7019,7020) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7032,'配置与公告',7014,3,'/access/configuration','','M','0','1','','system','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,builtin) VALUES(7032,'/access/configuration',TRUE);
UPDATE sys_menu SET parent_id=7032 WHERE menu_id IN (7024,7025,7026) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time) VALUES(7033,'日志与会话',7014,4,'/access/activity','','M','0','1','','log','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,builtin) VALUES(7033,'/access/activity',TRUE);
UPDATE sys_menu SET parent_id=7033 WHERE menu_id IN (7021,7022,7023) AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
UPDATE sys_menu SET menu_name='业务操作权限' WHERE menu_id=2000 AND menu_name='钻井工程' AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V19');
INSERT IGNORE INTO drilling_menu_setting(menu_id) SELECT menu_id FROM sys_menu WHERE perms LIKE 'drilling:%';
INSERT IGNORE INTO drilling_menu_setting(menu_id) SELECT DISTINCT m.parent_id FROM sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id JOIN sys_menu parent ON parent.menu_id=m.parent_id;
INSERT IGNORE INTO drilling_menu_setting(menu_id) SELECT DISTINCT m.parent_id FROM sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id JOIN sys_menu parent ON parent.menu_id=m.parent_id;
INSERT IGNORE INTO drilling_menu_setting(menu_id) SELECT DISTINCT m.parent_id FROM sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id JOIN sys_menu parent ON parent.menu_id=m.parent_id;
INSERT IGNORE INTO drilling_menu_setting(menu_id) SELECT DISTINCT m.parent_id FROM sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id JOIN sys_menu parent ON parent.menu_id=m.parent_id;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes) VALUES('V19','V19__organization_data_scope_menu.sql','APPLIED','application','{}');
