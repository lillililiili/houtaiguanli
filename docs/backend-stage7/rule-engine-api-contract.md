# 阶段 7 规则引擎与合法性判定接口契约

## 2026-10-08 合并时确认的适用口径

- 用户明确采用远端“超视距只提示不算非法”：新研判 C02-6 超过距离阈值只提供 `beyond_vlos` 与 `pilot_distance_note`，不计违规、风险等级或证据充分性的阻断项；不回写旧研判和旧告警。
- 保留正式修订版 `C03.no_plan_status=LEGAL`，缺计划本身不构成违规，夜间及空域等独立检查仍生效。远端的 120 米低空豁免只用于原先会因无计划判违的规则版本；展示该说明必须有 C01 的 `no_plan_exempt=true`，不能由 `LEGAL` 与 `NONE` 自行推断高度或空域事实。
- 沿用远端未匹配任务时不比较偏航的改动：C01 为 `NONE` 时 C02-3 返回 `NOT_APPLICABLE`；已匹配任务仍按该版本的距离基准与偏航阈值判断，不用候选任务冒充已关联任务。可靠关联但仅超时的本机计划仍保留本地超时原因。
- 读取响应兼容保留本地 `effective_legal_status` 和远端 `confidence`、`confidence_threshold`、`source_count`。原始记录、复核有效结论、目标识别信息分别按各自字段读取。
- C04 保留已确认的高度带、数量、趋势及计划前后 15 分钟；C05 按计划本身时段检查，不套用 C04 的前后扩展时段。来源隔离与并发去重保持。

## 2026-10-08 第二、第四部分确认规则

本节按用户提供的《规则与做法确认书（含验收方法）-2026-10-08》第二、第四部分及本会话的高度分层补充确认执行，替代下方历史演示参数口径。源文件 SHA-256：`b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566`。

**同日最新修订优先：**用户撤回第 2-2 条，并明确“合法，但是这和告警没关系”。最终启用修订版的 `C03.no_plan_status=LEGAL`：没有匹配报备计划本身不构成违规，其他可检查项通过且证据充分时可以判合法；C01 的未匹配事实仍展示。独立空域、夜间等已确认检查和数据质量要求继续生效，不把低置信度、断轨或未知空域当合法。已有告警不因该修订被删除、核实或解除，告警办理流程不改。第 2-1 条验收示例中的“未匹配即无授权”亦不再适用，历史版本和历史结果保留。

本次待启用的合法性版本为 `legality-confirmed-20261008-r2`，空间风险版本为 `space-risk-confirmed-20261008`。r2 复制初版配置，仅修订 `C03.no_plan_status` 及其确认说明；初版 `legality-confirmed-20261008` 仅在未被生效/影子/回滚指针、激活历史或规则运行引用时标为 `RETIRED`（已撤回，不能启用）。已使用的初版及全部历史结果保留，迁移不代替验证和实际启用操作。

- 规则以独立 `PUBLISHED`、`CONFIRMED` 版本发布，通过原有 `shadow`、`activate` API 和真实管理员会话启用。迁移不覆盖旧版本、不改已有生效指针，不增加发布公共接口。新库在关闭开发种子时也有正式规则目录；启用操作仍需要 `rule:manage`。
- 合法性：关联计划使用中心线 100 米与前后 10 分钟；中心线偏离超过 20 米、计划结束超过 10 分钟、计划限高、有效禁限飞/临时管控/限高空域分别产生具体原因。夜间为北京时间 20:00–06:00；有计划且时间匹配时不因夜间单独判违，其他情况追加夜间原因。已可靠确认计划身份及位置而仅超时的目标保留超时原因，不叠加“无授权”。遥控器测算距离超过 500 米才判断超视距，位置缺失不猜测。
- 证据：使用最近 120 秒；置信度至少 0.75、至少 3 点、相邻间隔不超过 30 秒。资料不足为 `UNDETERMINED`，不经 C06 生成违规告警。评分因素、优先顺序和 67/34 分界按确认书；原权重及严重度系数沿用既有实现，参数说明明确其未逐值单独确认。
- 空间风险：C04 使用航线 300 米、计划前后 15 分钟、鸟群至少 20 只和最近 30 分钟实测趋势。AGL<150 为爬升高度带，150≤AGL<300 为进近高度带；不推断实际航段，不拿海拔代替离地高度。缺高度保留原有未知依据及人工核验提示，不声称已满足高度条件；鸟群缺数量不按“默认20只”制造鸟群风险。
- C05 仅在对应计划时段内按进离场航线 500 米或保护目标周围 200 米判断机场异物，固定中风险；取消计划不参与。无计划仍只保留目标观测，不新建独立区域风险流程。风险核验、排除和通知继续使用原状态机、权限与人工入口。
- 受控模拟 `POST /api/v1/local-interface-simulator/target-observations` 的 `items[].object_count` 为可选正整数；缺失表示未知。按来源观测写入 `quality.object_count`，融合点从明确引用的观测读取；多设备数量不求和，冲突保持未知。同源消息幂等、范围校验和模拟门禁保持原样，真实设备字段需由对应适配器提供，不能从“鸟”类别推导数量。
- 第 25 项 C02-9～C02-12、确认书其余章节、模拟来源标识与反制授权不在本次规则确认范围内。历史研判和冻结材料继续引用历史版本，不回填为正式确认。

> 状态：领导冻结稿（2026-09-05）。执行者按本文实现；改契约先向领导提出。配套：`docs/superpowers/plans/2026-09-05-collaborator-b-stages-4-to-6.md` §0 全局约束、`docs/backend-stage3/flight-airspace-assessment-api-contract.md`（只读契约，本文修订其"不返回规则参数"一条：有 `rule:read` 时返回参数值与 DEMO/CONFIRMED 状态）。

## 交付边界

合法性研判从"读种子"变为"由规则集推导"。所有阈值/权重/容差放在 `rule_param`，带 `param_status ∈ {DEMO, CONFIRMED}`，代码中不得出现裸阈值。生产默认没有 ACTIVE 规则集版本：引擎空转、不产生研判或告警；`app.rule-engine.allow-demo-active=false`（生产）时激活 DEMO 版本返回 409 `DEMO_PARAMS_NOT_ALLOWED`。AGL 与 AMSL 永不互比；缺失事实即 `UNDETERMINED` 并带原因码。`assessment_result`、`rule_evaluation`、`legality_review_history` 只增。引擎运行本身不走 HTTP 鉴权/幂等，每次运行留 `rule_run`。阶段 4 的"ILLEGAL 不自动生成告警"被 C06 取代：**仅 ACTIVE 模式且 C03 输出 ABNORMAL/ILLEGAL 时**经内部可信用例 `AlarmIngestionService` 生成来源告警；风险仍不自动生成。空间事实只来自 PostGIS（`SpatialFactPort`），H2 测试用桩替换。

知会 A：`assessment_result.conclusion_code` 新增 `ABNORMAL`；`illegal_assessments` 指标（`conclusion_code='ILLEGAL'`）口径不变。

## 通用约定

### 2026-09-28 共享核实补充

