-- Quality checks remain internal import diagnostics. They are not a user-facing
-- management center; files, business revisions and release history own the UI.
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
VALUES('V23','V23__remove_quality_management_menu.sql','APPLIED','migration','Removed user-facing quality management menu');
