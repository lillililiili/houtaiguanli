# 阶段 8 融合引擎与融合感知页接口契约

> 状态：领导冻结稿（2026-09-06，v1.1：按决策 8-11/8-12 修订——前端不动、融合轨迹落 `track(layer='FUSED')`）。配套：`docs/backend-stage8/target-schema-v1-alignment.md`（6.5 关卡）、`docs/backend-stage8/decisions.md`、`docs/backend-stage1/t02-replay-contract.md`（回放信封）、`docs/backend-stage1/data-api-contract.md`（阶段 2 目标读契约，本文只追加可空字段）。

## 交付边界

融合引擎是阶段 2 统一目标库（`target / target_source_link / track / track_point / target_latest_state`）的唯一运行时写入者；输入是来源观测 `source_observation`，输出是阶段 2 表加新增融合表。**不在 ops 模型内融合，不改 `/api/v1/sensing/*`，不改 `ProtocolDataRepository/LiveDeviceSupervisor`。** 实测雷达 ops→阶段 2 提升不在本阶段（`SourceObservationPort` 预留，`app.fusion.live-promotion.enabled=false`）。三路来源按 Demo Schema（只有 RADAR 为 CONFIRMED），所有阈值/权重在 `fusion_config.params`（`schema_status=DEMO`）。回放数据全部 `source_mode='replay'`；生产不生成回放/模拟数据。

## 数据流与分区

```
NDJSON 回放行 ─FusionReplayRunner─▶ inbox_message(source_id, payload JSONB, payload_hash, status=RECEIVED)
   ▶ FusionIngestWorker @Scheduled(app.fusion.poll-millis:500)：条件更新租约（RECEIVED→PROCESSING，30 s，批 50，按 received_at,inbox_id）
   ▶ SourceObservation 解析 ▶ FusionPipeline（按 fusion_domain=(source_mode, owner_org_id, district_id) 串行）
       ① α-β 单源滤波 ② 门限 + 匈牙利关联 ③ ID 状态机 ④ 属性优选 + 加权融合 ⑤ 降级评估 ⑥ 写入
   ▶ source_observation / track(layer='RAW') / track_point（原始层） + track(layer='FUSED', link_id NULL) / track_point（融合层）
     + target / target_source_link / target_latest_state（融合结果） + target_track_status / target_lineage /
       target_current_alias / association_pending / target_degradation / target_attribute_selection
   ▶ inbox_message.status = DONE | FAILED(last_error)
```

一条 inbox 行 = 一帧（一个来源一个时刻的多目标观测），整帧一个事务，失败整帧回滚并 `FAILED`。领取时 `inbox_message.fusion_attempts` 加一，超过 `app.fusion.max-attempts`（默认 5）的过期租约行置 `FAILED`（决策 8-17）。租约 SQL 只作用于 `source_id IS NOT NULL AND payload IS NOT NULL AND source LIKE 'replay:%'`（不触碰 ops 的 `live-device:*` 行）。跨 `source_mode` 永不关联，因此 `TargetReadRepository` 的模式一致性 JOIN 不改。Worker 每帧更新 `target.last_seen_at/updated_at`，**不递增 `target.version`**（留给人工写）。

## 数据模型

| 迁移 | 所有者 | 内容 |
| --- | --- | --- |
| `V202609050050__stage8_fusion_permissions_and_catalog.sql` | 领导（已落地） | `fusion:read/revise/manage`（sort 950–952）；`source_type_catalog`；`integration_source.source_type`；`fusion_config(demo-v1 ACTIVE DEMO)` |
| `V202609050051__stage8_observation_and_raw_layers.sql` | E1 | `source_observation`、`track.layer/filter_state/config_version`（`link_id` 放开可空）、`track_point.point_kind/observation_id/position_accuracy_m/contributing/degradation_level/source_switched`、`association_pending` |
| `V202609050052__stage8_identity_and_status.sql` | E1 | `target_track_status`、`target_lineage`、`target_current_alias`、`target.unified` |
| `V202609050053__stage8_fused_layer_and_degradation.sql` | E2 | `target_attribute_selection`、`target_degradation`、`target_classification_revision`、`fusion_event` |
| `V202609050054__stage8_effect_views.sql` | 助手 | `replay_ground_truth`、视图 `fusion_effect_daily` |
| `db/postgresql/R__stage8_postgres_constraints_and_indexes.sql` | 领导骨架，E1/E2 各追加片段 | WGS-84 检查、GIST、`fusion_config` 单 ACTIVE 部分唯一、`target_lineage` GIN、只增触发器 |