研判列表和详情可返回 `alarm_verification={event_id,conclusion,note,version,verified_at}`。仅在具备告警读取权限、关联告警和事件仍属同一目标/组织/区域/来源，且核实发生在本次研判形成之后时提供；关联按原引擎告警、合并成员、人工转告警历史的既有优先顺序解析，不读取同目标其他历史告警。共享已核实记录不再要求重复核实，也从 `needs_review=true` 结果中排除。新研判不得沿用形成之前的核实。原 `legal_status`、`review` 及历史事实不改写，已核实事件不代表算法准确性已验证或反制获准。有关联告警时不再返回本页 `REVIEW` 动作，前台进入该告警查看处理情况。

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
- `alarm_merge_member(member_id, group_id, evaluation_id UNIQUE, alarm_id?, member_kind CREATED|MERGED|UPGRADED|DOWNGRADED|MANUAL_ESCALATION|ESCALATED, severity_before?, severity_after, created_at)`
- `alarm_escalation(escalation_id, alarm_id, seq, group_id?, evaluation_id UNIQUE, trigger_kind ENGINE|MANUAL, severity_before, severity_after, reasons_added JSONB, reasons_after JSONB, note?, actor_id?, owner_org_id, district_id, created_at)`，`UNIQUE(alarm_id, seq)`，只增（2026-10-06，见文末）
- 视图 `v_rule_effect_fact(evaluation_id, evaluated_at, mode, subject_kind, target_id, plan_id, rule_set_version_id, param_status, legal_status, manual_status, review_state, has_alarm, merge_kind, alarm_id, group_id, supersedes_evaluation_id, source_mode, owner_org_id, district_id)`

`assessment_result` 仍是计划维度投影：仅 ACTIVE 且 `plan_match_code ∈ {FULL, PARTIAL}` 时同事务追加一行（`checks` 由 `hit_details` 压缩为 `{rule_code,result_code,reason_code}`，`rule_version_id` 取规则集内 C03 的 `rule_version`），重算时 `supersedes_assessment_id` 指旧行。SHADOW 与无计划目标只落 `rule_evaluation`。

## 引擎接口（`modules/assessment/engine/RuleContracts.java`，领导冻结）

`SpatialFactPort { airspaceHits(targetId, asOf); distanceToRoute(targetId, routeVersionId); ambiguousEffectiveAirspaceVersion(asOf) }` 是唯一 PostGIS 依赖。`RuleCheck { ruleCode(); defaultPriority(); evaluate(EvaluationContext, RuleParams) → HitDetail }`。`RuleParams.number/integer/bool/string/list(ruleCode, key)` 缺参数抛 `IllegalStateException`（配置缺失是部署错误，不是业务未知）；唯一例外是 C03 评分用的 `C03.severity.<reason>`：缺项按 0 计分（`RuleParams.has`），不让整次研判失败。

评估流程：取 ACTIVE/SHADOW 版本 → 收集 `target_latest_state`、最近 `C03.track_points` 个轨迹点、候选计划、生效空域（`valid_from ≤ as_of < valid_to`）、空间事实 → 新鲜度（SCHEDULED：`observed_at ≥ now − C03.fresh_seconds` 否则 STALE → `NOT_APPLICABLE/STATE_STALE`；REPLAY/MANUAL 以 `observed_at` 为 `as_of`）→ C01 → C02-x → C03 → C06 → 写 `rule_evaluation` + 投影 + `legality_review(PENDING_REVIEW)`（`NOT_APPLICABLE` 研判不建复核行，否则 STALE 目标每 tick 都会灌满队列）；一条研判一个事务。

### C01 计划匹配（E2）

候选：同 `(owner_org_id, district_id)`、`status_code<>'CANCELLED'`（已取消的计划不授权飞行；`COMPLETED` 仍参与，超时继续飞才能对上本机计划）的 `flight_plan`，`uav_sn` 相等 **或** `[start_at − C01.time_window_min, end_at + C01.time_window_min)` 覆盖 `as_of`。五维：时间窗 MATCH/MISMATCH/UNDETERMINED(`PLAN_TIME_UNKNOWN`)；走廊（`distanceToRoute ≤ 半宽 + C01.corridor_tolerance_m`，与 C02-3 同源）MATCH/MISMATCH/UNDETERMINED(`CORRIDOR_WIDTH_UNKNOWN`/`POSITION_UNKNOWN`)；身份（`target.uav_sn` vs `plan.uav_sn`，目标无 sn → `IDENTITY_CLUE_MISSING`，页面文案"线索缺失"）；起降点恒 `TAKEOFF_POINT_UNAVAILABLE`；飞手/单位恒 `PILOT_UNIT_UNAVAILABLE`。等级：任一可判维度 MISMATCH → NONE；时间+走廊 MATCH 且身份 MATCH → FULL；时间+走廊 MATCH 且身份 UNDETERMINED → PARTIAL；时间或走廊 UNDETERMINED → UNDETERMINED；多候选同优且非 NONE → `PLAN_AMBIGUOUS` → UNDETERMINED；无候选 → NONE(`NO_PLAN_CANDIDATE`)。全部候选都是 NONE 时只说本机的情况：有同编号计划 → 挂本机计划（时段对得上的优先，否则离 `as_of` 最近的），原因写它没对上的维度（页面"不在计划时段"/"不在航线走廊内"）；没有同编号计划 → 不挂任何计划（别人的计划不拿来比偏航、高度），目标有编号时首要原因 `IDENTITY_MISMATCH`（"编号不匹配"）。计划主体已取消 → 无候选。

### C02 检查（E1）

| 代码 | 事实 | FAIL 原因码 | UNDETERMINED 原因码 |
| --- | --- | --- | --- |
| C02-1 禁飞空域 | kind ∈ `C02-1.kinds`；`ST_Touches` → 未知优先；`ST_Covers` 且（无高度带 或 目标同基准高度在带内） | `INSIDE_RESTRICTED_AIRSPACE` | `BOUNDARY_POLICY_UNKNOWN`、`POSITION_UNKNOWN`、`ALTITUDE_DATUM_OR_RANGE_UNKNOWN`、`VERSION_AMBIGUOUS` |
| C02-2 空域限高 | kind ∈ `C02-2.kinds` 水平覆盖；目标高度按 `airspace_version.altitude_datum` 取 `altitude_amsl_m` 或 `height_agl_m` | `AIRSPACE_ALTITUDE_EXCEEDED` | 同上 |
| C02-3 航线偏离 | 距中心线 − 半宽 > `C02-3.tolerance_m` | `ROUTE_DEVIATION` | `CORRIDOR_WIDTH_UNKNOWN`、`POSITION_UNKNOWN`；无计划 NOT_APPLICABLE；C01 为 NONE 时即使挂着本机计划也 NOT_APPLICABLE（2026-10-08 新-15） |
| C02-4 时间窗 | `as_of ≥ end_at + C02-4.grace_min` 或 `< start_at − grace` | `TIME_WINDOW_OVERRUN` | `PLAN_TIME_UNKNOWN`；无计划 NOT_APPLICABLE |
| C02-5 夜航 | `C02-5.timezone` 本地时 ∈ [`night_from`, 24) ∪ [0, `night_to`)，且 C01 没有匹配上计划（FULL/PARTIAL 即已在计划时段内，容差与白天同为 `C01.time_window_min`；超时另由 C02-4 判）；按规定无需申请的飞行（新-28）夜间 PASS | `NIGHT_FLIGHT` | — |
| C02-6 飞手距离（原超视距） | 只作提示、不判违规（2026-10-08 新-29）：目标与飞手位置距离 > `C02-6.vlos_m` 时仍 PASS，`facts` 加 `beyond_vlos=true`、`vlos_m`、`pilot_distance_note`，`message` 为 `飞手离无人机约 X 米（超过 {vlos_m} 米），是否经批准请核实`（X 四舍五入到米，精确值在 `facts.distance_m`） | —（10-07 至新-29 之前的研判里是 `BVLOS_EXCEEDED`） | 目标位置缺失 `POSITION_UNKNOWN`（不挡结论）；无飞手位置为 NOT_APPLICABLE（`PILOT_POSITION_UNAVAILABLE`，不进未知原因） |
| C02-7 计划高度 | 目标同基准高度 > `route_version.max_altitude_m` 或 < min | `PLAN_ALTITUDE_EXCEEDED` | 基准缺失；无计划 NOT_APPLICABLE |
| C02-8 临时限制 | kind ∈ `C02-8.kinds` 且生效窗口内覆盖 | `TEMPORARY_RESTRICTION_ACTIVE` | 同 C02-1 |

