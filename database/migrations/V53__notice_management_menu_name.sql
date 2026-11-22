START TRANSACTION;

UPDATE sys_menu m
JOIN drilling_menu_setting s ON s.menu_id = m.menu_id
SET m.menu_name = '通知公告管理', m.update_by = 'migration', m.update_time = NOW()
WHERE s.route_path = '/access/notices' AND m.menu_name = '通知公告'
  AND NOT EXISTS (SELECT 1 FROM drilling_schema_version WHERE version_code = 'V53');

INSERT IGNORE INTO drilling_schema_version (version_code, script_name, status, applied_by, notes)
VALUES ('V53', 'V53__notice_management_menu_name.sql', 'APPLIED', 'migration', '配置与公告下的通知公告管理入口');

COMMIT;