字段（列名即契约）：

- `source_observation(observation_id PK, inbox_id?, source_id → integration_source, device_id?, source_type → catalog, source_session_key, external_target_id, external_track_id?, observed_at, received_at, location POINT 4326?, position_accuracy_m? >0, altitude_amsl_m?, height_agl_m?, speed_mps? ≥0, heading_deg? [0,360), class_code?, class_confidence? [0,1], identity_clue?, identity_confidence?, latency_ms? ≥0, quality JSON, source_mode, owner_org_id?, district_id?, created_at)`，`UNIQUE(source_id, source_session_key, external_target_id, observed_at)`
- `track ADD layer VARCHAR(8) NOT NULL DEFAULT 'RAW' CHECK IN ('RAW','FUSED'), filter_state JSON, config_version?`，`link_id` 改为可空（PG CHECK：`layer='FUSED'` ⇔ `link_id IS NULL`）；`external_track_id` 在 FUSED 层写 `fused:<target_id>:<started_at_ms>`；`track_point ADD point_kind VARCHAR(8) NOT NULL DEFAULT 'MEAS' CHECK IN ('MEAS','BRIDGE','PRED'), observation_id?, position_accuracy_m? >0, contributing JSON? [{source_id, observation_id, weight}], position_source_id?, source_switched BOOLEAN NOT NULL DEFAULT FALSE, degradation_level?`
- `association_pending(pending_id, fusion_domain_key, pending_key, observation_id, candidate_target_ids JSON, reason ONE_TO_MANY|MANY_TO_ONE|GATE_AMBIGUOUS, first_seen_at, last_seen_at, frames_seen, resolved_at?, resolution?, created_at)`（`pending_key`/`last_seen_at` 为 E1 实现追加，v1.2）
- `target_track_status(target_id PK, status TENTATIVE|STABLE|SHORT_LOST|SPLIT|MERGE|TERMINATED, since, confirm_hits, miss_frames, last_observed_at?, primary_source_id?, updated_at, version)`（`primary_source_id` 记录当前位置主源，用于 SWITCH 血缘，v1.2）
- `target_lineage(lineage_id, op CREATE|MERGE|SPLIT|SWITCH|CLASS_REVISION|STATUS, occurred_at, survivor_target_id?, origin_target_id?, member_target_ids JSON, source_target_ids JSON, basis JSON, algo_version, config_version → fusion_config, operator_kind SYSTEM|USER, operator_id?, note?, snapshots JSON, created_at)` 只增
- `target_current_alias(historical_target_id PK, current_target_id, lineage_id, updated_at)`：被并目标行不删不改名，历史 FK（alarm/uav_event/flight_risk/handoff/assessment/rule_evaluation）保持可解析
- `target_attribute_selection(target_id PK, position_source_id?, class_source_id?, identity_source_id?, motion_source_id?, class_code?, class_confidence?, identity_clue?, selected_at, config_version, manual_class_override, updated_at, version)`
- `target_degradation(target_id PK, level THREE_SOURCE|FUSION_BOX_ONLY|SINGLE_SOURCE|NONE, available_source_ids JSON, confidence_deficit, determined, since, updated_at)`
- `target_classification_revision(revision_id, target_id, previous_class_code?, new_class_code, note 1–1000, actor_id, target_version, created_at)`，`UNIQUE(target_id, target_version)`
- `fusion_event(event_id, topic fusion.target.stable|fusion.target.high_risk|fusion.target.undetermined, target_id, payload JSON, occurred_at, created_at)` 只增（独立于设备 outbox，决策 8-3）
- `replay_ground_truth(dataset_id, scenario, record_no, true_target_key, source_code, external_target_id, observed_at)`；视图 `fusion_effect_daily(source_mode, owner_org_id, district_id, day, tracked_targets, id_switch_count, interrupt_rate, duplicate_target_rate, association_accuracy)`（决策 8-14；`interrupt_rate` = PRED 点 / FUSED 层全部点，决策 8-15）

语义：`target_latest_state.fusion_confidence = 1 − confidence_deficit`；`determined=false` 时为 NULL 并在 `unknown_fields` 记 `{"field":"fusion_confidence","reason_code":"UNSUPPORTED"}`；`classification_confidence` 只来自 EO 或人工修订，否则 `NOT_REPORTED`；`height_agl_m` 与 `altitude_amsl_m` 分别融合、不互推；AOA 仅方位的观测不参与位置融合。