### C03 四态（E1，短路顺序）

1. NO_STATE / STALE → `NOT_APPLICABLE`。
2. 质量门：`fusion_confidence`（缺则 `classification_confidence`）< `C03.conf_min` → `LOW_CONFIDENCE`；轨迹点数 < `C03.min_points` → `TRACK_DEGRADED`；相邻点间隔 > `C03.gap_seconds` → `TRACK_BRIDGED`；任一 → `UNDETERMINED`。
3. C01 NONE → `C03.no_plan_status`（历史演示默认 `ILLEGAL`，原因 `NO_AUTHORIZATION`；已过质量门、类别为无人机，行为项依据不足也不降为不可判定，计划授权待核对由 `decision_assurance` 交人工复核）。2026-10-08 r2 改为 `LEGAL`，仅未匹配计划不添加违规原因，继续执行其余检查及证据充分性校验，具体以本文顶部修订说明为准。C01 UNDETERMINED 且 C02-1/2/8 无 FAIL → `UNDETERMINED`，有空域 FAIL 则照常走第 4 步判 `ILLEGAL`（进禁飞/限高/临管空域不取决于属于哪个计划）。
3. C01 NONE → `C03.no_plan_status`（默认 `ILLEGAL`，原因 `NO_AUTHORIZATION`；已过质量门、类别为无人机，行为项依据不足也不降为不可判定，计划授权待核对由 `decision_assurance` 交人工复核）；完全没有报备任务、离地不超过 120 米、不在管控空域里的视同 `LEGAL`，按规定无需申请（2026-10-08 新-28，见文末）；C01 UNDETERMINED 且 C02-1/2/8 无 FAIL → `UNDETERMINED`，有空域 FAIL 则照常走第 4 步判 `ILLEGAL`（进禁飞/限高/临管空域不取决于属于哪个计划）。
4. C02-1/2/8 任一 FAIL → `ILLEGAL`；任一 UNDETERMINED（无 FAIL）→ `UNDETERMINED`。
5. C02-3/4/5/7 任一 FAIL → `ILLEGAL`；应用服务再校验证据充分性，不充分降为 `UNDETERMINED`（空域违规与无计划 `NO_AUTHORIZATION` 除外）。
6. 其余检查有 UNDETERMINED（排除 `C03.ignore_undetermined_rules`）→ `UNDETERMINED`；否则 `LEGAL`。

C02-6（飞手距离）不参与结论：超过阈值只在明细里提示“是否经批准请核实”，它判不清也不挡 `LEGAL`，任何规则集版本都一样（2026-10-08 新-29，取代 2026-10-07 的“单独超视距判非法、固定低风险”第 6 步，见文末）。

`violation_reasons` = 全部 FAIL 原因码；评分仅 ILLEGAL/ABNORMAL：`score = 100·Σ w_k·F_k`（因子：最大违规严重度 `C03.severity.<reason>`、计划匹配 NONE 1/PARTIAL、UNDETERMINED .5/FULL 0、限制空域命中 1/0、轨迹桥接 .6/0、`1 − confidence`），`grade` 按 `C03.grade.high/medium`；违规里有 `INSIDE_RESTRICTED_AIRSPACE`（进禁飞区、管制区）时固定 `HIGH`，不看加权分数，分数照常给出（2026-10-08 新-22，见文末）。

### 模式、回滚、重算、C06

- SHADOW：只写 `rule_evaluation(mode=SHADOW)`，`alarm_outcome={"kind":"SUPPRESSED_SHADOW"}`，不投影、不告警，默认队列不显示。一个规则集可同时有 ACTIVE 与 SHADOW 版本。
- 回滚：`previous_active_version_id` 设为 active（头行条件更新）+ `rule_set_activation(ROLLBACK)`；旧研判不改。
- 重算：新 `rule_evaluation(trigger=RECOMPUTE, supersedes_evaluation_id=旧)`，旧 `legality_review → SUPERSEDED`（version+1，历史 `RECOMPUTE`）。
- C06（E2）：`AlarmIngestionService.ingest(TrustedAlarmFact(sourceId, sourceAlarmId, targetId, alarmType, severity, occurredAt, receivedAt, detail, sourceMode))` 镜像 `RiskIngestionService`：校验 → `lockSource` → 目标元组存在且目录启用（否则 `INVALID_ALARM_FACT`，引擎记 `alarm_outcome.kind=BLOCKED`）→ `(source_id, source_alarm_id)` 幂等 → 插 `alarm` → `UavEventRepository.createForAlarm(…, PENDING_VERIFICATION)`。来源按目标 `source_mode` 取 `rule-engine-legality-{mode}`；`source_alarm_id = "eval:" + evaluation_id`（手动 `"manual:" + evaluation_id`）；`alarm_type = RULE_LEGALITY`；`severity` 由 `C06.severity_by_grade`。合并：同目标同类型 OPEN 组 `FOR UPDATE`；窗口内：等级更高或带来新的违规原因 → ESCALATED 升级组内当前告警（不建告警，2026-10-06 起，见文末）；同级且无新原因 → MERGED 不建告警、`hit_count++`、延长窗口；更低 → DOWNGRADED；当前告警已核实为误报时更高等级且在升级窗 → 新告警 UPGRADED；过期 → 新组。自动关闭：`window_expires_at + C06.auto_close_min < now` 且最近研判非 ABNORMAL/ILLEGAL → `AUTO_CLOSED`，**不改 `uav_event.state_code`，`alarm` 行永不 UPDATE**。REJECT 复核不删告警/组。

## 历史 DEMO 参数目录（`LEGALITY-DEMO` v1，全部 `param_status=DEMO`）

本表保留历史版本原值，不代表本次待启用的确认版本；r2 的 `C03.no_plan_status=LEGAL`，其余确认口径见本文顶部。

