-- 防碰报警查询、确认与解除的菜单权限。
-- 报警本体由 V2 创建；本迁移为新增闭环接口登记页面按钮权限。

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2034, '防碰报警查询', 2000, 34, '#', '', 'F', '1', '1', 'drilling:alarm:list', 'fa fa-bell', 'admin', NOW(), '项目防碰报警查询权限'),
    (2035, '防碰报警确认', 2000, 35, '#', '', 'F', '1', '1', 'drilling:alarm:ack', 'fa fa-check', 'admin', NOW(), '项目防碰报警确认权限'),
    (2036, '防碰报警解除', 2000, 36, '#', '', 'F', '1', '1', 'drilling:alarm:resolve', 'fa fa-check-circle', 'admin', NOW(), '项目防碰报警解除权限')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    menu_type=VALUES(menu_type), visible=VALUES(visible), is_refresh=VALUES(is_refresh),
    perms=VALUES(perms), icon=VALUES(icon), update_by='admin', update_time=NOW(), remark=VALUES(remark);

-- RuoYi 标准角色可以查看项目报警；确认和解除还需 OWNER/ENGINEER 项目成员身份。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id=2034;

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V11', 'V11__collision_alarm_workflow.sql', 'APPLIED', 'compose-bootstrap', '防碰报警查询确认解除权限');