## 算法规格（参数见 `fusion_config.params` demo-v1）

- 单源滤波（α-β）：每条原始 `track` 一个状态 `{x, y (局部 ENU 米), vx, vy, t, acc_m}`；`dt > max_dt_ms` 重新初始化；缺帧 ≤ `pred_max_frames` 输出 `PRED`（只进 FUSED 层的 `track_point`）；再次实测且间隙 ≤ `bridge_max_gap_ms` 时把插值写 `BRIDGE` 点到 `track_point`；更长则开新 `track`。缺精度取 `accuracy_default_m[source_type]`。
- 关联代价：`c = w_pos·(d/σ)² + w_alt·(Δalt/alt_scale)² + w_time·(Δt/time_scale)² + w_motion·[(Δv/speed_scale)² + (Δhdg/heading_scale)²] + w_class·mismatch + w_hist·(1 − stability)`，`σ = √(acc_o² + acc_T²)`，`d` 为 geography 米，Δalt 仅同基准可比（否则该项 0 并记 `NOT_COMPARABLE`），`mismatch ∈ {0, 0.5(任一未知), 1}`，`stability = hits/(hits+misses)`。门限 `d ≤ gate_sigma·σ` 且 `c ≤ cost_max`；同帧同源观测与目标匈牙利匹配（n ≤ 64，否则贪心）；同一来源同一帧两观测不能进同一目标；次优与最优代价差 < 1.0 → `association_pending`（`pending_confirm_frames` 帧一致落定，`pending_expire_frames` 后各自新建 TENTATIVE）。
- ID 状态机：新观测无匹配 → 新 `target(TENTATIVE)` + lineage `CREATE`；连续命中 ≥ `tentative_to_stable_hits` → STABLE；无源 > `short_lost_after_ms` → SHORT_LOST（PRED ≤ 3 帧）；> `terminate_after_ms` → TERMINATED（FUSED 层 `track.ended_at`，051 加此列）。合并：两 STABLE 目标连续 ≥ `merge_min_frames` 帧互在 `merge_max_dist_sigma·σ` 内且运动一致 → 保留 `first_seen_at` 更早者，另一方 MERGE，links 不迁移，`target_current_alias` 写入，lineage `MERGE` 含 snapshots。分裂分两条路径（v1.3，决策 16-2 修订；此前"两新 ID"从未实现）：**人工**（`POST /targets/{id}/split`，阶段 8 起）→ 指定 `link_ids` 迁到**一个**新 TENTATIVE 目标，原目标 `target_track_status=SPLIT`（对引擎是终态，因为它的来源已被人挪走），lineage `SPLIT(operator_kind=USER, origin_target_id)`；**自动**（阶段 16）→ 同源同帧对同一目标出现第二回波，`association_pending(ONE_TO_MANY)` 计帧，间距 ≥ `split_min_separation_m` 持续 ≥ `split_min_frames` 后，第二回波所成的新目标写 lineage `SPLIT(operator_kind=SYSTEM, origin_target_id)` 与 `fusion_event SPLIT`；**原目标保留 ID、状态不变（仍 STABLE，继续被引擎跟踪）**，因为它自己的回波还在。读血缘时以 `operator_kind` 区分两条路径；`op=SPLIT` 只表示"有新目标从原目标分出"，不蕴含原目标的状态。位置主源变更写 `SWITCH`。
- 权重与属性优选：`w_eff(s, attr) = weights[s][attr] · q_latency · q_loss · q_anomaly`（`q_latency = max(0.2, 1 − latency/latency_penalty_ms)`，`q_loss = max(0.2, 1 − misses·loss_penalty_per_miss)`，z > `anomaly_zscore` 时 `q_anomaly = anomaly_downweight`）；位置 `x_f = Σ(x_i/acc_i²)/Σ(1/acc_i²)`，`fused_accuracy = √(1/Σ(1/acc_i²))`；类别取 EO（缺则类别权重最高源）；身份取 TDOA/5G-A；速度/航向取位置主源同源。
- 降级：可用源 ≥ `three_source_min` → THREE_SOURCE(0)；仅 FUSION_BOX/RADAR 一路 → FUSION_BOX_ONLY(0.2)；其他单源 → SINGLE_SOURCE(0.35)；无源 → NONE，deficit 每帧 +`lost_step_deficit`，≥ `undetermined_deficit` → `determined=false`；位置保留最后可信点不外推，不写 (0,0)。