| rule_code | key | 值 | 类型/单位 |
| --- | --- | --- | --- |
| C01 | time_window_min | 10 | INTEGER min |
| C01 | corridor_tolerance_m | 100 | NUMBER m（必须明显大于 C02-3.tolerance_m，否则"已匹配计划但偏航"永远不可达） |
| C02-1 | kinds | PROHIBITED,RESTRICTED | LIST |
| C02-2 | kinds | HEIGHT_LIMIT,ALTITUDE_LIMIT | LIST |
| C02-3 | tolerance_m | 20 | NUMBER m |
| C02-4 | grace_min | 10 | INTEGER min |
| C02-5 | timezone / night_from / night_to | Asia/Shanghai / 20 / 6 | STRING / INTEGER h |
| C02-6 | vlos_m | 500 | NUMBER m（有飞手位置时超过它只提示“是否经批准请核实”，不判违规，新-29；仍是演示值，列在待确认事项） |
| C02-8 | kinds | TEMPORARY,TEMPORARY_CONTROL | LIST |
| C03 | fresh_seconds / track_points / conf_min / min_points / gap_seconds | 120 / 10 / 0.75 / 3 / 30 | INTEGER s / INTEGER / NUMBER / INTEGER / INTEGER s |
| C03 | no_plan_status | ILLEGAL | STRING |
| C03 | ignore_undetermined_rules | C02-6 | LIST |
| C03 | w.violation / w.plan_match / w.airspace / w.track / w.confidence | 0.40 / 0.25 / 0.15 / 0.10 / 0.10 | NUMBER |
| C03 | severity.INSIDE_RESTRICTED_AIRSPACE / AIRSPACE_ALTITUDE_EXCEEDED / TEMPORARY_RESTRICTION_ACTIVE / NO_AUTHORIZATION / ROUTE_DEVIATION / PLAN_ALTITUDE_EXCEEDED / TIME_WINDOW_OVERRUN / NIGHT_FLIGHT | 1.0 / 0.9 / 0.9 / 0.8 / 0.6 / 0.5 / 0.4 / 0.3 | NUMBER（不设 `severity.BVLOS_EXCEEDED`：新-29 起超视距不算违规、不再产生这个原因码；其他原因码缺严重度时 C03 按 0 计入评分，研判照常给出结论） |
| C03 | grade.high / grade.medium | 67 / 34 | NUMBER |
| C06 | dedup_window_min / upgrade_window_min / auto_close_min | 5 / 10 / 15 | INTEGER min |
| C06 | severity_by_grade | HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW | LIST |

## 接口

```text
GET  /api/v1/rule-sets                                   rule:read
GET  /api/v1/rule-sets/{code}/versions                   rule:read   items:{rule_set_version_id,version_no,status_code,param_status,valid_from,valid_to,is_active,is_shadow,activation_allowed,activation_block_reason?}
GET  /api/v1/rule-set-versions/{id}                      rule:read   {…, members:[{rule_code,rule_version_id,priority,enabled}], params:[{rule_code,key,value,type,unit,status,note}]}
POST /api/v1/rule-sets/{code}/activate                   rule:manage {rule_set_version_id,note,expected_version}
POST /api/v1/rule-sets/{code}/rollback                   rule:manage {note,expected_version}
POST /api/v1/rule-sets/{code}/shadow                     rule:manage {rule_set_version_id|null,note,expected_version}
GET  /api/v1/rule-sets/{code}/activations                rule:read   resulting_version ASC
GET  /api/v1/rule-runs?mode&trigger_kind&from&to         rule:read   started_at DESC, run_id DESC
GET  /api/v1/rule-runs/{id}                              rule:read
POST /api/v1/legality-evaluations                        assessment:evaluate(+源读) {subject_kind TARGET|PLAN, subject_id, mode ACTIVE|SHADOW} → 201 {run_id, evaluation}
GET  /api/v1/legality-evaluations?mode&latest_only&legal_status&plan_match&review_state&subject_kind&target_id&object_type_code&plan_id&from&to&owner_org_id&district_id&source_mode   assessment:read  排序 evaluated_at DESC, evaluation_id DESC
GET  /api/v1/legality-evaluations/{id}                   assessment:read  {…, hit_details, review:{state,manual_status,version}, allowed_actions, supersedes_evaluation_id, superseded_by_evaluation_id, alarm_id?, assessment_id}
GET  /api/v1/legality-evaluations/{id}/revisions         assessment:read  version ASC
POST /api/v1/legality-evaluations/{id}/revisions         assessment:revise {conclusion CONFIRM|REJECT|OVERRIDE, override_status?, note, expected_version}
POST /api/v1/legality-evaluations/{id}/recompute         assessment:evaluate {note, expected_version} → 201 新研判
POST /api/v1/legality-evaluations/{id}/alarms            assessment:escalate {note, expected_version} → 201 {alarm_id, event_id}
GET  /api/v1/rule-effects/facts?mode&from&to&…           assessment:read + rule:read（v_rule_effect_fact 分页；mode 默认 ACTIVE，SHADOW 仅显式指定时返回）
GET  /api/v1/rule-effects/summary?mode&from&to&timezone&source_mode&owner_org_id&district_id   同上；响应回显 mode
```

`summary`：`evaluations, alarm_worthy, alarms_created, alarms_merged, convergence_ratio, reviewed, false_positive_rate(REJECTED/reviewed), miss_rate(OVERRIDE 由 LEGAL|UNDETERMINED 改为 ILLEGAL|ABNORMAL / reviewed), manual_override_rate((REJECTED+OVERRIDDEN)/reviewed)`；分母 0 → `{value:null, availability:"NO_DENOMINATOR"}`。`allowed_actions`：PENDING_REVIEW 且有 revise → `REVIEW`；非 SUPERSEDED 且有 evaluate → `RECOMPUTE`；`legal_status ≠ LEGAL` 且无 `alarm_id` 且有 escalate → `ESCALATE`。`hit_details` 元素：`{rule_code, rule_version_id, result_code, reason_code, severity, facts{…}, params[{key,value,status}], evidence[{kind,id}], message}`。

版本列表的 `activation_allowed` 与激活接口的守卫同源：已发布、参数为 `CONFIRMED`（或显式测试环境 `local`+`qa`/`test` 且 `allow-demo-active=true` 时的 `DEMO`）、且尚未生效；为 `false` 时 `activation_block_reason` 给出中文原因（版本尚未发布 / 演示参数尚未经业务方确认，正式环境不能启用 / 该版本已是生效版本）。管理端“规则管理 → 研判规则集”据此给出“启用此版本”；账号是否有 `rule:manage` 仍由激活接口单独校验。

## 2026-09-17 合法性页面无人机范围

列表新增可选 `object_type_code=UAV|BIRD|UNKNOWN`，按关联目标当前 `target.object_type_code` 筛选；列表和 `total` 在分页前使用同一条件。不传此参数时保留原查询范围，规则引擎继续保留非无人机与类别未知的研判历史，风险页面与规则效果统计口径不变。

使用该参数要求 `assessment:read` 与 `target:read`，权限校验先于参数解析，非法类别返回 400。目标必须与研判属于同一有效组织/区域元组；目标不可见、缺失或类别未知时不计入 `object_type_code=UAV`。没有匹配计划的 UAV 目标仍会返回；PLAN 主体尚无关联目标时不满足 UAV 条件，有明确 UAV 观测来源时可满足条件。

列表和详情新增可省略字段 `object_type_code`，与 `target_id/target_no` 同步受目标读取权限及元组可见性约束；它表示当前目标类别，不是历史研判快照类别。业务前台合法性页面的列表、顶部统计及目标入口均使用 `object_type_code=UAV`，直接打开详情还应核对该字段；目标转为非无人机或未知类别后不继续展示其合法性详情。管理前台 `ruoyi-ui` 未发现此接口消费者；融合感知等未传此参数的已有读取保持兼容。

