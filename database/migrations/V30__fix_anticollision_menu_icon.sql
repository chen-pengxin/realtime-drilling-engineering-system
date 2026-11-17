-- Use an icon that exists in the Vue RuoYi SVG icon set.  The update is
-- route-based as installations may use either the legacy 2039 or the newer
-- 7003 menu id.
UPDATE sys_menu
   SET icon='form', update_by='system', update_time=NOW()
 WHERE url='/drilling/tasks/anticollision'
    OR menu_name='防碰评价';

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V30','V30__fix_anticollision_menu_icon.sql','APPLIED','application','防碰评价使用 RuoYi SVG form 图标');
