-- 只有 TDOA 看到的无人机（凌云协议 A 上报 objectType=无人机）此前在 demo-v1 里类别权重为 0：
-- 融合类别一直是"未知"，合法性研判只收明确为无人机的目标，于是闯进禁飞区也既不研判、也没有任何提示（验收问题 BUG-14）。
-- 产品方要求与单源 5G-A 同口径，TDOA 的类别权重取与 5G-A 相同的 0.2（DEMO 值，未经算法方确认）。
-- 单源目标的融合置信度仍低于 C03.conf_min，研判给"不可判定"并进入人工复核，不会只凭 TDOA 直接判违规。
-- 只改 weights.TDOA.class 这一个键，其余参数原样保留；JSON 列在 PostgreSQL 上借 jsonb_set 局部更新，
-- 只在仍为 0 时改，不覆盖已经调整过的值。H2 只用于测试，沿用迁移 050/070 的原值。
UPDATE fusion_config
   SET params = CAST(jsonb_set(CAST(params AS jsonb), '{weights,TDOA,class}', CAST('0.2' AS jsonb)) AS json),
       note = LEFT(COALESCE(note, '') || '；TDOA 类别权重按单源 5G-A 同口径取 0.2（DEMO）', 500)
 WHERE config_version = 'demo-v1'
   AND CAST(CAST(params AS jsonb) #>> '{weights,TDOA,class}' AS numeric) = 0;
