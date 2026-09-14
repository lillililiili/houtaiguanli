# 阶段 7 规则引擎与合法性判定接口契约

> 状态：领导冻结稿（2026-09-05）。执行者按本文实现；改契约先向领导提出。配套：`docs/superpowers/plans/2026-09-05-collaborator-b-stages-4-to-6.md` §0 全局约束、`docs/backend-stage3/flight-airspace-assessment-api-contract.md`（只读契约，本文修订其"不返回规则参数"一条：有 `rule:read` 时返回参数值与 DEMO/CONFIRMED 状态）。

## 交付边界

合法性研判从"读种子"变为"由规则集推导"。所有阈值/权重/容差放在 `rule_param`，带 `param_status ∈ {DEMO, CONFIRMED}`，代码中不得出现裸阈值。生产默认没有 ACTIVE 规则集版本：引擎空转、不产生研判或告警；`app.rule-engine.allow-demo-active=false`（生产）时激活 DEMO 版本返回 409 `DEMO_PARAMS_NOT_ALLOWED`。AGL 与 AMSL 永不互比；缺失事实即 `UNDETERMINED` 并带原因码。`assessment_result`、`rule_evaluation`、`legality_review_history` 只增。引擎运行本身不走 HTTP 鉴权/幂等，每次运行留 `rule_run`。阶段 4 的"ILLEGAL 不自动生成告警"被 C06 取代：**仅 ACTIVE 模式且 C03 输出 ABNORMAL/ILLEGAL 时**经内部可信用例 `AlarmIngestionService` 生成来源告警；风险仍不自动生成。空间事实只来自 PostGIS（`SpatialFactPort`），H2 测试用桩替换。

知会 A：`assessment_result.conclusion_code` 新增 `ABNORMAL`；`illegal_assessments` 指标（`conclusion_code='ILLEGAL'`）口径不变。

## 通用约定

沿用阶段 4/5：`{ok,data}`/`{ok:false,error:{code,message}}`、snake_case、字符串 ID、epoch 毫秒、`page,size → items/page/size/total`（默认 1/20，最大 100；未知/重复参数 400）、所有 GET/POST 先完成全部动作鉴权再解析 query/path/body、`ASSIGNED` 精确元组、`ALL` 仍要求目录启用、越权 404、缺动作 403、写请求 `Idempotency-Key`（8–128）+ `expected_version`、body 严格白名单（多余/重复字段 400 `UNKNOWN_FIELD`）、成功审计同事务、失败审计事务外。

## 权限

| 接口 | 权限 |
| --- | --- |
| 规则集/版本/激活历史/运行记录读取 | `rule:read` |
| 激活 / 回滚 / 设置阴影 | `rule:read` + `rule:manage` |
| 研判列表/详情/复核历史 | `assessment:read`（目标引用另需 `target:read`，告警引用另需 `alarm:read`，否则字段为 null） |
| 手动评估 / 重新研判 | `assessment:read` + `assessment:evaluate`（主体为目标时另需 `target:read`，为计划时另需 `flight:read`） |
| 人工复核（确认/驳回/改判） | `assessment:read` + `assessment:revise` |
| 转告警 | `assessment:read` + `assessment:escalate` |
| 规则效果事实/汇总 | `assessment:read` + `rule:read` |

范围：研判行自带 `(owner_org_id, district_id)`（复制自目标/计划），列表、详情、历史、count、`allowed_actions` 共用同一谓词，含组织/区域目录启用检查（同 `AlarmReadRepository.where`）。

## 数据与编号

