-- 权限目录的模块名和排序与当前侧栏/顶栏菜单对齐。
-- 权限编码保持不变；无菜单入口的能力（空域、风险、反制、接口）仍不出现在角色矩阵中。

UPDATE app_permission SET module_name = '数据大屏', sort_order = 10 WHERE permission_code = 'dashboard';
UPDATE app_permission SET module_name = '感知监测', sort_order = 20 WHERE permission_code = 'sensing';
UPDATE app_permission SET module_name = '飞行监管', sort_order = 30 WHERE permission_code = 'flights';
UPDATE app_permission SET module_name = '飞行监管', sort_order = 40 WHERE permission_code = 'legality';
UPDATE app_permission SET module_name = '飞行监管', sort_order = 41 WHERE permission_code = 'airspace';
UPDATE app_permission SET module_name = '飞行监管', sort_order = 42 WHERE permission_code = 'risk';
UPDATE app_permission SET module_name = '事件处置', sort_order = 50 WHERE permission_code = 'alarms';
UPDATE app_permission SET module_name = '事件处置', sort_order = 60 WHERE permission_code = 'punishment';
UPDATE app_permission SET module_name = '事件处置', sort_order = 61 WHERE permission_code = 'countermeasure';
UPDATE app_permission SET module_name = '分析报告', sort_order = 70 WHERE permission_code = 'statistics';
UPDATE app_permission SET module_name = '分析报告', sort_order = 80 WHERE permission_code = 'evidence';
UPDATE app_permission SET module_name = '运维管理', sort_order = 90 WHERE permission_code = 'devices';
UPDATE app_permission SET module_name = '运维管理', sort_order = 100 WHERE permission_code = 'monitoring';
UPDATE app_permission SET module_name = '运维管理', sort_order = 110 WHERE permission_code = 'commissioning';
UPDATE app_permission SET module_name = '运维管理', sort_order = 111 WHERE permission_code = 'interfaces';
UPDATE app_permission SET module_name = '系统管理', sort_order = 170 WHERE permission_code = 'users';
UPDATE app_permission SET module_name = '系统管理', sort_order = 180 WHERE permission_code = 'roles';
UPDATE app_permission SET module_name = '系统管理', sort_order = 190 WHERE permission_code = 'audit';
