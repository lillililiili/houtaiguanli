-- ZT-14：账号数据范围可设为“全部单位 / 本单位 / 本单位及下级单位”。
-- scope_org_rule 为空时沿用 scope_mode 原义（ALL 全部、ASSIGNED 手工授权元组、NONE 无业务数据）；
-- 非空时 scope_mode 必为 ASSIGNED，app_user_data_scope 中该账号的元组由服务端按所属单位和单位层级重建，
-- 各业务查询仍按既有“单位 + 区域”元组取交集，不另开一套范围判断。
ALTER TABLE app_user ADD COLUMN scope_org_rule VARCHAR(16);

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_scope_org_rule CHECK (
    scope_org_rule IS NULL
    OR (scope_org_rule IN ('OWN_ORG', 'OWN_ORG_TREE') AND scope_mode = 'ASSIGNED'));
