-- V53: keep business-center APIs for task pages, but remove their duplicate
-- top-level navigation. History, alarms, reports and connector operations are
-- opened from the corresponding engineering pages or basic-function pages.
-- Permission rows remain in sys_menu so existing controller authorization and
-- task-page actions continue to work.
UPDATE sys_menu
   SET visible='1', update_by='system', update_time=NOW()
 WHERE menu_id IN (7100,7101,7102,7104,7105,7106,
                   7120,7121,7122,7130,7150,7151,7152,
                   7160,7161,7162,7170);

DELETE FROM drilling_menu_setting
 WHERE menu_id IN (7100,7101,7102,7104,7105,7106,
                   7120,7121,7122,7130,7150,7151,7152,
                   7160,7161,7162,7170);

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V53','V53__hide_redundant_business_centers.sql','APPLIED','application',
     '隐藏重复业务管理导航；告警、历史、报告和接入接口保留给对应页面调用');
