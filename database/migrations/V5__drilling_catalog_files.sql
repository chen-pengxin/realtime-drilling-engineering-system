-- 区块/井场和项目文件管理权限。
-- 依赖若依 sys_menu 与钻井工程根菜单 (V3)。
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2020, '区块查询', 2000, 20, '#', '', 'F', '1', '1', 'drilling:block:list', 'fa fa-map', 'admin', NOW(), '区块查询权限'),
    (2021, '区块新增', 2000, 21, '#', '', 'F', '1', '1', 'drilling:block:add', 'fa fa-plus', 'admin', NOW(), '区块新增权限'),
    (2022, '区块修改', 2000, 22, '#', '', 'F', '1', '1', 'drilling:block:edit', 'fa fa-edit', 'admin', NOW(), '区块修改权限'),
    (2023, '区块归档', 2000, 23, '#', '', 'F', '1', '1', 'drilling:block:remove', 'fa fa-trash', 'admin', NOW(), '区块归档权限'),
    (2024, '井场查询', 2000, 24, '#', '', 'F', '1', '1', 'drilling:field:list', 'fa fa-map-marker', 'admin', NOW(), '井场查询权限'),
    (2025, '井场新增', 2000, 25, '#', '', 'F', '1', '1', 'drilling:field:add', 'fa fa-plus', 'admin', NOW(), '井场新增权限'),
    (2026, '井场修改', 2000, 26, '#', '', 'F', '1', '1', 'drilling:field:edit', 'fa fa-edit', 'admin', NOW(), '井场修改权限'),
    (2027, '井场归档', 2000, 27, '#', '', 'F', '1', '1', 'drilling:field:remove', 'fa fa-trash', 'admin', NOW(), '井场归档权限'),
    (2028, '项目文件查询', 2000, 28, '#', '', 'F', '1', '1', 'drilling:file:list', 'fa fa-folder-open', 'admin', NOW(), '项目文件查询与下载权限'),
    (2029, '项目文件上传', 2000, 29, '#', '', 'F', '1', '1', 'drilling:file:upload', 'fa fa-upload', 'admin', NOW(), '项目文件上传权限'),
    (2030, '项目文件归档', 2000, 30, '#', '', 'F', '1', '1', 'drilling:file:remove', 'fa fa-trash', 'admin', NOW(), '项目文件归档权限')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    url=VALUES(url), target=VALUES(target), menu_type=VALUES(menu_type), visible=VALUES(visible),
    is_refresh=VALUES(is_refresh), perms=VALUES(perms), icon=VALUES(icon),
    update_by='admin', update_time=NOW(), remark=VALUES(remark);

-- 普通工程角色默认只读目录和文件；写入能力仍由管理角色/授权角色分配。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id IN (2020, 2024, 2028);