## 稳定错误码

`RULE_SET_NOT_FOUND(404)`、`RULE_VERSION_NOT_FOUND(404)`、`RULE_VERSION_NOT_PUBLISHED(409)`、`DEMO_PARAMS_NOT_ALLOWED(409)`、`NO_PREVIOUS_VERSION(409)`、`NO_ACTIVE_RULE_SET(409)`、`SHADOW_VERSION_NOT_SET(409)`、`SPATIAL_BACKEND_UNAVAILABLE(500)`、`LEGALITY_EVALUATION_NOT_FOUND(404)`、`EVALUATION_SUPERSEDED(409)`、`ALARM_ALREADY_LINKED(409)`、`INVALID_CONCLUSION(400)`、`OVERRIDE_STATUS_REQUIRED(400)`、`INVALID_ALARM_FACT(400，内部)`，沿用 `FORBIDDEN / NOT_FOUND / UNKNOWN_FIELD / INVALID_REQUEST / INVALID_TRANSITION / VERSION_CONFLICT / IDEMPOTENCY_REPLAY / IDEMPOTENCY_KEY_REUSED`。

## 调度与回放

`RuleEngineWorker @Scheduled(fixedDelayString="${app.rule-engine.poll-millis:5000}")`，`@ConditionalOnProperty(app.rule-engine.enabled)`（test 默认 false）。每 tick：条件更新 `rule_engine_lease` → 待评估主体 = `target_latest_state.observed_at >` 该目标最近同模式研判的 `observed_at` 且新鲜（同口径比较；`updated_at` 总晚于 `observed_at`，用它会每 tick 重复研判），`LIMIT app.rule-engine.batch-size(50)` → 每主体独立事务 → `rule_run` 计数 → 自动关闭到期组。不扩展 `OutboxWorker`。回放回归：`RuleReplayRegressionTest`（H2 桩事实）+ `Stage7PostgresTest`（PostGIS 端到端）+ `RuleReplayRunner`（`!production`，`app.rule-engine.replay.run-on-start`，结论不符启动失败，已有同数据集 `rule_run` 则跳过，不覆盖 `legality_review`）。

2026-10-06 调度节奏（ZT-06 / ZT-20，判定规则与参数不变）：`poll-millis` 默认改为 1000。定时口径的待评估主体在上述条件上再加三条——新鲜：`observed_at` **或** `received_at` 在 C03 `fresh_seconds` 窗口内（报文时刻已过期但刚收到的目标也评一次，按 C03 得 `STALE` / `NOT_APPLICABLE` + `STATE_STALE`，不产生告警，用来留下"为什么没判"的记录）；节流：`target.created_at`（平台建档时刻）在 `fast-window-seconds`（30）内的新目标每 tick 都评，其余目标同模式同版本 `reevaluate-millis`（5000）内已有研判的等下一轮；顺序：同模式同版本从未研判的目标排最前，再按状态更新时间。一批取满时在 `drain-budget-millis`（3000）内接着取下一批。手动、回放等非定时调用沿用原口径。索引 `idx_zt06_rule_evaluation_recent`（V202610069081）。生产默认调度线程由 1 改为 4（`spring.task.scheduling.pool.size`，`APP_SCHEDULING_POOL_SIZE`），本地 profile 原本就是 4。

## 文件归属

领导：`PermissionCode.java`、迁移 040、`GlobalExceptionHandler`、`AuditLabels`、`modules/assessment/engine/RuleContracts.java`、本文。E1：迁移 041、`R__stage7_*`、`modules/assessment/engine/**`（除 RuleContracts 与 `checks/PlanMatchCheck`）、`modules/assessment/api/{RuleSetController,RuleRunController,RuleDtos}`、`application/RuleSetManagementService`。E2：迁移 042、`engine/checks/PlanMatchCheck`、`modules/alarm/application/AlarmIngestionService`(+`TrustedAlarmFact`)、`modules/alarm/infrastructure/AlarmMergeRepository`、`modules/assessment/application/{LegalityReviewService,AlarmEscalationService,LegalityEvaluationReadService,RuleEffectReadService}`、`api/{LegalityEvaluationController,RuleEffectController}`、`infrastructure/LegalityReviewRepository`、`integration/mock/LocalStage7RuleEngineSeeder`、`RuleReplayRunner`、`dongying-vue/src/services/legalityApi.js`、`pages/LegalityPage.vue`、`ui/legalityReviewModal.js`。助手：`Stage7PostgresTest`、`ProductionStage7SeedIsolationTest`、`RuleReplayRegressionTest`。

## 尚未接入

身份线索（TDOA/5G-A）、起降点、飞手/单位、真实规则参数确认、处置（转入处置按钮禁用）。飞手位置自阶段 8.5 随观测接入；超视距 2026-10-07 起曾单独判违规，2026-10-08 起只作提示（新-29，见文末）。

## 2026-10-08：人工复核后的生效结论

研判列表、详情及复核返回值新增 `effective_legal_status`。`legal_status` 继续保留原始系统结论（历史 ABNORMAL 的现行投影仍为 UNDETERMINED）。仅 ACTIVE 研判自身的 CONFIRMED/OVERRIDDEN 复核中明确保存的合法、非法、不可判定人工结论生效；被替代记录保留当次人工结论供历史查询，新的研判不继承旧记录的改判。REJECT 未指定替代结论，不能自动理解为合法；SHADOW 不采纳人工结论。

同日用户补充确认：REJECT 表示人工认定本次系统误判，主结果显示“系统误判”，不继续显示原来的不可判定、非法或合法。读取投影 `effective_legal_status=REJECTED` 表达该复核结果，原始 `legal_status`、`manual_status=null`、规则依据和历史保持。`legal_status=REJECTED` 可筛选此结果；原有“已驳回”复核筛选继续可用。summary 新增 `rejected` 单独计数，已驳回记录不计入合法、非法、不可判定，也不再进入 `needs_attention=true`。页面保留三类合法性页签，通过“全部”及“已驳回”查看系统误判。重新研判使旧头行变为 SUPERSEDED 后，仍依据该条研判的 REJECT 历史保留系统误判展示，新研判独立计算；SHADOW 保持系统原始结果。REJECTED 只扩展读取和展示投影，不作为引擎结论或可选人工改判结论。

列表的现行 `legal_status` 分类筛选、`needs_attention`、全量 summary 与页面主结论均使用同一生效结论，在分页前计算。兼容旧客户端的 ABNORMAL 历史筛选和规则效果分析继续使用原始事实。原始规则命中、风险评分、告警核实及动作授权不被改写。

同一研判只允许从 PENDING_REVIEW 执行一次确认、驳回或改判，重复复核返回 `INVALID_TRANSITION`。复核历史包含重新研判、转告警等追加操作，`version` 是并发版本而非复核次数。前台不显示“第几次复核”，记录未超过当前每页条数时隐藏历史分页；已有审计记录保留。

## 2026-09-17：算法证据充分性与人工复核分流

本节扩展原 C01–C03 分类结果，不改变既有 `legal_status`、风险评分或通知/处置状态机。算法版本 `EVIDENCE_SUFFICIENCY_V1` 在每次研判事务内读取同一目标状态、轨迹质量、计划匹配、规则参数和命中事实，计算该结论能否自动采纳；它不是经过统计校准的正确概率。

