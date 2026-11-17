-- V39: use the engineering term "测试案例" for the read-only A/B/C records.
UPDATE sys_menu
   SET menu_name='算法测试案例', remark='算法项目 A/B/C 的只读测试案例',
       update_by='system', update_time=NOW()
 WHERE url='/drilling/tasks/demonstrations';

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V39','V39__rename_algorithm_test_menu.sql','APPLIED','migration','规范算法测试案例菜单名称');