| 编号 | 所有者 | 内容 |
| --- | --- | --- |
| `V202609050040__stage7_rule_permissions_and_assessment_links.sql` | 领导 | 五个 ACTION 目录（sort 940–944）；`integration_source` 三行 `rule-engine-legality-{mock,replay,live}`（`RULE-ENGINE-LEGALITY-*`，enabled，无凭据）；`assessment_result` 追加 `evaluation_id`、`rule_set_version_id`、`supersedes_assessment_id`（自引用 FK），`conclusion_code` CHECK 重建含 `ABNORMAL` |
| `V202609050041__stage7_rule_engine_core.sql` | E1 | `rule_set`、`rule_set_version`、`rule_set_member`、`rule_param`、`rule_set_activation`、`rule_run`、`rule_evaluation`、`rule_engine_lease`；补 `assessment_result.rule_set_version_id` FK |
| `V202609050042__stage7_review_and_alarm_merge.sql` | E2 | `legality_review`、`legality_review_history`、`alarm_merge_group`、`alarm_merge_member`、视图 `v_rule_effect_fact` |
| `db/postgresql/R__stage7_rule_engine_constraints.sql` | E1（E2 片段交 E1 合并） | `rule_evaluation`/`rule_run`/`legality_review_history` 只增触发器；已 PUBLISHED 版本的 `rule_param` 不可改；`rule_run(REPLAY, dataset, version)` 部分唯一索引 |

阶段 8 权限 sort 950–952，阶段 9 960–964（避免与本阶段 940–944 冲突）。种子 `@Order`：`LocalStage7RuleEngineSeeder` 65、`RuleReplayRunner` 70。

表字段（摘要，列名即契约）：

- `rule_set(rule_set_id, rule_set_code UNIQUE, name, active_version_id?, shadow_version_id?, previous_active_version_id?, version, created_at, updated_at)`
- `rule_set_version(rule_set_version_id, rule_set_id, version_no, status_code DRAFT|PUBLISHED|RETIRED, param_status DEMO|CONFIRMED, valid_from, valid_to?, description, source_mode, created_at, published_at?)`，`UNIQUE(rule_set_id, version_no)`
- `rule_set_member(rule_set_version_id, rule_version_id → rule_version, priority, enabled)`；复用既有 `rule_version` 作单规则定义，`rule_code ∈ {C01, C02-1…C02-8, C03, C06}`
- `rule_param(rule_param_id, rule_set_version_id, rule_code, param_key, value_text, value_type NUMBER|INTEGER|BOOLEAN|STRING|LIST, unit?, param_status, note)`，`UNIQUE(rule_set_version_id, rule_code, param_key)`
- `rule_set_activation(activation_id, rule_set_id, kind ACTIVATE|ROLLBACK|SHADOW_SET|SHADOW_CLEAR, from_version_id?, to_version_id?, actor_id, note, resulting_version, created_at)`
- `rule_run(run_id, rule_set_id, rule_set_version_id, mode ACTIVE|SHADOW, trigger_kind SCHEDULED|MANUAL|RECOMPUTE|REPLAY, replay_dataset_code?, triggered_by?, as_of, started_at, finished_at?, status RUNNING|DONE|FAILED|SKIPPED, subject_count, evaluated_count, alarm_created_count, alarm_merged_count, error_summary?, source_mode, created_at)`
- `rule_evaluation(evaluation_id, run_id, rule_set_version_id, mode, subject_kind TARGET|PLAN, target_id?, track_id?, plan_id?, route_version_id?, observed_at?, as_of, evaluated_at, freshness_code FRESH|STALE|NO_STATE|REPLAY, plan_match_code FULL|PARTIAL|NONE|UNDETERMINED|NOT_APPLICABLE, legal_status LEGAL|ABNORMAL|ILLEGAL|UNDETERMINED|NOT_APPLICABLE, score?, grade HIGH|MEDIUM|LOW?, violation_reasons JSONB, hit_details JSONB, unknown_reasons JSONB, evidence_references JSONB, input_snapshot JSONB（不出 API）, supersedes_evaluation_id?, assessment_id?, alarm_outcome JSONB?, alarm_id?, owner_org_id, district_id, source_mode, created_at)`
- `legality_review(evaluation_id PK, review_state PENDING_REVIEW|CONFIRMED|REJECTED|OVERRIDDEN|SUPERSEDED, manual_status?, version, owner_org_id, district_id, created_at, updated_at)`
- `legality_review_history(history_id, evaluation_id, version, previous_state, resulting_state, conclusion CONFIRM|REJECT|OVERRIDE|RECOMPUTE|ESCALATE, status_before, status_after?, note 1–1000, actor_id, related_evaluation_id?, related_alarm_id?, created_at)`，`UNIQUE(evaluation_id, version)`
- `alarm_merge_group(group_id, target_id, alarm_type, rule_set_id, state OPEN|AUTO_CLOSED, current_severity, first_alarm_id, latest_alarm_id, hit_count, window_opened_at, window_expires_at, last_hit_at, closed_at?, closed_reason?, owner_org_id, district_id, version, created_at, updated_at)`
- `alarm_merge_member(member_id, group_id, evaluation_id UNIQUE, alarm_id?, member_kind CREATED|MERGED|UPGRADED|DOWNGRADED|MANUAL_ESCALATION, severity_before?, severity_after, created_at)`
- 视图 `v_rule_effect_fact(evaluation_id, evaluated_at, mode, subject_kind, target_id, plan_id, rule_set_version_id, param_status, legal_status, manual_status, review_state, has_alarm, merge_kind, alarm_id, group_id, supersedes_evaluation_id, source_mode, owner_org_id, district_id)`

