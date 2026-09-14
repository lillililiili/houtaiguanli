-- 阶段 8 效果视图（助手）。依赖 051（source_observation、track.layer、track_point.point_kind/observation_id）、
-- 052（target_lineage、target_current_alias）；不触及任何 JSON 列。H2 与 PostgreSQL 都要能建。

-- 回放真值：数据集内每条来源观测对应的物理目标键。只有 local/test 的回放生成器写入，生产没有真值，
-- 因此依赖真值的指标（重复目标率、关联准确率）在生产恒为 NULL，而不是 0。
CREATE TABLE replay_ground_truth (
    dataset_id          VARCHAR(64) NOT NULL,
    scenario            VARCHAR(64) NOT NULL,
    record_no           BIGINT NOT NULL,
    true_target_key     VARCHAR(128) NOT NULL,
    source_code         VARCHAR(64) NOT NULL,
    external_target_id  VARCHAR(128) NOT NULL,
    observed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage8_replay_ground_truth PRIMARY KEY (dataset_id, source_code, external_target_id, observed_at),
    CONSTRAINT ck_stage8_ground_truth_dataset CHECK (TRIM(dataset_id) <> ''),
    CONSTRAINT ck_stage8_ground_truth_scenario CHECK (TRIM(scenario) <> ''),
    CONSTRAINT ck_stage8_ground_truth_key CHECK (TRIM(true_target_key) <> ''),
    CONSTRAINT ck_stage8_ground_truth_record CHECK (record_no >= 0)
);

CREATE INDEX idx_stage8_ground_truth_observation ON replay_ground_truth (source_code, external_target_id, observed_at);
CREATE INDEX idx_stage8_ground_truth_dataset_key ON replay_ground_truth (dataset_id, true_target_key);

-- 融合效果按 (source_mode, owner_org_id, district_id, day) 汇总；fusion_domain_key 与 FusionDomainKey.asKey() 同形。
-- 四个事实源各自先按分区与日历日聚合，再 UNION ALL 后求和：避免 FULL JOIN 的方言差异，也避免多对多联接放大计数。
--   tracked_targets      当日在 FUSED 层有轨迹点的目标数
--   id_switch_count      血缘中 SWITCH/SPLIT/MERGE 的次数（分区取幸存者/原目标的归属）
--   interrupt_rate       FUSED 层 PRED 点（无源预测帧）/ FUSED 层全部点；无帧为 NULL
--   duplicate_target_rate 同一真值键当日映射到 ≥2 个（按 alias 归并后的）目标的真值键数 / 当日有映射的真值键数；无真值为 NULL
--   association_accuracy 每个真值键的主导目标观测数之和 / 有真值的观测总数；无真值为 NULL
-- 日历日按 UTC 取，避免依赖数据库会话时区。列名 day 在 H2 是关键字，统一加引号（DATABASE_TO_LOWER 下与 PostgreSQL 同名）。
CREATE VIEW fusion_effect_daily AS
SELECT
    x.source_mode || '|' || COALESCE(x.owner_org_id, '') || '|' || COALESCE(x.district_id, '') AS fusion_domain_key,
    x.source_mode,
    x.owner_org_id,
    x.district_id,
    x."day",
    SUM(x.tracked_targets) AS tracked_targets,
    SUM(x.id_switch_count) AS id_switch_count,
    SUM(x.short_lost_frames) AS short_lost_frames,
    SUM(x.total_frames) AS total_frames,
    CASE WHEN SUM(x.total_frames) > 0
         THEN ROUND(CAST(SUM(x.short_lost_frames) AS NUMERIC(14, 4)) / SUM(x.total_frames), 4) END AS interrupt_rate,
    SUM(x.duplicate_targets) AS duplicate_targets,
    SUM(x.truth_targets) AS truth_targets,
    CASE WHEN SUM(x.truth_targets) > 0
         THEN ROUND(CAST(SUM(x.duplicate_targets) AS NUMERIC(14, 4)) / SUM(x.truth_targets), 4) END AS duplicate_target_rate,
    SUM(x.correct_associations) AS correct_associations,
    SUM(x.total_associations) AS total_associations,
    CASE WHEN SUM(x.total_associations) > 0
         THEN ROUND(CAST(SUM(x.correct_associations) AS NUMERIC(14, 4)) / SUM(x.total_associations), 4) END AS association_accuracy