`rule_evaluation` 新增三个可空列：`decision_algorithm_version VARCHAR(64)`、`decision_assurance_code VARCHAR(32)`、`decision_assurance_reasons JSON`（PostgreSQL 为 JSONB）。新研判 INSERT 时一起写入并冻结；三个字段同时为空表示历史未保存算法结果，禁止回填成已验证。H2/PG 检查约束要求新值成组有效；PG 追加触发器阻止更改这三列。迁移为 `V202609170020` 与 PostgreSQL 专用 `V202609170020.1`，不修改旧迁移。

`GET /legality-evaluations` 列表和详情均增加：

```json
{
  "decision_assurance": {
    "algorithm_version": "EVIDENCE_SUFFICIENCY_V1",
    "status": "SUFFICIENT",
    "review_required": false,
    "reasons": [],
    "accuracy_status": "NOT_VALIDATED"
  }
}
```

- `SUFFICIENT`：有效输入满足既有质量门。合法结果还要求计划身份完整匹配、必要检查完整且无阻断项；非法结果须有确定的禁飞/空域限高/临管违规事实。明确违规可以独立于计划匹配成立，不因无关未知而强制复核。
- `INSUFFICIENT`：保存本次阻断原因。无匹配计划本身不证明无授权，身份缺失不证明属于该计划，只有 ABNORMAL 风险结论不擅自改成非法。
- `NOT_APPLICABLE`：本次不适用实际飞行判定，保留原原因。
- `UNAVAILABLE`：只读层对三个新列均为 NULL 的旧记录返回，`algorithm_version` 按全局 null 规则省略，原因 `ALGORITHM_RESULT_UNAVAILABLE`。三列不完整、非法状态或原因 JSON 格式不合法时返回既有 `INTERNAL_ERROR`，不降级成可靠结果。
- `accuracy_status=NOT_VALIDATED`：功能测试与模拟回放不等于统计准确率验证，不返回虚构百分比。

真实观测需确认参与质量判断的 `fresh_seconds / track_points / conf_min / min_points / gap_seconds`；判非法时继续校验支撑该违规的参数，判合法时校验必要检查及忽略未知规则的配置。模拟/回放可按演示参数验算，来源与参数标识原样保留，不冒充真实算法验收。

`review_required` 独立于操作者权限：仅 ACTIVE、结论不是 NOT_APPLICABLE、复核头仍为 PENDING_REVIEW、尚未被取代且充分性为 INSUFFICIENT 或旧记录未知时为 true。已复核、影子模式和不适用不重复要求人工操作。原 `review.state` 是复核头状态，不单独表示系统必须等待人工；`allowed_actions` 继续表示有权进行的动作，REVIEW 可供自愿纠错，不等于强制复核任务。

列表新增可选 `needs_review=true|false`。未传不增加条件；显式 false 与未传不同。与 DTO 使用同一业务条件，在 count 与列表分页前筛选，继续叠加组织/区域、目标类别、最新研判等既有条件。`信息待核对` 使用 `needs_review=true`，不能固定筛选 `legal_status=UNDETERMINED` 或在分页后删行。

前端根据分流结果隐藏或显示右侧“核对信息缺口”。可靠合法与可靠非法都没有主复核要求；低可靠合法/非法保留原标签并标识“暂不能可靠确认”。无提交权限时只读原因；旧响应缺新字段显示未提供算法可靠性结果；复核、重新研判、刷新与重新登录均回读服务端，保留原结论、来源及历史。

### 2026-09-18 归并研判与告警关联

新产生的 MERGED/DOWNGRADED 研判通过 `alarm_id` 关联归并当时已有的告警，通知资格校验因此可以使用最新研判。该关联不表示本次新建告警；新增数量仍以 `alarm_outcome.kind` 的 CREATED/UPGRADED 为准。合并成员的 `alarm_id` 仍只记录本次新建，原告警时间、历史研判和既有通知任务不回写。后续升级不得使先前研判的关联改指新告警。旧记录缺少关联仍按旧事实读取。

### 2026-09-18：停用无人机人工现场记录

- `POST /api/v1/uav-events/{id}/advisory/actions` 不再接收 `kind=OBSERVATION`，返回 400 / `MANUAL_OBSERVATION_RETIRED`；历史只读查询、通知记录及冻结材料保留。
- 旧人工观察不再阻断短信/电话，也不再决定列表进度或反制资格。通知仍检查原时效、当前规则结论、接收对象和独立渠道回执。
- 反制申请、执行、排队下发及续链统一读取当前系统依据：已核实事件、当前 UAV 观测、有效 C03.fresh_seconds、关联本事件的最新 ACTIVE / ILLEGAL / FRESH 研判、SUFFICIENT 充分性及空未知原因。无依据或过期时明确阻断，删除空历史兼容放行。
- 权限、审批、设备范围、授权时间窗及急停后的设备停机核查保持；此变更未实现自动飞离解除、自动反制或自动处罚。


### 2026-09-22 核实位置筛选

`GET /legality-evaluations` 新增可选布尔参数 `has_alarm`：省略为全部，`true` 为关联告警，`false` 为无关联告警。按当前研判的引擎告警、合并成员告警及人工转告警历史判断，不按目标的其他历史告警推断。该条件与最新研判、待处理、复核状态、区域、计划等条件取交集，在分页前执行，列表和 total 共用谓词。关联 ID 仍按原动作权限与数据范围脱敏；不可见的告警不因此变成无告警。此参数不修改核实结果、权限和历史。

业务前台用于“核实位置”筛选与同口径统计；管理前端当前无此接口消费者，既有调用省略参数时行为不变。


### 2026-09-23：合法性统计聚合

新增只读 `GET /api/v1/legality-evaluations/summary`，返回 `{total, legal, abnormal, illegal, undetermined, not_applicable}` 六个非负计数。与列表复用参数白名单、权限检查和查询谓词（包括 `latest_only`、`object_type_code`、`needs_attention`、`needs_review`、`has_alarm`、区域、单位、计划、状态和时间）。`page/size` 仍按列表校验，但不限制统计范围。空结果各项为 0。

权限先于参数解析：需要 `assessment:read`；带目标/类别筛选须 `target:read`，带计划筛选须 `flight:read`；不返回关联实体或动作资格。一次 SQL 条件聚合，无列表 DTO、逐条关联查询或统计缓存；最新记录及历史保护规则不变。

业务前台合法性页一次读取五项可见统计，在列表请求结束后启动，不等待详情和复核历史，以避免两路重查询争抢数据库。后台管理端无此列表消费者，现有列表、详情与写接口未改变；旧服务不支持新端点时前台明确显示读取失败。


### 2026-10-06 告警升级并入原告警（BUG-11 偏航告警不升级、BUG-16 同一架无人机两条告警）

