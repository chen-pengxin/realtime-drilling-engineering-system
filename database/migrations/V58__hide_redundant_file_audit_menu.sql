-- 文件管理已经并入工程项目管理；旧的独立入口不再出现在基础功能菜单。
UPDATE sys_menu SET visible='1' WHERE menu_id=7013;
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V58','V58__hide_redundant_file_audit_menu.sql','APPLIED','application','文件管理并入工程项目管理，隐藏旧文件与审计入口');