`assessment_result` 仍是计划维度投影：仅 ACTIVE 且 `plan_match_code ∈ {FULL, PARTIAL}` 时同事务追加一行（`checks` 由 `hit_details` 压缩为 `{rule_code,result_code,reason_code}`，`rule_version_id` 取规则集内 C03 的 `rule_version`），重算时 `supersedes_assessment_id` 指旧行。SHADOW 与无计划目标只落 `rule_evaluation`。

## 引擎接口（`modules/assessment/engine/RuleContracts.java`，领导冻结）

`SpatialFactPort { airspaceHits(targetId, asOf); distanceToRoute(targetId, routeVersionId); ambiguousEffectiveAirspaceVersion(asOf) }` 是唯一 PostGIS 依赖。`RuleCheck { ruleCode(); defaultPriority(); evaluate(EvaluationContext, RuleParams) → HitDetail }`。`RuleParams.number/integer/bool/string/list(ruleCode, key)` 缺参数抛 `IllegalStateException`（配置缺失是部署错误，不是业务未知）。

评估流程：取 ACTIVE/SHADOW 版本 → 收集 `target_latest_state`、最近 `C03.track_points` 个轨迹点、候选计划、生效空域（`valid_from ≤ as_of < valid_to`）、空间事实 → 新鲜度（SCHEDULED：`observed_at ≥ now − C03.fresh_seconds` 否则 STALE → `NOT_APPLICABLE/STATE_STALE`；REPLAY/MANUAL 以 `observed_at` 为 `as_of`）→ C01 → C02-x → C03 → C06 → 写 `rule_evaluation` + 投影 + `legality_review(PENDING_REVIEW)`（`NOT_APPLICABLE` 研判不建复核行，否则 STALE 目标每 tick 都会灌满队列）；一条研判一个事务。

### C01 计划匹配（E2）

候选：同 `(owner_org_id, district_id)` 的 `flight_plan`，`uav_sn` 相等 **或** `[start_at − C01.time_window_min, end_at + C01.time_window_min)` 覆盖 `as_of`。五维：时间窗 MATCH/MISMATCH/UNDETERMINED(`PLAN_TIME_UNKNOWN`)；走廊（`distanceToRoute ≤ 半宽 + C01.corridor_tolerance_m`，与 C02-3 同源）MATCH/MISMATCH/UNDETERMINED(`CORRIDOR_WIDTH_UNKNOWN`/`POSITION_UNKNOWN`)；身份（`target.uav_sn` vs `plan.uav_sn`，目标无 sn → `IDENTITY_CLUE_MISSING`，页面文案"线索缺失"）；起降点恒 `TAKEOFF_POINT_UNAVAILABLE`；飞手/单位恒 `PILOT_UNIT_UNAVAILABLE`。等级：任一可判维度 MISMATCH → NONE；时间+走廊 MATCH 且身份 MATCH → FULL；时间+走廊 MATCH 且身份 UNDETERMINED → PARTIAL；时间或走廊 UNDETERMINED → UNDETERMINED；多候选同优且非 NONE → `PLAN_AMBIGUOUS` → UNDETERMINED；无候选 → NONE(`NO_PLAN_CANDIDATE`)。

