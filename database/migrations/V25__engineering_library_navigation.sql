-- Retain saved permissions and old bookmark redirects; rename only the
-- built-in catalog label. User-customized labels are left unchanged.
UPDATE sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id
 SET m.menu_name='工程资料目录'
 WHERE s.builtin=TRUE AND s.route_path='/drilling/basic/catalog'
 AND m.menu_name IN ('区块 / 井场 / 项目','区块/井场/项目');
