# 阶段 9 飞行监管补齐与第二业务线接口契约

> 状态：领导冻结稿 v1.1（2026-09-07：space-fact 响应体、并发 confirm 的 409 码；v1.0 于 2026-09-06）。**2026-09-09 变更**：外部授权登记按需求确认表增补四 F8 整体撤除——`GET/POST /flight-plans/{id}/authorizations`、`actuals` 响应的 `authorizations` 段、权限 `flight:authorize`、表 `flight_plan_authorization` 均已删除（`V202609090106`），下文相关条目仅作历史记录。配套：`docs/backend-stage9/decisions.md`、`docs/backend-stage3/flight-airspace-assessment-api-contract.md`（阶段 3 只读契约，本文只追加）、`docs/backend-stage4/alarm-risk-api-contract.md`（风险核验，本文只加过滤与可空字段）、`docs/backend-stage7/rule-engine-api-contract.md`（规则参数机制）。约定沿用：`{ok,data}` 包络、snake_case、字符串 ID、epoch 毫秒、`page/size → items/page/size/total`、先鉴权再解析、精确 `(owner_org_id,district_id)` 元组、越权 404、写接口 `Idempotency-Key` + `expected_version`、成功审计同事务、失败审计事务外、未知字段 `UNKNOWN_FIELD`。

## 交付边界

- 空域：新建、追加版本（接替式）、GeoJSON 导入（暂存→确认/放弃）、版本差异；**航线不可编辑**（9-4）。
- 空间安全风险：C04 空中异物规则骨架（鸟群/气球/风筝/孔明灯/其他异物）、C05 机场鸟击骨架、异物细类字典、按细类/等级/状态/高度带的汇总、手动/定时评估；产出仍是阶段 4 的 `flight_risk`，核验与交接复用阶段 4/5。
- 机场基础数据：机场、跑道、进离场航线、保护目标、通报对象，只增不改不删。
- 飞行计划：`GET /flight-plans/{id}/actuals` 聚合（对照/高度关系/最新风险/合法性/授权，各带 `availability`）、外部授权登记（只增）。
- 明确不做：真实鸟类识别与驱鸟、机场通报通道、航线编辑、处罚立案、真实通知渠道。所有阈值 DEMO（`rule_param.param_status='DEMO'`）。

## 数据模型

| 迁移 | 所有者 | 内容 |
| --- | --- | --- |
| `V202609050060__stage9_permissions_and_menus.sql` | 领导（已落地） | 权限 `airspace:manage / airport:read / airport:manage / risk:evaluate / flight:authorize`（sort 960–964）；MODULE 行 `airspace`、`risk` 补 `route_key` |
| `V202609050061__stage9_airspace_write_and_import.sql` | E1 | `airspace_version.kind_code` CHECK 字典；`airspace_version_origin(origin_id, airspace_version_id UNIQUE, origin_kind MANUAL|GEOJSON_IMPORT|SEED, actor_id?, import_item_id?, superseded_version_id?, created_at)`；`airspace_import_batch(batch_id, status STAGED|CONFIRMED|DISCARDED, feature_count, accepted_count, note, owner_org_id, district_id, created_by, created_at, decided_by?, decided_at?, version)`；`airspace_import_item(item_id, batch_id, seq, name?, airspace_no?, kind_code?, boundary_geojson JSON, boundary GEOMETRY(MULTIPOLYGON,4326)?, min/max_altitude_m?, altitude_datum?, valid_from?, valid_to?, issues JSON, accepted BOOLEAN, target_airspace_id?, result_airspace_version_id?)` |
| `V202609050062__stage9_space_risk.sql` | E2 | `space_object_subtype(subtype_code PK, display_name, aliases JSON, enabled)` 五行；`integration_source` 两行 `rule-engine-space-risk-{live,mock}`；规则集 `SPACE-RISK-DEMO` v1 PUBLISHED（DEMO）+ `rule_version` C04/C05 + `rule_param`；`space_risk_fact(risk_id PK→flight_risk, subtype_code, rule_version_id, rule_set_version_id, distance_to_route_m?, corridor_relation INSIDE|NEAR|OUTSIDE|UNKNOWN, altitude_band CLIMB|APPROACH|CRUISE|UNKNOWN, altitude_datum?, object_count?, trend RISING|FLAT|FALLING|UNKNOWN, window_from, window_to, created_at)` 只增；`rule_evaluation_run(run_id, rule_code C04|C05, trigger MANUAL|SCHEDULED, window_from, window_to, status SUCCESS|FAILED|UNAVAILABLE, targets_seen, risks_created, risks_deduplicated, message?, actor_id?, started_at, finished_at?)` |
| `V202609050063__stage9_airport.sql` | E2 | `airport(airport_id, icao_code UNIQUE, name, reference_point GEOMETRY(POINT,4326), elevation_amsl_m?, owner_org_id, district_id, enabled, created_by, created_at, version)`；`airport_runway(runway_id, airport_id, designator, heading_deg, length_m?, centerline GEOMETRY(LINESTRING,4326)?)`；`airport_procedure_route(route_id, airport_id, kind APPROACH|DEPARTURE, name, centerline GEOMETRY(LINESTRING,4326), protect_width_m, min_altitude_m, max_altitude_m, altitude_datum)`；`airport_protected_target(target_id, airport_id, name, kind, location GEOMETRY(POINT,4326), radius_m)`；`airport_notification_target(target_id, airport_id, name, role, channel_kind, enabled)`（只存逻辑名，不存号码/凭据） |
| `V202609050064__stage9_plan_authorization.sql` | E1（9.2） | `flight_plan_authorization(authorization_id, plan_id, document_no, issuer, granted_from, granted_to, scope_note?, recorded_by, recorded_at, source_kind MANUAL|IMPORT)`，`UNIQUE(plan_id, document_no)`，只增 |
| `db/postgresql/R__stage9_airspace_succession.sql` | E1 | 用新触发器替换 `trg_stage3_airspace_version_immutable`：UPDATE 只允许 `valid_to` 从 NULL 变为非 NULL 且其余列不变；DELETE 禁止；导入项几何 `ST_IsValid` + SRID 4326 CHECK |
| `db/postgresql/R__stage9_space_risk_and_airport.sql` | E2 | `space_risk_fact`、`rule_evaluation_run` 只增触发器；机场几何 CHECK 与 GIST |