- 同目标 `RULE_LEGALITY` 的 OPEN 组在去重窗内再次命中时，与组内当前告警（`latest_alarm_id`）比：等级更高，或带来告警里还没有的违规原因（例如夜航告警之后又进入禁飞区、计划内无人机偏航），记 `member_kind=ESCALATED`：不建告警、不建事件、不另起核实，追加一行 `alarm_escalation`，组 `current_severity` 取较高者、`hit_count++`、延长去重窗。这条规则不受 `upgrade_window_min` 限制。同级且原因都已在告警里仍为 MERGED，更低仍为 DOWNGRADED，都只计数。
- 当前告警的事件已核实为误报（`FALSE_POSITIVE`）时不往里并：更高等级且在升级窗内仍按原规则新建 UPGRADED 告警重新核实，其余只计数。已核实属实（`CONFIRMED`）的照样升级，事件状态不变，不要求重新核实。
- 人工转告警 `POST /legality-evaluations/{id}/alarms`：同目标 OPEN 组仍在去重窗内且当前告警不是误报时，并入这条告警，201 返回原告警的 `alarm_id/event_id`，追加 `trigger_kind=MANUAL` 的升级记录并写明操作人与说明（等级、原因都没变也记一行）；否则仍新建 `manual:` 告警。
- `alarm_escalation`：每条告警的 `seq` 从 1 递增，最近一行就是当前状态；`reasons_added` 是本次新增的原因，`reasons_after` 是升级后的全部原因（按出现顺序、不重复）；`actor_id` 在 MANUAL 时必填、ENGINE 时为空。PG 触发器拒绝 UPDATE/DELETE，并把它与 `alarm` 一起接到实时推送 `alarm` 主题。告警当前等级 = 最近一行 `severity_after`，没有升级过就是 `alarm.severity`；当前原因 = 最近一行 `reasons_after`，没有就是 `alarm.detail.violation_reasons`。`alarm` 行仍永不 UPDATE（证据链对告警入库等级取指纹）。
- 关联：ESCALATED 成员与本次研判的 `alarm_id` 都指向被升级的原告警，所以“最新研判关联的告警”就是事件所属告警。本节替代 2026-09-18 “合并成员的 `alarm_id` 仍只记录本次新建”：成员 `alarm_id` 记录本次新建或本次升级的告警，MERGED/DOWNGRADED 仍为空。`alarm_outcome.kind=ESCALATED` 计入 `alarm_merged_count` 与 `rule-effects` 的 `alarms_merged`，不计入新增告警。
- 读接口与页面字段见 `docs/backend-stage4/alarm-risk-api-contract.md` 2026-10-06 一节。未改：自动核实条件、移送材料与证据链里冻结的告警入库等级、导出列。

### 2026-10-07 超视距单独违规判非法、固定低风险（已被 2026-10-08 新-29 取代）

> 已取消：2026-10-08 起超视距只作提示、不算违规，见文末新-29 一节。以下为当时口径，留作历史；这期间产生的研判和告警原样保留。

- 业务决定：C02-6 超视距是唯一一项违规时，C03 判 `ILLEGAL`，主原因 `BVLOS_EXCEEDED`，`grade` 固定 `LOW`（不看加权分数），照常走 C06 出告警：`C06.severity_by_grade` 的 `LOW:LOW` 映射为低风险告警，同事务建待核实事件。规则写在 `C03Decision` 第 6 步而不是参数里，已发布的 `LEGALITY-DEMO` 版本不重新发布、不加参数也按此执行；`C02-6.vlos_m` 仍为 500（DEMO）。
- 与其他违规同时出现：等级按原来的方式只由其他违规计算，超视距不参与严重度取最大、不做主原因，高等级不会被压低、低等级也不会被抬高；`violation_reasons` 照常多列一个 `BVLOS_EXCEEDED`（与此前一致，C06 会把它当作新增原因做 ESCALATED 升级）。为此不设 `C03.severity.BVLOS_EXCEEDED`，即使某个版本配置了也不生效。
- 不改的短路：数据过期、质量门、计划不明（如 `PLAN_AMBIGUOUS`）、空域未知、`no_plan_status` 为 UNDETERMINED/ABNORMAL 时结论照旧（`NOT_APPLICABLE`/`UNDETERMINED`），超视距只记在 `violation_reasons`。与依据不足的行为偏差同时出现时照旧按第 5 步降为 `UNDETERMINED`，与没有超视距时一致。其余不阻断第 5 步的未知（如 `PLAN_TIME_UNKNOWN`）同样不阻断第 6 步，未知原因保留给复核。
- 没有飞手位置：C02-6 仍为 `PILOT_POSITION_UNAVAILABLE`，被 `C03.ignore_undetermined_rules=C02-6` 忽略，不告警、不因此不可判定。
- 证据充分性 `EVIDENCE_SUFFICIENCY_V2`：明确的 C02-6 FAIL 与明确的行为偏差一样算作结论依据，不再追加 `DECISIVE_EVIDENCE_MISSING`；计划/身份未核实、未忽略的未知、实测数据配演示参数等原因照旧给出 `INSUFFICIENT`。此前 C02-6 FAIL 不会单独成立 ILLEGAL，已有研判的结果不受影响，算法版本号不变。
- 可读原因：后端只在研判明细 `hit_details[].message`（合法性详情页逐条展示）给出 `超视距飞行（飞手离无人机约 X 米，超过 500 米）`，DEMO 参数时末尾照例附 `；参数为 DEMO 演示值，尚未确认`。`alarm` 行与告警接口不带可读原因，只有 `detail.violation_reasons` 原因码，告警列表文字由前端按原因码映射；接口不新增字段。
- 自动反制不受影响：自动规则的风险等级取最新研判 `grade`，可选条件只有"高风险""中风险或高风险"，`LOW` 不满足任何一项（预置的反制条件为"达到高风险"）。

### 2026-10-08 当前无人机队列稳定显示

`GET /legality-evaluations` 与 `/legality-evaluations/summary` 接受可选 `sort`。省略或 `evaluated_at_desc` 保留原研判时间倒序；`target_created_at_desc` 仅允许与 `latest_only=true&object_type_code=UAV` 同用，在分页前按目标 `created_at DESC, target_id DESC` 排序，再以研判时间及记录 ID 作确定性后序。新目标正常进入队列，同一目标重新研判不会改变所在页；仍返回每个目标最新的结论、时间和依据。筛选、total、来源隔离、对象权限及动作守卫不变，summary 仅校验排序参数，不改变统计口径。
### 2026-10-08 没有飞手位置不挡反制（验收预跑 3-4 / 8-8，新-19）

- 反制资格（申请、执行、排队下发及续链）和暂不反制的“当前可靠明确研判”，原来都要求研判的未知原因为空，现在改为“除 `PILOT_POSITION_UNAVAILABLE` 外没有未知原因”。没有飞手位置只让 C02-6 超视距判不了，它本来就被 `C03.ignore_undetermined_rules` 忽略，不影响结论和证据充分性；黑飞常常测不到遥控器位置，此前这类明确违规的告警连人工反制也申请不了。
- 其他未知原因（计划不明、高度基准或量程未知等）照旧阻断；ILLEGAL / FRESH / SUFFICIENT、事件已核实、当前观测、权限与授权约束都不变。判断只在 `UavAdvisoryRules.noBlockingUnknowns` 一处，`can_request_counter`、自动规则反制、干扰续链、下发前检查和暂不反制同用。飞手自动短信、电话按事件核实状态发送，本来就不看研判的未知原因，不受影响。

### 2026-10-08 没有报备任务、离地 120 米以下的普通区域飞行按规定无需申请（法规核对，确认书 2-2，新-28）

