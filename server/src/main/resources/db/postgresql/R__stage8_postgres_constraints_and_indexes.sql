-- 阶段 8 融合引擎 PostgreSQL 专属约束与索引（H2 测试不加载本目录）。
-- 领导骨架：只含迁移 050 已有对象的约束；E1（051/052）与 E2（053）各自在标注段落内追加，领导合并时只做拼接不改语义。
-- 约定：所有 CREATE 都必须可重复执行（R__ 文件每次校验和变化都会重跑）。

-- ---------- 050：融合参数版本 ----------
-- 任何时刻只能有一个 ACTIVE 的参数版本：激活是"旧 ACTIVE→RETIRED + 新→ACTIVE"同事务完成，并发激活由本索引兜底为一成一败。
CREATE UNIQUE INDEX IF NOT EXISTS uk_stage8_fusion_config_single_active
    ON fusion_config (status) WHERE status = 'ACTIVE';

-- ---------- 051/052（E1 追加）：source_observation / track 分层 / track_point 扩展 / association_pending / target_track_status / target_lineage / target_current_alias ----------
-- 来源观测位置必须是合法的 WGS-84 点：融合域层只用经纬度做米制换算，越界或非 4326 的点会污染所有关联距离。
DO $$
BEGIN
    ALTER TABLE source_observation DROP CONSTRAINT IF EXISTS ck_stage8_observation_location_wgs84;
    ALTER TABLE source_observation ADD CONSTRAINT ck_stage8_observation_location_wgs84 CHECK (
        location IS NULL
        OR (
            NOT ST_IsEmpty(location)
            AND ST_IsValid(location)
            AND ST_SRID(location) = 4326
            AND ST_X(location) BETWEEN -180 AND 180
            AND ST_Y(location) BETWEEN -90 AND 90
        )
    );
END
$$;
CREATE INDEX IF NOT EXISTS idx_stage8_observation_location_gist
    ON source_observation USING GIST (location);

-- 轨迹分层与 link 的等价关系：FUSED 层没有来源 link，RAW 层必须有；H2 不支持在 ALTER 后追加此类 CHECK 的可重复写法，只放 PostgreSQL。
DO $$
BEGIN
    ALTER TABLE track DROP CONSTRAINT IF EXISTS ck_stage8_track_layer_link;
    ALTER TABLE track ADD CONSTRAINT ck_stage8_track_layer_link CHECK (
        (layer = 'FUSED' AND link_id IS NULL)
        OR (layer = 'RAW' AND link_id IS NOT NULL)
    );
END
$$;

-- 血缘只增：合并/分裂/切换是目标身份的证据链，纠错只能追加新行。
CREATE OR REPLACE FUNCTION prevent_stage8_lineage_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'target lineage is append-only' USING ERRCODE = '23514';
END
$$;
DROP TRIGGER IF EXISTS trg_stage8_lineage_append_only ON target_lineage;
CREATE TRIGGER trg_stage8_lineage_append_only
BEFORE UPDATE OR DELETE ON target_lineage
FOR EACH ROW EXECUTE FUNCTION prevent_stage8_lineage_mutation();

CREATE INDEX IF NOT EXISTS idx_stage8_lineage_members_gin
    ON target_lineage USING GIN (member_target_ids);
CREATE INDEX IF NOT EXISTS idx_stage8_lineage_sources_gin
    ON target_lineage USING GIN (source_target_ids);

-- 别名不得自指（迁移 052 已在 H2/PG 都写了同名 CHECK；这里保证可重复执行时语义一致）。
DO $$
BEGIN
    ALTER TABLE target_current_alias DROP CONSTRAINT IF EXISTS ck_stage8_alias_distinct;
    ALTER TABLE target_current_alias ADD CONSTRAINT ck_stage8_alias_distinct CHECK (historical_target_id <> current_target_id);
END
$$;

-- 同一分区同一待定键只允许一条未落定的待定关联（H2 不支持部分唯一索引，只放 PostgreSQL）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_stage8_pending_open
    ON association_pending (fusion_domain_key, pending_key)
    WHERE resolved_at IS NULL;

-- 领导集成补充（审查 P2-5）：link_id 为 NULL 的 FUSED 层不受 uk_stage2_track_link_external 约束，用部分唯一索引守住 fused:<target>:<ts> 的唯一性。
CREATE UNIQUE INDEX IF NOT EXISTS uk_stage8_track_fused_external
    ON track (target_id, external_track_id) WHERE layer = 'FUSED';

-- ---------- 053（E2 追加）：target_attribute_selection / target_degradation / target_classification_revision / fusion_event ----------
-- fusion_event 只增：事件是态势页与后续统计的事实来源，纠错以新事件表达。
CREATE OR REPLACE FUNCTION prevent_stage8_fusion_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'fusion_event is append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage8_fusion_event_append_only ON fusion_event;
CREATE TRIGGER trg_stage8_fusion_event_append_only
BEFORE UPDATE OR DELETE ON fusion_event
FOR EACH ROW EXECUTE FUNCTION prevent_stage8_fusion_event_mutation();

-- 人工类别修订只增：修订历史与 target.version 一一对应，改写会让审计与版本链错位。
CREATE OR REPLACE FUNCTION prevent_stage8_classification_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'target_classification_revision is append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage8_classification_revision_append_only ON target_classification_revision;
CREATE TRIGGER trg_stage8_classification_revision_append_only
BEFORE UPDATE OR DELETE ON target_classification_revision
FOR EACH ROW EXECUTE FUNCTION prevent_stage8_classification_revision_mutation();

-- target_degradation.confidence_deficit ∈ [0,1] 已在迁移 053 的 CHECK 中声明（H2 与 PG 同一份）；这里只补查询索引。
CREATE INDEX IF NOT EXISTS idx_stage8_degradation_level ON target_degradation (level, determined, updated_at DESC);