## 接口

```
# 空域（写：airspace:manage；读沿用 airspace:read）
POST /api/v1/airspaces                                  {airspace_no, name, kind_code, boundary(GeoJSON MultiPolygon), min_altitude_m?, max_altitude_m?, altitude_datum?, valid_from, valid_to?, change_reason?, owner_org_id, district_id} → 201 {airspace_id, airspace_version_id, version_no:1}
POST /api/v1/airspaces/{id}/versions                    {kind_code, boundary, min/max_altitude_m?, altitude_datum?, valid_from, valid_to?, change_reason, expected_version} → 201；接替：把当前开放版本 valid_to=新 valid_from；新 valid_from ≤ 上版 valid_from → 409 VERSION_OVERLAP；valid_to ≤ valid_from → 400 INVALID_VALIDITY
GET  /api/v1/airspaces/{id}/versions/{a}/diff/{b}       {fields:[{field, from, to}], geometry:{changed, area_delta_m2?, availability AVAILABLE|UNAVAILABLE}}（H2 下几何 UNAVAILABLE）
POST /api/v1/airspaces/import-batches                   {geojson(FeatureCollection ≤2 MB, ≤200 features), defaults{kind_code?, altitude_datum?, valid_from?}, owner_org_id, district_id} → 201 {batch_id, status:STAGED, items[{seq, name, kind_code, issues[], accepted}]}
GET  /api/v1/airspaces/import-batches/{id}
POST /api/v1/airspaces/import-batches/{id}/confirm      {expected_version} → 200 {created_airspaces, created_versions}；只对 accepted 项建空域/版本；并发 confirm 一成一 409：批次已被另一请求决定 → `IMPORT_ALREADY_DECIDED`；客户端携带的 `expected_version` 已过期 → `VERSION_CONFLICT`（决策 9-23）
POST /api/v1/airspaces/import-batches/{id}/discard      {expected_version} → 200

# 空间安全风险（读：risk:read；评估：risk:evaluate）
GET  /api/v1/risks?risk_type=SPACE_OBJECT&object_subtype=&page&size    既有接口加过滤；RiskDto 追加可空 space_fact{subtype_code, subtype_name, rule_version_id, rule_set_version_no, corridor_relation, distance_to_route_m, altitude_band, object_count, trend}
GET  /api/v1/risks/{id}/space-fact                       无则 404 SPACE_FACT_NOT_FOUND；响应体（v1.1，审查第 5 轮补）：{risk_id, subtype_code, subtype_name, rule_version_id, rule_set_version_id, rule_set_version_no, distance_to_route_m?, corridor_relation, altitude_band, altitude_datum, object_count?, trend?, unknown_reasons[], longitude?, latitude?, target_altitude_raw?, window_from, window_to}——坐标为顶层扁平字段（决策 9-18），没有可信坐标时省略，页面不画点
GET  /api/v1/space-risks/summary?from&to&owner_org_id&district_id       {by_subtype[], by_severity[], by_state[], by_altitude_band[], trend_buckets[{from,to,count}], routes_involved, rule_version:{rule_set_code, version_no, param_status}}
GET  /api/v1/space-object-subtypes                       [{subtype_code, display_name, aliases[], enabled}]
POST /api/v1/rule-evaluations                            {rule_code C04|C05, window_from, window_to} → 202 {run_id, status}；H2/无 PostGIS → status UNAVAILABLE（不是 500）
GET  /api/v1/rule-evaluations?rule_code&page&size

# 机场（airport:read / airport:manage；只增）
GET/POST /api/v1/airports ；GET /api/v1/airports/{id}（含 runways/procedure_routes/protected_targets/notification_targets）
POST /api/v1/airports/{id}/runways | /procedure-routes | /protected-targets | /notification-targets

# 飞行计划（flight:read；授权登记 flight:authorize）
GET  /api/v1/flight-plans/{id}/actuals                   {match:{availability, plan_match_code, evaluation_id, evaluated_at, hit_details_c01[]}, altitude_relation:{availability, relation ABOVE|WITHIN|BELOW|UNDETERMINED, datum}, latest_risks:{availability, items[]}, legality:{availability, legal_status, evaluation_id}, authorizations:{availability, items[]}}；无权限的段 availability=FORBIDDEN 且不带数量
GET/POST /api/v1/flight-plans/{id}/authorizations       POST {document_no, issuer, granted_from, granted_to, scope_note?} → 201；同 plan 同 document_no → 409 AUTHORIZATION_EXISTS
```

