-- 阶段 15（决策 15-27）：重跑一次 0104 里的 AUTH→OP 归一，只碰 ACTION 行。
--
-- 为什么要有第二条一模一样的迁移：升级库 uav_stage10_verify 上 0104 已经跑过并记进
-- flyway_schema_history，但**同一次启动里**紧随其后的 SuperAdminIntegrityInitializer 又把那 42 行
-- 刷回了 AUTH（Flyway 在 ApplicationRunner 之前跑）。0104 不会再跑，存量就一直留在库里。
-- 初始化器已限定只作用于 MODULE 行（决策 15-25 修订），所以这一条跑完之后不会再被覆盖。
--
-- 已知的 AUTH 动作行全部属于 ROLE-ADMIN，而超级管理员的权限根本不从这些行来
-- （AccessService 对超管直接按整份目录展开），所以这些行上的等级是不起作用的存量数据。
-- 若将来出现**非超管**角色的 AUTH 动作行，归一到 OP 是"授予操作权"而不是收紧，届时要重新判断。

UPDATE app_role_permission SET permission_level = 'OP'
WHERE permission_level = 'AUTH'
  AND permission_code IN (SELECT permission_code FROM app_permission WHERE permission_kind = 'ACTION');