## 回放数据集（E1 生成器）

信封沿用 t02 回放契约：`{"dataset_id","record_no","received_at","frame":{"source_code","observed_at","items":[{"external_target_id","lon","lat","alt_amsl_m","position_accuracy_m","speed_mps","heading_deg","class_code","class_confidence","identity_clue"}]}}`；`source_namespace = replay:<source_code>:<dataset_id>`、`source_message_id = record_no`、`payload_hash = SHA-256(规范 JSON)`、`source_session_key = dataset_id`、`point_seq = record_no`；同键同哈希幂等，同键不同哈希 `SOURCE_MESSAGE_CONFLICT`。三合成来源 `replay-radar-a`（RADAR 15 m，六值类别无置信度）、`replay-tdoa-a`（TDOA 60 m，`identity_clue=RF-xxxx`）、`replay-eo-a`（EO 25 m，`class_code + class_confidence`）；固定 `Random(20260905)`、`T0 = 2026-09-05T12:00:00Z`、1 Hz；六场景 S1 三源同见 / S2 单源缺失（TDOA 20 s 空窗；变体全源丢失）/ S3 交叉（最小间距 40 m）/ S4 分裂合并 / S5 迟到乱序（EO 晚 2.5 s、record_no 倒序）/ S6 精度差异（TDOA ±50 m）；同时写 `replay_ground_truth`。组件：`integration/replay/{FusionReplayDatasetGenerator, FusionReplayReader, FusionReplayRunner, ReplayAdapterPort}`、`LocalStage8FusionReplaySeeder @Order(75)`（双门禁，同步 `drain()`）。测试用固定 `AppClock` 与 `FusionIngestWorker.drain()`。

## 接口

权限：读复用 `target:read`；`fusion:read`（配置、状态、血缘详情、指标视图）；`fusion:revise`（类别修订、合并、分裂，另需 `target:read`）；`fusion:manage`（配置激活）。写请求 `Idempotency-Key`（8–128）+ `expected_version`，body 严格白名单（`UNKNOWN_FIELD`）。范围：目标精确元组；越权 404。

```text
GET  /api/v1/targets           默认**排除** track_status=MERGE 的目标（决策 16-6）：被并者是某个存活目标的别名，
                               列出来用户看到的就是"同一架出现两次"。`include_merged=true` 恢复（只认 true）。
                               详情 /targets/{id} 不过滤——告警、事件、风险里存的是旧 id，历史数据必须还能打开。
GET  /api/v1/targets/{id}      追加可空字段：track_status{status,since}, degradation{level,available_sources[],confidence_deficit,determined},
                               attribute_selection{position_source_code,class_source_code,identity_source_code,motion_source_code,manual_class_override},
                               lineage_summary{current_target_id,op_count,last_op,last_at}, source_links[].source_type/schema_status
                               被并目标 200 且 lineage_summary.current_target_id ≠ target_id
GET  /api/v1/targets/{id}/tracks?layer=RAW|FUSED       既有接口；默认两层都返回，TrackDto 追加可空 `layer`、`config_version`；FUSED 层 `link_id`/`source_id` 为 null
     /api/v1/tracks/{id}/points?kind=MEAS,BRIDGE,PRED   既有接口；默认 MEAS,BRIDGE；PointDto 追加可空 `point_kind`、`position_accuracy_m`、`contributing[{source_code,weight}]`、`source_switched`、`degradation_level`
GET  /api/v1/targets/{id}/lineage?page&size          target:read + fusion:read   occurred_at ASC, lineage_id ASC
GET  /api/v1/targets/{id}/observations?source_code&time_from&time_to&page&size   target:read（原始观测层）
GET  /api/v1/fusion/config                            fusion:read   {active:{config_version,status,schema_status,params,activated_at}, versions[]}
GET  /api/v1/fusion/status                            target:read   {available_sources[{source_code,source_type,schema_status,last_observed_at,online}], data_interrupted, as_of}
GET  /api/v1/fusion/metrics/daily?domain&from&to      fusion:read   fusion_effect_daily 行
POST /api/v1/targets/{id}/classification-revisions    fusion:revise {new_class_code UAV|BIRD|VEHICLE|PERSON|UNKNOWN, note 1–1000, expected_version} → 201 {revision_id,target_id,class_code,version,updated_at}
POST /api/v1/targets/merge                            fusion:revise {survivor_target_id, member_target_ids[1..5], note, expected_versions{id:version}} → 201 {lineage_id,survivor_target_id,merged_target_ids}
POST /api/v1/targets/{id}/split                       fusion:revise {link_ids[≥1], note, expected_version}（v1.2：`source_codes` 不在本期，决策 8-21） → 201 {lineage_id,origin_target_id,new_target_ids[]}
POST /api/v1/fusion/config/{version}/activate         fusion:manage {expected_version} → 200；旧 ACTIVE → RETIRED 同事务
```

