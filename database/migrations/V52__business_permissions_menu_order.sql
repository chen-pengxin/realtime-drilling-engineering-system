START TRANSACTION;

UPDATE sys_menu
SET order_num = 3, update_by = 'migration', update_time = NOW()
WHERE menu_id = 2000 AND parent_id = 0 AND menu_name = '业务操作权限' AND order_num = 4
  AND NOT EXISTS (SELECT 1 FROM drilling_schema_version WHERE version_code = 'V52');

INSERT IGNORE INTO drilling_schema_version (version_code, script_name, status, applied_by, notes)
VALUES ('V52', 'V52__business_permissions_menu_order.sql', 'APPLIED', 'migration', '调整顶层业务操作权限的显示排序');

COMMIT;
