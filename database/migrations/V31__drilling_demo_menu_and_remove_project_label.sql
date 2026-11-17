-- Register the built-in read-only algorithm demonstrations under task center
-- and remove legacy project wording from the engineering catalog navigation.
INSERT IGNORE INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time)
VALUES
  (7040,'算法案例演示',7002,2,'/drilling/tasks/demonstrations','','C','0','1','','example','system',NOW());
INSERT IGNORE INTO drilling_menu_setting(menu_id,route_path,module_key,builtin)
VALUES(7040,'/drilling/tasks/demonstrations','demonstrations',TRUE);
UPDATE sys_menu
   SET menu_name='工程资料目录', icon='tree', update_by='system', update_time=NOW()
 WHERE url='/drilling/basic/catalog'
   AND menu_name IN ('区块 / 井场 / 项目','区块/井场/项目');

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V31','V31__drilling_demo_menu_and_remove_project_label.sql','APPLIED','application','增加只读算法案例演示入口并更新工程目录菜单名称');