### C02 检查（E1）

| 代码 | 事实 | FAIL 原因码 | UNDETERMINED 原因码 |
| --- | --- | --- | --- |
| C02-1 禁飞空域 | kind ∈ `C02-1.kinds`；`ST_Touches` → 未知优先；`ST_Covers` 且（无高度带 或 目标同基准高度在带内） | `INSIDE_RESTRICTED_AIRSPACE` | `BOUNDARY_POLICY_UNKNOWN`、`POSITION_UNKNOWN`、`ALTITUDE_DATUM_OR_RANGE_UNKNOWN`、`VERSION_AMBIGUOUS` |
| C02-2 空域限高 | kind ∈ `C02-2.kinds` 水平覆盖；目标高度按 `airspace_version.altitude_datum` 取 `altitude_amsl_m` 或 `height_agl_m` | `AIRSPACE_ALTITUDE_EXCEEDED` | 同上 |
| C02-3 航线偏离 | 距中心线 − 半宽 > `C02-3.tolerance_m` | `ROUTE_DEVIATION` | `CORRIDOR_WIDTH_UNKNOWN`、`POSITION_UNKNOWN`；无计划 NOT_APPLICABLE |
| C02-4 时间窗 | `as_of ≥ end_at + C02-4.grace_min` 或 `< start_at − grace` | `TIME_WINDOW_OVERRUN` | `PLAN_TIME_UNKNOWN`；无计划 NOT_APPLICABLE |
| C02-5 夜航 | `C02-5.timezone` 本地时 ∈ [`night_from`, 24) ∪ [0, `night_to`) | `NIGHT_FLIGHT` | — |
| C02-6 超视距 | 无 pilot_position | — | 恒 `PILOT_POSITION_UNAVAILABLE` |
| C02-7 计划高度 | 目标同基准高度 > `route_version.max_altitude_m` 或 < min | `PLAN_ALTITUDE_EXCEEDED` | 基准缺失；无计划 NOT_APPLICABLE |
| C02-8 临时限制 | kind ∈ `C02-8.kinds` 且生效窗口内覆盖 | `TEMPORARY_RESTRICTION_ACTIVE` | 同 C02-1 |

### C03 四态（E1，短路顺序）

1. NO_STATE / STALE → `NOT_APPLICABLE`。
2. 质量门：`fusion_confidence`（缺则 `classification_confidence`）< `C03.conf_min` → `LOW_CONFIDENCE`；轨迹点数 < `C03.min_points` → `TRACK_DEGRADED`；相邻点间隔 > `C03.gap_seconds` → `TRACK_BRIDGED`；任一 → `UNDETERMINED`。
3. C01 NONE → `C03.no_plan_status`（默认 `ILLEGAL`，原因 `NO_AUTHORIZATION`）；C01 UNDETERMINED → `UNDETERMINED`。
4. C02-1/2/8 任一 FAIL → `ILLEGAL`；任一 UNDETERMINED（无 FAIL）→ `UNDETERMINED`。
5. C02-3/4/5/7 任一 FAIL → `ABNORMAL`。
6. 其余检查有 UNDETERMINED（排除 `C03.ignore_undetermined_rules`）→ `UNDETERMINED`；否则 `LEGAL`。

