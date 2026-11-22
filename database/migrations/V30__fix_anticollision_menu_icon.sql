-- Repair only old icon names. A valid configured SVG must survive startup.
UPDATE sys_menu SET icon='form', update_by='system', update_time=NOW()
 WHERE (url='/drilling/tasks/anticollision' OR menu_name='防碰评价')
   AND (icon IS NULL OR TRIM(icon) IN ('', '#', 'warning') OR TRIM(icon) REGEXP '^fa(-|[[:space:]])');

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V30','V30__fix_anticollision_menu_icon.sql','APPLIED','application','防碰评价使用 RuoYi SVG form 图标');
