# 阶段 8 融合效果指标（视图 `fusion_effect_daily`，迁移 054）

> 状态：助手起草（2026-09-06）。指标只读自数据库视图，`GET /api/v1/fusion/metrics/daily` 不做二次计算。所有阈值不参与指标定义；指标是对融合结果的事后度量，不改变任何目标状态。

## 分区与日历日

- 分区 = `fusion_domain_key = source_mode|owner_org_id|district_id`（与 `FusionContracts.FusionDomainKey.asKey()` 同形）。归属取 **目标** 的 `owner_org_id/district_id`，不取观测的（观测归属可空）。
- `day` 按 **UTC** 日历日折算（`observed_at AT TIME ZONE 'UTC'`），不依赖数据库会话时区。API 的 `from/to`（epoch 毫秒，`[from,to)`）按 UTC 折成闭区间 `[from 日, (to−1ms) 日]`。
- 范围：`fusion:read` 的读者按 `ASSIGNED` 精确元组或 `ALL` 过滤；`ALL` 仍要求组织与区域目录存在且启用；分区缺归属的行对任何范围不可见。

## 指标定义

| 列 | 分子 | 分母 | 分母为 0 时 |
| --- | --- | --- | --- |
| `tracked_targets` | 当日在 FUSED 层（`track.layer='FUSED'`）有至少一个 `track_point` 的目标数 | — | 0 |
| `id_switch_count` | `target_lineage.op ∈ {SWITCH, SPLIT, MERGE}` 的行数；分区取 `COALESCE(survivor_target_id, origin_target_id)` 所指目标的归属 | — | 0 |
| `interrupt_rate` | `short_lost_frames`：FUSED 层 `point_kind='PRED'` 的点数（引擎只在无源短失联时写 PRED） | `total_frames`：FUSED 层全部点数（MEAS+BRIDGE+PRED） | `NULL` → API `availability=NO_DENOMINATOR` |
| `duplicate_target_rate` | `duplicate_targets`：当日映射到 ≥2 个目标的真值键数 | `truth_targets`：当日有映射的真值键数 | `NULL` → API `availability=NO_GROUND_TRUTH` |
| `association_accuracy` | `correct_associations`：每个真值键的“主导目标”（观测最多的目标）的观测数之和 | `total_associations`：有真值且已关联到目标的观测总数 | `NULL` → API `availability=NO_GROUND_TRUTH` |

真值路径：`replay_ground_truth(source_code, external_target_id, observed_at)` → `integration_source.source_code` → `source_observation(source_id, external_target_id, observed_at)` → RAW 层 `track_point.observation_id` → `track(layer='RAW').target_id` → `target_current_alias`（被并目标归到当前目标）。因此自动/人工合并后重复率下降、关联准确率上升；分裂产生的新目标在真值键下被计为重复，直到真值键本身区分两条物理目标。

## 无真值时的表现

生产没有 `replay_ground_truth`（只有 local/test 的回放生成器写入），`duplicate_target_rate` 与 `association_accuracy` 恒为 `NULL`，API 返回 `{"value": null, "availability": "NO_GROUND_TRUTH"}`；`tracked_targets`、`id_switch_count`、`interrupt_rate` 不依赖真值，生产可用。没有任何 FUSED 层点的日子不会出现在视图里（没有行，不是 0 行）。

## API

```text
GET /api/v1/fusion/metrics/daily?from=<epoch ms>&to=<epoch ms>[&domain=<mode|org|district>]   fusion:read
→ {from, to, domain, items:[{fusion_domain_key, source_mode, owner_org_id, district_id, day:"YYYY-MM-DD",
     tracked_targets, id_switch_count, short_lost_frames, total_frames, interrupt_rate:{value,availability},
     duplicate_targets, truth_targets, duplicate_target_rate:{value,availability},
     correct_associations, total_associations, association_accuracy:{value,availability}}]}
```

`from/to` 必填且 `from < to`（否则 400 `INVALID_TIME_RANGE`）；未知/重复参数 400 `VALIDATION_ERROR`；`domain` 必须是 `mode|org|district` 形（可为空段）。缺 `fusion:read` 403，先于任何参数解析。比率保留 4 位小数（`ROUND(…, 4)`）。

## 与契约的差异（需要领导裁定）

- 契约把视图列写成 `fusion_effect_daily(fusion_domain_key, day, tracked_targets, id_switches, discontinuity_rate, duplicate_target_rate, association_accuracy)`，简报把列写成 `(source_mode, owner_org_id, district_id, day, id_switch_count, interrupt_rate, duplicate_target_rate, association_accuracy)`。实现取并集：同时给出 `fusion_domain_key` 与三段归属列，计数列用简报名（`id_switch_count`、`interrupt_rate`），并额外暴露分子/分母列供 API 判定 availability。
- `replay_ground_truth` 按简报列 `(dataset_id, scenario, record_no, true_target_key, source_code, external_target_id, observed_at)`，主键 `(dataset_id, source_code, external_target_id, observed_at)`；契约写的是 `(dataset_id, source_id, external_target_id, physical_target_key, observed_at)`。E1 的回放生成器写真值时以本文列名为准；不一致时以领导裁定为准并同步 054。
