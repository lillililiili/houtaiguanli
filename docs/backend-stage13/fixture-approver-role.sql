-- 阶段 13/14 验收夹具（仅本地/验收库，勿在生产执行）：
-- 给"两人规则"（处置审批人 ≠ 申请人）和"复核人 ≠ 承办人"造一个独立的第二人角色。角色矩阵接口目前只接受 MODULE 码，ACTION 码（disposal:*）没有授予入口，
-- 因此这几行直接落库。用户账号请在"系统管理 → 用户"里正常创建并选择该角色（role_code = ROLE-S13-APPROVER）。
-- 执行方式（Docker 内 PostgreSQL）：
--   docker exec -i deploy-db-1 psql -U uav -d <验收库名> < docs/backend-stage13/fixture-approver-role.sql

INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version, system_role)
SELECT 'ROLE-S13-APPROVER', '处置审批演示角色', '阶段 13 验收夹具：ACTION 码无角色矩阵入口，直接落库', false, true, 0, 0, 0, false
WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE role_code = 'ROLE-S13-APPROVER');

-- 只授处置域与处罚复核的动作权限；模块菜单（alarm 等）请在角色矩阵页里按需勾选。
INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
SELECT 'ROLE-S13-APPROVER', c, 'OP', false, current_timestamp
FROM unnest(ARRAY['disposal:read', 'disposal:approve', 'disposal:execute', 'disposal:stop',
                  'punishment:read', 'punishment:review', 'handoff:read', 'alarm:read']) AS c
WHERE EXISTS (SELECT 1 FROM app_permission WHERE permission_code = c)
  AND NOT EXISTS (SELECT 1 FROM app_role_permission WHERE role_code = 'ROLE-S13-APPROVER' AND permission_code = c);
