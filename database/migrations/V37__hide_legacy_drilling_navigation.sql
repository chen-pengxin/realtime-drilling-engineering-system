-- V37: keep legacy permission records for API authorization, but hide the
-- superseded drilling navigation tree. The canonical visible tree starts at
-- 7000 (钻井工程) and must not render a second task/basic menu set.
UPDATE sys_menu
   SET visible='1', update_by='system', update_time=NOW()
 WHERE menu_id IN (2000,2001,2039,2040,2041,2042,2044,2047);

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V37','V37__hide_legacy_drilling_navigation.sql','APPLIED','migration','隐藏旧钻井导航树，保留旧按钮权限记录');