`violation_reasons` = 全部 FAIL 原因码；评分仅 ILLEGAL/ABNORMAL：`score = 100·Σ w_k·F_k`（因子：最大违规严重度 `C03.severity.<reason>`、计划匹配 NONE 1/PARTIAL .5/FULL 0、限制空域命中 1/0、轨迹桥接 .6/0、`1 − confidence`），`grade` 按 `C03.grade.high/medium`。

### 模式、回滚、重算、C06

- SHADOW：只写 `rule_evaluation(mode=SHADOW)`，`alarm_outcome={"kind":"SUPPRESSED_SHADOW"}`，不投影、不告警，默认队列不显示。一个规则集可同时有 ACTIVE 与 SHADOW 版本。
- 回滚：`previous_active_version_id` 设为 active（头行条件更新）+ `rule_set_activation(ROLLBACK)`；旧研判不改。
- 重算：新 `rule_evaluation(trigger=RECOMPUTE, supersedes_evaluation_id=旧)`，旧 `legality_review → SUPERSEDED`（version+1，历史 `RECOMPUTE`）。
- C06（E2）：`AlarmIngestionService.ingest(TrustedAlarmFact(sourceId, sourceAlarmId, targetId, alarmType, severity, occurredAt, receivedAt, detail, sourceMode))` 镜像 `RiskIngestionService`：校验 → `lockSource` → 目标元组存在且目录启用（否则 `INVALID_ALARM_FACT`，引擎记 `alarm_outcome.kind=BLOCKED`）→ `(source_id, source_alarm_id)` 幂等 → 插 `alarm` → `UavEventRepository.createForAlarm(…, PENDING_VERIFICATION)`。来源按目标 `source_mode` 取 `rule-engine-legality-{mode}`；`source_alarm_id = "eval:" + evaluation_id`（手动 `"manual:" + evaluation_id`）；`alarm_type = RULE_LEGALITY`；`severity` 由 `C06.severity_by_grade`。合并：同目标同类型 OPEN 组 `FOR UPDATE`；窗口内 → MERGED 不建告警、`hit_count++`、延长窗口；等级更高且在升级窗 → 新告警 UPGRADED；更低 → DOWNGRADED；过期 → 新组。自动关闭：`window_expires_at + C06.auto_close_min < now` 且最近研判非 ABNORMAL/ILLEGAL → `AUTO_CLOSED`，**不改 `uav_event.state_code`，`alarm` 行永不 UPDATE**。REJECT 复核不删告警/组。

## DEMO 参数目录（`LEGALITY-DEMO` v1，全部 `param_status=DEMO`）

| rule_code | key | 值 | 类型/单位 |
| --- | --- | --- | --- |
| C01 | time_window_min | 10 | INTEGER min |
| C01 | corridor_tolerance_m | 100 | NUMBER m（必须明显大于 C02-3.tolerance_m，否则"已匹配计划但偏航"永远不可达） |
| C02-1 | kinds | PROHIBITED,RESTRICTED | LIST |
| C02-2 | kinds | HEIGHT_LIMIT,ALTITUDE_LIMIT | LIST |
| C02-3 | tolerance_m | 20 | NUMBER m |
| C02-4 | grace_min | 10 | INTEGER min |
| C02-5 | timezone / night_from / night_to | Asia/Shanghai / 20 / 6 | STRING / INTEGER h |
| C02-6 | vlos_m | 500 | NUMBER m（当前恒未知，保留参数） |
| C02-8 | kinds | TEMPORARY,TEMPORARY_CONTROL | LIST |
| C03 | fresh_seconds / track_points / conf_min / min_points / gap_seconds | 120 / 10 / 0.75 / 3 / 30 | INTEGER s / INTEGER / NUMBER / INTEGER / INTEGER s |
| C03 | no_plan_status | ILLEGAL | STRING |
| C03 | ignore_undetermined_rules | C02-6 | LIST |
| C03 | w.violation / w.plan_match / w.airspace / w.track / w.confidence | 0.40 / 0.25 / 0.15 / 0.10 / 0.10 | NUMBER |
| C03 | severity.INSIDE_RESTRICTED_AIRSPACE / AIRSPACE_ALTITUDE_EXCEEDED / TEMPORARY_RESTRICTION_ACTIVE / NO_AUTHORIZATION / ROUTE_DEVIATION / PLAN_ALTITUDE_EXCEEDED / TIME_WINDOW_OVERRUN / NIGHT_FLIGHT | 1.0 / 0.9 / 0.9 / 0.8 / 0.6 / 0.5 / 0.4 / 0.3 | NUMBER |
| C03 | grade.high / grade.medium | 67 / 34 | NUMBER |
| C06 | dedup_window_min / upgrade_window_min / auto_close_min | 5 / 10 / 15 | INTEGER min |
| C06 | severity_by_grade | HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW | LIST |

