-- 撤除飞行计划外部授权登记（需求确认表增补四 F8，用户 2026-09-09 裁定：页面、后端一并去掉）。
--
-- 阶段 9 迁移 060 登记了动作权限 flight:authorize，064 建了 flight_plan_authorization。该功能是"把别处批下来的
-- 文号补登记到计划上"，客户业务里没有确认存在这种场景，合法性研判也从未读取它。前端区块已先行删除（4d10546），
-- 这里把后端能力也收干净：目录里的权限行、各角色上的授予、以及登记表本身。
-- 表内数据：该功能从未在演示或生产库里登记过真实文号（生产隔离测试一直断言该表为空），删表不丢业务事实。
-- PostgreSQL 只增触发器随表消失；遗留的触发器函数由 db/postgresql/R__stage9_airspace_succession.sql 清理。
DELETE FROM app_role_permission WHERE permission_code = 'flight:authorize';
DELETE FROM app_permission WHERE permission_code = 'flight:authorize';
DROP TABLE flight_plan_authorization;
