-- RuoYi 菜单与权限迁移。
-- 该文件依赖 ruoyi-java/sql/ry_20260319.sql 中的 sys_menu、sys_role_menu。
-- 使用 INSERT ... ON DUPLICATE KEY UPDATE 保证重复执行不会产生重复菜单。

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2000, '钻井工程', 0, 4, '#', '', 'M', '0', '1', '', 'fa fa-compass', 'admin', NOW(), '井眼轨迹工程模块'),
    (2001, '轨迹工作台', 2000, 1, '/drilling/workbench', '', 'C', '0', '1', 'drilling:project:view', 'fa fa-dashboard', 'admin', NOW(), '轨迹计算、防碰和规划工作台'),
    (2002, '项目查询', 2000, 2, '#', '', 'F', '1', '1', 'drilling:project:list', 'fa fa-list', 'admin', NOW(), '项目列表权限'),
    (2003, '项目查看', 2000, 3, '#', '', 'F', '1', '1', 'drilling:project:view', 'fa fa-eye', 'admin', NOW(), '项目查看权限'),
    (2004, '项目新增', 2000, 4, '#', '', 'F', '1', '1', 'drilling:project:add', 'fa fa-plus', 'admin', NOW(), '项目新增权限'),
    (2005, '项目修改', 2000, 5, '#', '', 'F', '1', '1', 'drilling:project:edit', 'fa fa-edit', 'admin', NOW(), '项目修改权限'),
    (2006, '项目归档', 2000, 6, '#', '', 'F', '1', '1', 'drilling:project:remove', 'fa fa-trash', 'admin', NOW(), '项目归档权限'),
    (2007, '项目成员', 2000, 7, '#', '', 'F', '1', '1', 'drilling:project:member', 'fa fa-users', 'admin', NOW(), '项目成员授权权限'),
    (2008, '项目配置', 2000, 8, '#', '', 'F', '1', '1', 'drilling:project:config', 'fa fa-cog', 'admin', NOW(), '坐标和算法配置权限'),
    (2009, '井管理', 2000, 9, '#', '', 'F', '1', '1', 'drilling:well:list', 'fa fa-database', 'admin', NOW(), '井和测斜版本权限'),
    (2010, '井新增', 2000, 10, '#', '', 'F', '1', '1', 'drilling:well:add', 'fa fa-plus', 'admin', NOW(), '井新增权限'),
    (2011, '井修改', 2000, 11, '#', '', 'F', '1', '1', 'drilling:well:edit', 'fa fa-edit', 'admin', NOW(), '井修改权限'),
    (2012, '井归档', 2000, 12, '#', '', 'F', '1', '1', 'drilling:well:remove', 'fa fa-trash', 'admin', NOW(), '井归档权限'),
    (2013, '测斜导入', 2000, 13, '#', '', 'F', '1', '1', 'drilling:well:survey:import', 'fa fa-upload', 'admin', NOW(), '测斜版本导入权限'),
    (2014, '防碰评价', 2000, 14, '#', '', 'F', '1', '1', 'drilling:collision:run', 'fa fa-warning', 'admin', NOW(), '防碰评价权限'),
    (2015, '轨迹规划', 2000, 15, '#', '', 'F', '1', '1', 'drilling:plan:run', 'fa fa-random', 'admin', NOW(), '轨迹规划权限'),
    (2016, '预测任务', 2000, 16, '#', '', 'F', '1', '1', 'drilling:prediction:list', 'fa fa-line-chart', 'admin', NOW(), '预测业务任务权限'),
    (2017, '训练任务', 2000, 17, '#', '', 'F', '1', '1', 'drilling:training:list', 'fa fa-tasks', 'admin', NOW(), '训练任务权限')
    ,(2018, '训练任务提交', 2000, 18, '#', '', 'F', '1', '1', 'drilling:training:run', 'fa fa-play', 'admin', NOW(), '训练任务提交与终止权限')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    url = VALUES(url), target = VALUES(target), menu_type = VALUES(menu_type),
    visible = VALUES(visible), is_refresh = VALUES(is_refresh), perms = VALUES(perms),
    icon = VALUES(icon), update_by = 'admin', update_time = NOW(), remark = VALUES(remark);

-- 普通角色获得只读工作台和查询权限；管理员角色在 RuoYi 中拥有全量权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id IN (2000, 2001, 2002, 2003, 2009, 2014, 2015, 2016, 2017);
