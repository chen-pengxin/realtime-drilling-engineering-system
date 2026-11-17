-- Test cases use the normal engineering tree and task pages.
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE url='/drilling/tasks/demonstrations');
DELETE FROM drilling_menu_setting WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE url='/drilling/tasks/demonstrations');
DELETE FROM sys_menu WHERE url='/drilling/tasks/demonstrations';
UPDATE sys_menu SET menu_name=REPLACE(menu_name,'项目文件','工程文件') WHERE menu_name LIKE '%项目文件%';
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V45','V45__integrate_test_cases_into_engineering.sql','APPLIED','migration','测试案例使用普通井眼数据和业务页面');
