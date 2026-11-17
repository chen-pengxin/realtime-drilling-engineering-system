-- The product entry is split into task-center and basic-function menus.
-- This migration only changes navigation and permissions; no algorithm or
-- commercial benchmark workflow is introduced.

UPDATE sys_menu
   SET menu_name='任务中心', menu_type='M', url='#', perms='', icon='fa fa-tasks',
       order_num=1, visible='0', update_by='admin', update_time=NOW()
 WHERE menu_id=2001;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2039, '防碰评价', 2001, 1, '/drilling/tasks/anticollision', '', 'C', '0', '1', 'drilling:collision:run', 'form', 'admin', NOW(), '全邻井防碰评价'),
    (2040, '姿态预测', 2001, 2, '/drilling/tasks/prediction', '', 'C', '0', '1', 'drilling:prediction:list', 'fa fa-line-chart', 'admin', NOW(), '预测任务训练任务和模型仓库'),
    (2041, '轨迹再规划', 2001, 3, '/drilling/tasks/replanning', '', 'C', '0', '1', 'drilling:plan:run', 'fa fa-random', 'admin', NOW(), 'SF 约束轨迹再规划'),
    (2042, '基础功能', 2000, 2, '#', '', 'M', '0', '1', '', 'fa fa-cog', 'admin', NOW(), '钻井基础数据和配置'),
    (2043, '区块 / 井场 / 项目', 2042, 1, '/drilling/basic/catalog', '', 'C', '0', '1', 'drilling:project:view', 'fa fa-map', 'admin', NOW(), '项目层级'),
    (2044, '井与测斜数据', 2042, 2, '/drilling/basic/surveys', '', 'C', '0', '1', 'drilling:well:list', 'fa fa-database', 'admin', NOW(), '井和测斜版本'),
    (2045, '实时数据接入', 2042, 3, '/drilling/basic/realtime', '', 'C', '0', '1', 'drilling:project:view', 'fa fa-plug', 'admin', NOW(), 'WITS 和实时事件'),
    (2046, '系统配置', 2042, 4, '/drilling/basic/config', '', 'C', '0', '1', 'drilling:project:config', 'fa fa-cog', 'admin', NOW(), '坐标误差模型和算法配置'),
    (2047, '文件与审计', 2042, 5, '/drilling/basic/files', '', 'C', '0', '1', 'drilling:file:list', 'fa fa-folder-open', 'admin', NOW(), '项目文件和审计记录')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    url=VALUES(url), menu_type=VALUES(menu_type), visible=VALUES(visible),
    is_refresh=VALUES(is_refresh), perms=VALUES(perms), icon=VALUES(icon),
    update_by='admin', update_time=NOW(), remark=VALUES(remark);

UPDATE sys_menu SET visible='1', update_by='admin', update_time=NOW()
 WHERE menu_id IN (2002, 2004, 2005, 2006, 2007, 2010, 2011, 2012, 2013,
                   2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022,
                   2023, 2024, 2025, 2026, 2027, 2028, 2029, 2030);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id IN (2000, 2001, 2039, 2040, 2041, 2042, 2043, 2044, 2045, 2046, 2047);

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V16', 'V16__drilling_menu_restructure.sql', 'APPLIED', 'compose-bootstrap',
        '按任务中心和基础功能拆分实时钻井系统菜单');