`allowed_actions` 于目标详情：`REVISE_CLASS`（有 `fusion:revise` 且状态 ∉ {TERMINATED, MERGE}）、`MERGE`、`SPLIT`（≥ 2 links）。错误码：沿用 `FORBIDDEN / NOT_FOUND / INVALID_REQUEST / UNKNOWN_FIELD / VERSION_CONFLICT / INVALID_TRANSITION / IDEMPOTENCY_REPLAY / IDEMPOTENCY_KEY_REUSED`，新增 `FUSION_DOMAIN_MISMATCH(409)`、`TARGET_ALREADY_MERGED(409)`、`CONFIG_NOT_FOUND(404)`、`CONFIG_ALREADY_ACTIVE(409)`、`INVALID_CLASS_CODE(400)`。写事务顺序同阶段 5/7：鉴权 → 锁 target 行 → claim → 版本/状态 → 写 lineage/alias/selection → 成功审计。事件：`fusion_event.event_type ∈ STATUS_STABLE | MERGED | SPLIT | UNDETERMINED | CLASS_REVISED`（决策 8-3、8-20），独立只增表，不走 outbox 主题。领导补 `GlobalExceptionHandler.module()`（`/fusion`→fusion；`/targets` 已归 …→ 需核对现值）与 `AuditLabels`。

## 前端

本阶段**不改前端**（决策 8-11）：`SituationPage.vue` 与 `services/targetApi.js` 保持现状，A 的页面按本契约接线；`map.js` 已有的 `layerKey`/精度圈/锚点修复保留供 A 使用。既有四个读接口就能看到融合结果（`target_latest_state` 即融合结果，`/targets/{id}/tracks` 含 FUSED 层）。

## 文件归属

领导（已做）：`PermissionCode`、迁移 050、`map.js`、对齐文档、本文；领导（待做）：`R__stage8` 骨架、`GlobalExceptionHandler`/`AuditLabels`。E1：迁移 051/052、`modules/fusion/domain/{SourceObservation, FusionDomainKey, AlphaBetaFilter, AssociationCost, Associator, IdentityStateMachine, FusionParams}`、`modules/fusion/application/{FusionIngestWorker, FusionPipeline, FusionConfigLoader}`、`modules/fusion/infrastructure/{ObservationRepository, RawTrackRepository, IdentityRepository, AssociationPendingRepository, FusionInboxRepository}`、`integration/replay/**`、`integration/mock/LocalStage8FusionReplaySeeder`。E2：迁移 053、`modules/fusion/domain/{AttributeSelector, WeightedFuser, DegradationEvaluator}`、`application/{FusedLayerWriter, FusionReadService, FusionCommandService, FusionConfigService, FusionEventEmitter}`、`infrastructure/{FusedTrackRepository, LineageRepository, DegradationRepository, FusionConfigRepository, FusionEventRepository}`、`api/{FusionController, FusionDtos}`、`TargetDtos/TargetReadService/TargetReadRepository` 只加可空字段与 `layer/kind` 过滤。前端无归属（不改）。助手：迁移 054、`FusionMetricsRepository`、`Stage8PostgresTest`、`ProductionStage8SeedIsolationTest`、`docs/backend-stage8/fusion-effect-metrics.md`。E1↔E2 边界：`FusedLayerWriter.write(TargetFrameResult(targetId, domainKey, observedAt, List<SourceEstimate>, TrackStatus))`，E1 在管线第 ⑥ 步调用；E2 落地前 E1 用无操作实现测试。

## 门槛（阶段 7 审查固化）

列表计数断言按自身归属过滤；触及 JSON/JSONB 列的非 INSERT 表达式、CHECK、触发器必须先过 `Stage8PostgresTest` 再报 GREEN；H2 下米制距离用 Java 侧 Haversine（域层不依赖 SQL 几何）。