- 业务决定：国家规定小型及以下无人机在真高 120 米以下的适飞空域飞行无需申请；系统分不出机型大小，同时满足下面三条的判 `LEGAL`、不出告警（`NoPlanExemption`，C03 第 3 步视同 `no_plan_status=LEGAL` 继续走第 4–6 步）：
  1. 完全没有报备任务：C01 为 NONE 且没有挂上本机编号的计划（`plan` 为空）。有本机计划却飞出时段或航线的照旧按计划查，结论不变。
  2. 设备报的离地高度 `height_agl_m` 不超过 120 米（含 120）。没有离地高度不推算，照旧按 `C03.no_plan_status` 判。
  3. 不在管控空域里：`PROHIBITED`、`RESTRICTED`、`ALTITUDE_LIMIT` 及 C02-1/C02-2 `kinds` 另配的类型随时都算，`TEMPORARY_CONTROL` 及 C02-8 `kinds` 另配的类型只在生效窗口内算（与 C02-8 同口径），这些空域的关系都必须是 `DISJOINT`；水平上在限高区里、高度没超也不算普通区域。压在边界上（`TOUCHES`）、空域关系不明、同一空域两个版本同时生效（`VERSION_AMBIGUOUS`）的照旧报。
- “看得准”由第 2 步质量门把关（置信度、轨迹点数、断点），不另设条件。120 米是法规数值，与 2-12 禁飞区定级一样写在代码里，已发布的规则集版本不重新发布、不加参数也按此执行。
- C01 明细照实记 FAIL（`NO_PLAN_CANDIDATE` 等），`facts` 加 `no_plan_exempt=true`、`height_agl_m`，`message` 为 `没有报备任务；离地约 N 米，不超过 120 米，不在禁飞区、管制区、限高区、临时管控区内，按规定无需申请`（N 四舍五入到米，DEMO 参数时照例附 `；参数为 DEMO 演示值，尚未确认`）。C02-5 对这类飞行夜间 PASS（夜间只在原有违规上加注，确认书 2-9），C03 与证据充分性都不把 C01 的 FAIL、夜航当作与合法结论冲突，也不要求核对计划授权（不加 `PLAN_AUTHORIZATION_UNVERIFIED`）。
- 已有研判与告警不动。本地回放场景 `no-plan`、`cross-scope` 及相关测试夹具的目标改到离地 150 米，继续验“没有任务 → 无飞行授权”。

### 2026-10-08 超视距只作提示，不算违规（法规核对，确认书 2-10，新-29）

- 业务决定：国家规定只有微型无人机必须在视距内飞，其他经批准可以超视距；系统分不出是不是微型，任务里也没写批没批超视距，所以 2026-10-07 那条“只有超视距时判 `ILLEGAL`、固定 `LOW`、照常告警”取消，C03 删去该步（原第 7 步改为第 6 步）。写在代码里，已发布的规则集版本不重新发布、不改参数也按此执行。
- C02-6 照旧按 Haversine 算目标与飞手的距离：超过 `C02-6.vlos_m`（仍为 500，DEMO，列在待确认事项）时结果仍是 `PASS`，`facts` 带 `distance_m`、`vlos_m`、`beyond_vlos=true` 和 `pilot_distance_note`=`飞手离无人机约 N 米（超过 500 米），是否经批准请核实`（N 四舍五入到米），`message` 为这句（DEMO 参数时照例附 `；参数为 DEMO 演示值，尚未确认`）；不出 `BVLOS_EXCEEDED`、不告警。没超过时与此前相同。
- 没有遥控器位置：这一项不判，结果 `NOT_APPLICABLE`（原因码仍记 `PILOT_POSITION_UNAVAILABLE`），不进 `unknown_reasons`，不影响合法、非法的结论。只有飞手位置、目标位置缺失时是 `UNDETERMINED`（`POSITION_UNKNOWN`），C03 第 6 步和证据充分性都不因 C02-6 的未知挡 `LEGAL`、算依据不足，不看 `ignore_undetermined_rules` 有没有列它；真实观测（`live`）下 C02-6 的演示参数也不要求人工复核。
- 同时有别的违规（如闯禁飞区）：照常按那条违规定级、告警，`violation_reasons` 里没有超视距；那句提示照样留在 C02-6 明细里。
- 目标列表与详情的 `legality_summary` 新增可选字段 `pilot_distance_note`：取最近一次研判 C02-6 `facts.pilot_distance_note`，没有这句时整项省略。页面在合法性研判页、告警详情和目标详情（“遥控器位置”后面）显示它。
- 以前因超视距出的告警和研判原样保留；`BVLOS_EXCEEDED` 原因码、告警筛选项和导出中文名留给这些旧数据。`UavAdvisoryRules.noBlockingUnknowns` 照旧放行未知原因里只有 `PILOT_POSITION_UNAVAILABLE` 的旧研判（新-19）。
- 验证：`C02ChecksTest`、`C03DecisionTest`、`DecisionAssuranceAlgorithmTest`、`LegalityEvaluationServiceTest`、`TargetSummariesApiTest`，端到端 `PilotDistanceNoteFlowTest`（H2）与 `PilotDistanceNoteFlowPostgresTest`（PostgreSQL/PostGIS，由原 `BvlosLowAlarmFlow*` 改写）：有任务按航线飞、飞手 501/800/3000 米外判 `LEGAL`、不告警、明细带提示；499 米与没有飞手位置不带提示；与禁飞区、无授权、偏航同时出现时结论、分数、等级、告警等级与没有飞手位置的同类目标逐项一致。

### 2026-10-08 进禁飞区、管制区不管有没有任务都定高风险（确认书 2-12，新-22）

- 业务决定：对上报备任务的无人机进禁飞区时计划因子为 0，加权分只有约 55（中风险），比超时飞行还低，自动反制只对高风险，流程 1 跑不通。现在 C03 评分后，违规里有 `INSIDE_RESTRICTED_AIRSPACE`（C02-1：禁飞区、管制区）就定为 `HIGH`，不看加权分数；分数照常给出供页面参考。限高（`AIRSPACE_ALTITUDE_EXCEEDED`）、临管（`TEMPORARY_RESTRICTION_ACTIVE`）仍按分数定级。
- 写在 `C03Decision` 里，已发布的规则集版本不重新发布、不加参数也按此执行；已有研判和告警不动。
- 验证：`C03DecisionTest.restrictedAirspaceIsHighEvenWhenTheDroneMatchesItsTask`（对上任务 55 分 → HIGH；超时飞行 LOW；限高、临管 MEDIUM；无任务 80 分 HIGH 不变）。

### 2026-10-08 对不上任务时不再比偏航（验收预跑 2-1，新-15）

- C01 判“对不上任务”（NONE）时，若有同编号的本机计划会挂在匹配结果里，只作超时、任务高度的参考；C02-3 不再拿它比偏航，记 `NOT_APPLICABLE`，违规原因只写无飞行授权（`NO_AUTHORIZATION`）。分数、等级、主原因不变：NONE 时无飞行授权（严重度 0.8）本来就比偏航（0.6）大。
- 演示规则集 C01 走廊容差 100 米、C02-3 偏航容差 20 米：走廊外 20～100 米仍对得上任务，照常判偏航（确认书 2-6）；100 米以外才对不上任务，只判无飞行授权（确认书 2-1）。
- 验证：`C02ChecksTest`、`C03DecisionTest` 对应用例。