新错误码：`VERSION_OVERLAP / INVALID_VALIDITY / INVALID_GEOJSON / IMPORT_TOO_LARGE / IMPORT_BATCH_NOT_FOUND / IMPORT_ALREADY_DECIDED / SPACE_FACT_NOT_FOUND / AIRPORT_NOT_FOUND / AUTHORIZATION_EXISTS`。领导已补 `GlobalExceptionHandler.module()`（`/airports`→airport；`/space-risks|/space-object-subtypes|/rule-evaluations`→risk；`/airspace*`→airspace；`/flight-plans`→flights）与 `AuditLabels`。

## C04 决策表（DEMO 参数，`rule_param` 规则集 `SPACE-RISK-DEMO` v1）

| 参数 | 值 | 单位 |
| --- | --- | --- |
| `C04.corridor_near_m` | 300 | m |
| `C04.climb_band_agl_m` | 150 | m |
| `C04.approach_band_agl_m` | 300 | m |
| `C04.flock_count_threshold` | 20 | 只 |
| `C04.trend_window_min` | 30 | min |
| `C04.plan_window_pad_min` | 15 | min |
| `C05.procedure_buffer_m` | 500 | m |
| `C05.protected_target_pad_m` | 200 | m |

判定：走廊内（点到活动计划航线走廊 ≤ 半宽）+ 同基准高度在带内 + 活动计划 → HIGH；走廊内但高度未知/带外 → MEDIUM；近航线（≤ `corridor_near_m`）→ MEDIUM；无活动计划 → 不生成风险，只计 `targets_seen`；`object_count ≥ flock_count_threshold` 或趋势上升 → 上调一级（上限 CRITICAL）。AGL/AMSL 不互比，基准缺失 → `altitude_band=UNKNOWN`。`source_risk_id = C04:<rule_version_id>:<plan_id>:<target_id>:<window_from>` 幂等；目标 ID 先经 `target_current_alias` 解析到存活目标。C05：进近/离场线缓冲 + 保护目标半径命中 → `affected_area=AIRPORT_ZONE`；无计划时只写 `rule_evaluation_run` 统计并返回 `PLAN_REQUIRED` 消息。定时 `SpaceRiskEvaluationJob`（`app.rule-engine.c04.enabled` 缺省 false，只在 PostGIS 上执行；H2 返回 UNAVAILABLE）。

## 前端

- `#/airspace` → `pages/airspace/AirspacePage.vue`（E1）：列表（种类/生效期/归属筛选）+ 详情（版本时间线、字段差、只读航线）+ 地图预览（复用 `FlightsPage.renderRouteMap` 的 `draw` 覆写画 MultiPolygon）+ 新建/新版本/导入三个 `openFormModal`（9-12）。
- `#/risk` → `pages/spacerisk/SpaceRiskPage.vue`（E2）：按 legacy `risk.js` 的 DOM 恢复（6 KPI：近 7 天异物事件/高风险/中风险/鸟类事件/待核验/涉及航线；三栏 地图 0.82 / 列表 1.7 / 详情 30%；列表列 编号/目标/细类/来源/区域·高度/最近航线/等级/状态；详情含 `C04 v1 · DEMO` 徽标；事件/通报页签；核验复用 `openRiskVerification`，通报复用 `handoffApi`，驱鸟处置按钮禁用并标"尚未接入"）。
- `FlightsPage.vue`（E1，9.2）：**只**把既有占位区块"计划与实际对照 / 本航线风险 / 合法性"接上 `GET /flight-plans/{id}/actuals`，并在"计划信息"下追加"外部授权登记"区块（列表 + `openFormModal` 登记）；不改页签、不改其它区块。
- 文案与布局：`writing-user-readable-ui-text` 技能 + `b2d1359` 布局规则；`services/*Api.js` 只加方法。

## 文件归属与顺序

见计划文件"阶段 9 执行计划"分工表与各简报。顺序 9.0 → 9.1‖9.3‖9.4 → 9.2 → 9.5；E1 先落 061 并在 `progress.md` 追加 "9.1 DDL 061 landed"。门槛同阶段 8：列表计数按自身归属过滤；JSON/几何非 INSERT 表达式先过 `Stage9PostgresTest`；H2 米制距离在 Java 侧算。