FROM (
    SELECT t.source_mode, t.owner_org_id, t.district_id,
           CAST(p.observed_at AT TIME ZONE 'UTC' AS DATE) AS "day",
           COUNT(DISTINCT tr.target_id) AS tracked_targets,
           0 AS id_switch_count,
           SUM(CASE WHEN p.point_kind = 'PRED' THEN 1 ELSE 0 END) AS short_lost_frames,
           COUNT(*) AS total_frames,
           0 AS duplicate_targets, 0 AS truth_targets, 0 AS correct_associations, 0 AS total_associations
    FROM track_point p
    JOIN track tr ON tr.track_id = p.track_id AND tr.layer = 'FUSED'
    JOIN target t ON t.target_id = tr.target_id
    WHERE p.observed_at IS NOT NULL
    GROUP BY t.source_mode, t.owner_org_id, t.district_id, CAST(p.observed_at AT TIME ZONE 'UTC' AS DATE)
  UNION ALL
    SELECT t.source_mode, t.owner_org_id, t.district_id,
           CAST(l.occurred_at AT TIME ZONE 'UTC' AS DATE) AS "day",
           0, COUNT(*), 0, 0, 0, 0, 0, 0
    FROM target_lineage l
    JOIN target t ON t.target_id = COALESCE(l.survivor_target_id, l.origin_target_id)
    WHERE l.op IN ('SWITCH', 'SPLIT', 'MERGE')
    GROUP BY t.source_mode, t.owner_org_id, t.district_id, CAST(l.occurred_at AT TIME ZONE 'UTC' AS DATE)
  UNION ALL
    SELECT k.source_mode, k.owner_org_id, k.district_id, k."day",
           0, 0, 0, 0,
           SUM(CASE WHEN k.target_count > 1 THEN 1 ELSE 0 END),
           COUNT(*),
           SUM(k.dominant_observations),
           SUM(k.total_observations)
    FROM (
        SELECT m.source_mode, m.owner_org_id, m.district_id, m."day", m.true_target_key,
               COUNT(*) AS target_count,
               MAX(m.observations) AS dominant_observations,
               SUM(m.observations) AS total_observations
        FROM (
            -- 真值观测经 RAW 层轨迹点回到目标；被并目标经 alias 归到当前目标，所以人工/自动合并后重复率会下降。
            SELECT t.source_mode, t.owner_org_id, t.district_id,
                   CAST(o.observed_at AT TIME ZONE 'UTC' AS DATE) AS "day",
                   g.true_target_key,
                   COALESCE(a.current_target_id, rt.target_id) AS resolved_target_id,
                   COUNT(*) AS observations
            FROM replay_ground_truth g
            JOIN integration_source s ON s.source_code = g.source_code
            JOIN source_observation o ON o.source_id = s.source_id
                 AND o.external_target_id = g.external_target_id AND o.observed_at = g.observed_at
            JOIN track_point rp ON rp.observation_id = o.observation_id
            JOIN track rt ON rt.track_id = rp.track_id AND rt.layer = 'RAW'
            LEFT JOIN target_current_alias a ON a.historical_target_id = rt.target_id
            JOIN target t ON t.target_id = rt.target_id
            GROUP BY t.source_mode, t.owner_org_id, t.district_id, CAST(o.observed_at AT TIME ZONE 'UTC' AS DATE),
                     g.true_target_key, COALESCE(a.current_target_id, rt.target_id)
        ) m
        GROUP BY m.source_mode, m.owner_org_id, m.district_id, m."day", m.true_target_key
    ) k
    GROUP BY k.source_mode, k.owner_org_id, k.district_id, k."day"
) x
GROUP BY x.source_mode, x.owner_org_id, x.district_id, x."day";
