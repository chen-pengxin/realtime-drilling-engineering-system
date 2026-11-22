UPDATE sys_menu SET menu_name='工程项目管理',remark='区块、井场、井、井眼及其资料与版本管理'
 WHERE menu_id=7009;
UPDATE sys_menu SET menu_name='工程参数管理',remark='工程统一计算与显示参数'
 WHERE menu_id=7012;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V57','V57__engineering_project_management_labels.sql','APPLIED','application','工程资料目录更名为工程项目管理；工程参数更名为工程参数管理');