## 接口

```text
GET  /api/v1/rule-sets                                   rule:read
GET  /api/v1/rule-sets/{code}/versions                   rule:read   items:{rule_set_version_id,version_no,status_code,param_status,valid_from,valid_to,is_active,is_shadow}
GET  /api/v1/rule-set-versions/{id}                      rule:read   {…, members:[{rule_code,rule_version_id,priority,enabled}], params:[{rule_code,key,value,type,unit,status,note}]}
POST /api/v1/rule-sets/{code}/activate                   rule:manage {rule_set_version_id,note,expected_version}
POST /api/v1/rule-sets/{code}/rollback                   rule:manage {note,expected_version}
POST /api/v1/rule-sets/{code}/shadow                     rule:manage {rule_set_version_id|null,note,expected_version}
GET  /api/v1/rule-sets/{code}/activations                rule:read   resulting_version ASC
GET  /api/v1/rule-runs?mode&trigger_kind&from&to         rule:read   started_at DESC, run_id DESC
GET  /api/v1/rule-runs/{id}                              rule:read
POST /api/v1/legality-evaluations                        assessment:evaluate(+源读) {subject_kind TARGET|PLAN, subject_id, mode ACTIVE|SHADOW} → 201 {run_id, evaluation}
GET  /api/v1/legality-evaluations?mode&latest_only&legal_status&plan_match&review_state&subject_kind&target_id&plan_id&from&to&owner_org_id&district_id&source_mode   assessment:read  排序 evaluated_at DESC, evaluation_id DESC
GET  /api/v1/legality-evaluations/{id}                   assessment:read  {…, hit_details, review:{state,manual_status,version}, allowed_actions, supersedes_evaluation_id, superseded_by_evaluation_id, alarm_id?, assessment_id}
GET  /api/v1/legality-evaluations/{id}/revisions         assessment:read  version ASC
POST /api/v1/legality-evaluations/{id}/revisions         assessment:revise {conclusion CONFIRM|REJECT|OVERRIDE, override_status?, note, expected_version}
POST /api/v1/legality-evaluations/{id}/recompute         assessment:evaluate {note, expected_version} → 201 新研判
POST /api/v1/legality-evaluations/{id}/alarms            assessment:escalate {note, expected_version} → 201 {alarm_id, event_id}
GET  /api/v1/rule-effects/facts?mode&from&to&…           assessment:read + rule:read（v_rule_effect_fact 分页；mode 默认 ACTIVE，SHADOW 仅显式指定时返回）
GET  /api/v1/rule-effects/summary?mode&from&to&timezone&source_mode&owner_org_id&district_id   同上；响应回显 mode
```

`summary`：`evaluations, alarm_worthy, alarms_created, alarms_merged, convergence_ratio, reviewed, false_positive_rate(REJECTED/reviewed), miss_rate(OVERRIDE 由 LEGAL|UNDETERMINED 改为 ILLEGAL|ABNORMAL / reviewed), manual_override_rate((REJECTED+OVERRIDDEN)/reviewed)`；分母 0 → `{value:null, availability:"NO_DENOMINATOR"}`。`allowed_actions`：PENDING_REVIEW 且有 revise → `REVIEW`；非 SUPERSEDED 且有 evaluate → `RECOMPUTE`；`legal_status ≠ LEGAL` 且无 `alarm_id` 且有 escalate → `ESCALATE`。`hit_details` 元素：`{rule_code, rule_version_id, result_code, reason_code, severity, facts{…}, params[{key,value,status}], evidence[{kind,id}], message}`。

