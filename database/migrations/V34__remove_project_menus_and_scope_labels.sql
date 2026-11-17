-- V34: remove the obsolete engineering-project navigation and permissions.
-- V1-V33 are historical upgrade sources; after this migration the live menu
-- tree and live engineering tables use only block -> field -> well -> wellbore.

SET FOREIGN_KEY_CHECKS=0;

-- Carry role visibility from the retired catalog route to its field-based
-- replacement before removing the old permission row.
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT old_role.role_id,new_menu.menu_id
  FROM sys_role_menu old_role
  JOIN sys_menu old_menu ON old_menu.menu_id=old_role.menu_id
  JOIN sys_menu new_menu ON new_menu.url='/drilling/basic/catalog'
 WHERE old_menu.menu_id=2043 OR old_menu.perms='drilling:project:view';

-- Delete legacy project management actions and their role bindings.  The
-- current field, well and task permissions are seeded by the later menu rows.
DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
 WHERE m.menu_id IN (2002,2003,2004,2005,2006,2007,2008)
    OR m.perms LIKE 'drilling:project:%';
DELETE s FROM drilling_menu_setting s LEFT JOIN sys_menu m ON m.menu_id=s.menu_id
 WHERE m.menu_id IS NULL OR m.menu_id IN (2002,2003,2004,2005,2006,2007,2008)
    OR m.perms LIKE 'drilling:project:%';
DELETE FROM sys_menu
 WHERE menu_id IN (2002,2003,2004,2005,2006,2007,2008)
    OR perms LIKE 'drilling:project:%';

-- Older installations may have inserted the same catalog under a different
-- id.  Keep the route and change its permission to the field read permission.
UPDATE sys_menu
   SET menu_name='工程资料目录', perms='drilling:field:view', icon='tree',
       remark='区块、井场、井、井眼及成果资料', update_by='system', update_time=NOW()
 WHERE url='/drilling/basic/catalog';

UPDATE sys_menu
   SET menu_name='文件与审计', remark='工程文件、版本历史和审计记录',
       update_by='system', update_time=NOW()
 WHERE url='/drilling/basic/files';

UPDATE sys_menu
   SET menu_name='系统配置', perms='drilling:field:config',
       remark='井场坐标、误差模型和算法参数', update_by='system', update_time=NOW()
 WHERE url='/drilling/basic/config';

UPDATE sys_dept SET dept_name='井场协调组', update_by='system', update_time=NOW()
 WHERE dept_name='项目协调组';
UPDATE sys_post SET post_name='井场负责人', update_by='system', update_time=NOW()
 WHERE post_name IN ('项目经理','钻井项目经理');

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V34','V34__remove_project_menus_and_scope_labels.sql','APPLIED','migration','删除工程项目菜单和权限，统一为区块、井场、井、井眼层级');

SET FOREIGN_KEY_CHECKS=1;