## 稳定错误码

`RULE_SET_NOT_FOUND(404)`、`RULE_VERSION_NOT_FOUND(404)`、`RULE_VERSION_NOT_PUBLISHED(409)`、`DEMO_PARAMS_NOT_ALLOWED(409)`、`NO_PREVIOUS_VERSION(409)`、`NO_ACTIVE_RULE_SET(409)`、`SHADOW_VERSION_NOT_SET(409)`、`SPATIAL_BACKEND_UNAVAILABLE(500)`、`LEGALITY_EVALUATION_NOT_FOUND(404)`、`EVALUATION_SUPERSEDED(409)`、`ALARM_ALREADY_LINKED(409)`、`INVALID_CONCLUSION(400)`、`OVERRIDE_STATUS_REQUIRED(400)`、`INVALID_ALARM_FACT(400，内部)`，沿用 `FORBIDDEN / NOT_FOUND / UNKNOWN_FIELD / INVALID_REQUEST / INVALID_TRANSITION / VERSION_CONFLICT / IDEMPOTENCY_REPLAY / IDEMPOTENCY_KEY_REUSED`。

## 调度与回放

`RuleEngineWorker @Scheduled(fixedDelayString="${app.rule-engine.poll-millis:5000}")`，`@ConditionalOnProperty(app.rule-engine.enabled)`（test 默认 false）。每 tick：条件更新 `rule_engine_lease` → 待评估主体 = `target_latest_state.observed_at >` 该目标最近同模式研判的 `observed_at` 且新鲜（同口径比较；`updated_at` 总晚于 `observed_at`，用它会每 tick 重复研判），`LIMIT app.rule-engine.batch-size(50)` → 每主体独立事务 → `rule_run` 计数 → 自动关闭到期组。不扩展 `OutboxWorker`。回放回归：`RuleReplayRegressionTest`（H2 桩事实）+ `Stage7PostgresTest`（PostGIS 端到端）+ `RuleReplayRunner`（`!production`，`app.rule-engine.replay.run-on-start`，结论不符启动失败，已有同数据集 `rule_run` 则跳过，不覆盖 `legality_review`）。

## 文件归属

领导：`PermissionCode.java`、迁移 040、`GlobalExceptionHandler`、`AuditLabels`、`modules/assessment/engine/RuleContracts.java`、本文。E1：迁移 041、`R__stage7_*`、`modules/assessment/engine/**`（除 RuleContracts 与 `checks/PlanMatchCheck`）、`modules/assessment/api/{RuleSetController,RuleRunController,RuleDtos}`、`application/RuleSetManagementService`。E2：迁移 042、`engine/checks/PlanMatchCheck`、`modules/alarm/application/AlarmIngestionService`(+`TrustedAlarmFact`)、`modules/alarm/infrastructure/AlarmMergeRepository`、`modules/assessment/application/{LegalityReviewService,AlarmEscalationService,LegalityEvaluationReadService,RuleEffectReadService}`、`api/{LegalityEvaluationController,RuleEffectController}`、`infrastructure/LegalityReviewRepository`、`integration/mock/LocalStage7RuleEngineSeeder`、`RuleReplayRunner`、`dongying-vue/src/services/legalityApi.js`、`pages/LegalityPage.vue`、`ui/legalityReviewModal.js`。助手：`Stage7PostgresTest`、`ProductionStage7SeedIsolationTest`、`RuleReplayRegressionTest`。

## 尚未接入

飞手位置/超视距、身份线索（TDOA/5G-A）、起降点、飞手/单位、真实规则参数确认、处置（转入处置按钮禁用）。
