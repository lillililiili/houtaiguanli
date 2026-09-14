# 处罚案件 API 契约（阶段 14，v1.3）

> 状态：v1.3（2026-09-08，E2 联调后：违法事由十码、`title`、`current_discretion/open_leads` 形状、裁量请求体五字段、空段语义、`REVIEW` 对承办人不给）；v1.2（2026-09-08，审查第 2 轮后：提交顺序 14-21 修订、`source_mode` 取告警、`penalty_types` JSON 数组 + `PENALTY_TYPE_NOT_ALLOWED`、`availability.evidence` 以 material 为前提、`completed_at` 取事件流）；v1.1 为第 1 轮后（`allowed_actions` 十项、复核审计带结论）。依据：计划 `docs/superpowers/plans/2026-09-08-collaborator-b-stage-14-punishment-cases.md`、决策 14-1…14-18、阶段 5 交接契约、阶段 13 处置契约。通用约定同前：`{ok,data}` 包络、snake_case、字符串 ID、epoch ms、`page,size→items/page/size/total`、先鉴权再解析（403→400→409）、精确 `(owner_org_id,district_id)` 元组、越权 404、`Idempotency-Key` + `expected_version`、成功审计同事务、失败审计事务外、未知不补默认值。

## 1. 处罚交接材料包 v2（`POST /handoffs` 的 UAV_EVENT 分支）

请求体不变：`{source_kind:"UAV_EVENT", source_id, handoff_type:"UAV_PUNISHMENT", recipient_id, expected_version}`（`expected_version` = `uav_event.version`）。权限 `handoff:create` + `alarm:read`。顺序（14-21 修订）：`handoff:create` → 宽松预解析只取 `source_kind` → 按来源种类要读权限（403）→ 严格解析（400）→ 前提（存在 COMPLETED 授权，409 `HANDOFF_PREREQUISITE_UNAVAILABLE`）→ 组合校验 → 锁事件（不可见 404）→ 幂等 → 版本（409 `VERSION_CONFLICT`）→ 事件须 `CONFIRMED`（409 `INVALID_TRANSITION`）→ 接收方（409 `RECIPIENT_NOT_CONFIGURED` / 404 `RECIPIENT_NOT_FOUND`）→ 逻辑唯一（409 `HANDOFF_ALREADY_EXISTS`）→ 写 `handoff(event_id=source_id)` + 快照 v2 + 首条投递（`PENDING_DELIVERY / NOT_EXPECTED / CHANNEL_NOT_CONNECTED`）+ 审计 `handoff_created`。

快照 v2（`handoff_material_snapshot.schema_version=2`）：

| 段 | 字段 |
| --- | --- |
| `event` | `event_id, alarm_id, source_alarm_id, alarm_type, severity, occurred_at, received_at, state, target_id, owner_org_id, district_id, source_mode, version` |
| `verifications[]` | `conclusion, note, resulting_state, version, created_at, actor_id, actor_name`；查出零条时**省略键**（CONFIRMED 事件不可能没有核实记录，零条只可能是没取到，14-28） |
| `disposals[]` | `authorization_id, authorization_no, action_type, channel, device_id, status, requested_by_name, approved_by_name, valid_from, valid_until, result_code, result_detail, completed_at`（该事件全部终态授权；COMPLETED 至少一条；零条时省略键，14-28） |
| `evidence[]` | `evidence_id, evidence_no, kind_code, sha256, captured_at, status`（`evidence_link.subject_kind='EVENT'`，只读 A 的表；提交人缺 `evidence:read` 时整段省略并记 `evidence_omitted=true`；有权限查出零条保留 `[]`＝『移送时确无关联证据』） |
| `references` | `target_id, track_id`（全空则整块省略） |

`GET /handoffs/{id}` 的 `material` 按 `schema_version` 返回 v1 或 v2 形状；`availability.material`：UAV_EVENT 来源缺 `alarm:read` → `FORBIDDEN`，事件不在范围 → `SOURCE_NOT_VISIBLE`；新增 `availability.evidence ∈ AVAILABLE|FORBIDDEN|SOURCE_NOT_VISIBLE|OMITTED_AT_SUBMISSION`（以 `availability.material` 为前提，14-25）。

## 2. 案件域

### 2.1 资源

`punishment_case`：`case_id, case_no (CASE-YYYYMMDD-NNNN), event_id (UNIQUE), handoff_id, status ∈ FILED|INVESTIGATING|UNDER_REVIEW|DECIDED|CLOSED|WITHDRAWN, party_type ∈ PERSON|ORG|UNKNOWN, party_name?, officer_id?, officer_name?, primary_violation_code?, filed_by, filed_by_name, filed_at, decided_at?, closed_at?, close_note?, withdraw_reason?, owner_org_id, district_id, source_mode, version, created_at, updated_at`；只读派生：`current_discretion`（单个对象：最新 CONFIRMED 或 DRAFT）、`issued_document_count`、`open_leads[]`（未解决线索；没有 `discretions[]/leads[]/reviews[]` 数组，复核历史从事件流 `REVIEW_CONCLUDED` 取）、`allowed_actions ⊆ ASSIGN|ADD_LEAD|RESOLVE_LEAD|DRAFT_DISCRETION|CONFIRM_DISCRETION|REVIEW|ISSUE_DOCUMENT|REVOKE_DOCUMENT|CLOSE|WITHDRAW`（十项，与 §2.2 的写接口一一对应：`ASSIGN→/assign`、`ADD_LEAD→/leads`、`RESOLVE_LEAD→/leads/{id}/resolve`、`DRAFT_DISCRETION→/discretions`、`CONFIRM_DISCRETION→/discretions/{id}/confirm`（确认即提请复核，没有单独的"提请复核"动作）、`REVIEW→/reviews`、`ISSUE_DOCUMENT→/decision-documents`、`REVOKE_DOCUMENT→/decision-documents/{id}/revoke`、`CLOSE→/close`、`WITHDRAW→/withdraw`；按状态与权限裁剪，且 `REVIEW` 对承办人本人不给（14-32）；语义是『当前调用者现在能做的』，前端只按此表启用按钮，不预判规则）。

`punishment_case_event`（只增）：`event_id, case_id, event_kind ∈ FILE|ASSIGN|LEAD_ADDED|LEAD_RESOLVED|DISCRETION_DRAFTED|DISCRETION_CONFIRMED|DOCUMENT_ISSUED|DOCUMENT_REVOKED|REVIEW_REQUESTED|REVIEW_CONCLUDED|CLOSE|WITHDRAW, actor_id, actor_name, note, snapshot, occurred_at`。

`penalty_rule`（DEMO）：`rule_code, violation_code ∈ NO_AUTHORIZATION|PROHIBITED_AIRSPACE_OVERLAP|AIRSPACE_ALTITUDE_EXCEEDED|PLAN_ALTITUDE_EXCEEDED|TIME_WINDOW_EXCEEDED|ROUTE_DEVIATION|BVLOS_EXCEEDED|NIGHT_FLIGHT|IDENTITY_MISMATCH|OTHER（阶段 7 原因码，14-8）, title（法定表述，页面优先显示）, legal_basis（只写条例名 + "条款号待法制岗核定"，14-20）, fine_min, fine_max, fine_reference?, penalty_types[]（JSON 数组，14-24）, schema_status=DEMO, enabled`。

`penalty_discretion`：`discretion_id, case_id, version_no, status ∈ DRAFT|CONFIRMED|SUPERSEDED, violation_code, rule_code, penalty_type ∈ WARNING|FINE|WARNING_AND_FINE, fine_amount (分, ≥0), factors[] {code ∈ AGGRAVATING|MITIGATING|NONE, text}, basis_text, drafted_by, drafted_at, decided_by?, decided_at?`。

`penalty_decision_document`：`document_id, document_no (<case_no>-DEC-NN), case_id, discretion_id, template_version (demo-v1), status ∈ ISSUED|REVOKED, fields (JSON), rendered_sha256, issued_by, issued_by_name, issued_at, revoked_at?, revoke_reason?`。

`punishment_review`（只增）：`review_id, case_id, reviewer_id, reviewer_name, conclusion ∈ UPHELD|REVISED|INSUFFICIENT, note, missing_leads[] {kind ∈ PARTY_IDENTITY|EVIDENCE|JURISDICTION|FACT|OTHER, description}, created_at`。

### 2.2 接口

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| GET | `/penalty-rules` | `punishment:read` | DEMO 档位表（全部带 `schema_status`） |
| POST | `/punishment-cases` | `punishment:file` + `handoff:read` | `{handoff_id, party_type, party_name?, note?}`；交接须 `UAV_PUNISHMENT` 且可见（404）；一事件一案 409 `CASE_ALREADY_EXISTS`；201 返回案件，事件 `FILE` |
| GET | `/punishment-cases?status&event_id&handoff_id&page&size` | `punishment:read` | 分页，范围元组 |
| GET | `/punishment-cases/{id}` / `/{id}/events` | `punishment:read` | 详情 / 事件流（裸数组） |
| POST | `/{id}/assign` | `punishment:file` | `{officer_id, expected_version}`；`FILED → INVESTIGATING`（已在 INVESTIGATING 也可改承办人）；officer 须是可见的启用用户（404） |
| POST | `/{id}/leads` | `punishment:file` | `{kind, description, expected_version}`；只在 `INVESTIGATING`；事件 `LEAD_ADDED` |
| POST | `/{id}/leads/{leadId}/resolve` | `punishment:file` | `{note, expected_version}`；事件 `LEAD_RESOLVED` |
| POST | `/{id}/discretions` | `punishment:decide` | body 只认 `{rule_code, penalty_type, fine_amount, factors[], basis_text, expected_version}`（`violation_code`/`legal_basis` 由 `rule_code` 决定，传了 400 `UNKNOWN_FIELD`）；新 DRAFT（已有 DRAFT → 覆盖为新版本，旧的 SUPERSEDED）；`fine_amount` 区间校验 400 `FINE_OUT_OF_RANGE`；`penalty_type` 须在规则 `penalty_types` 内（400 `PENALTY_TYPE_NOT_ALLOWED`，14-24）；WARNING 金额须 0（400 `VALIDATION_ERROR`）；只在 `INVESTIGATING` |
| POST | `/{id}/discretions/{did}/confirm` | `punishment:decide` | `{expected_version}`；DRAFT → CONFIRMED，案件 `INVESTIGATING → UNDER_REVIEW`，事件 `DISCRETION_CONFIRMED` + `REVIEW_REQUESTED` |
| POST | `/{id}/reviews` | `punishment:review` | `{conclusion, note, missing_leads?[], expected_version}`；`missing_leads[]` 结构校验先于锁定/版本/状态（400 先于 409），`UPHELD` 携带 `missing_leads` → 400 `VALIDATION_ERROR`『维持原裁量时不能附待补线索』（14-33）；只在 `UNDER_REVIEW`；复核人 ≠ 承办人 409 `REVIEW_SELF_NOT_ALLOWED`；`UPHELD → DECIDED`；`REVISED/INSUFFICIENT → INVESTIGATING`（裁量置 SUPERSEDED，线索挂案） |
| POST | `/{id}/decision-documents` | `punishment:decide` | `{expected_version}`；只在 `DECIDED` 且存在 CONFIRMED 裁量（409 `DISCRETION_NOT_CONFIRMED`）；201 返回文书（含 `rendered_sha256`），事件 `DOCUMENT_ISSUED` |
| GET | `/{id}/decision-documents` | `punishment:read` | 列表 |
| GET | `/decision-documents/{docId}/content` | `punishment:read` | `text/plain; charset=UTF-8`，`Content-Disposition: attachment; filename="<document_no>.txt"`；首行水印 |
| POST | `/decision-documents/{docId}/revoke` | `punishment:decide` | `{reason, expected_version}`；ISSUED → REVOKED |
| POST | `/{id}/close` | `punishment:close` | `{note?, expected_version}`；须 `DECIDED` + ≥1 ISSUED 文书（409 `DECISION_DOCUMENT_REQUIRED`） |
| POST | `/{id}/withdraw` | `punishment:close` | `{reason, expected_version}`；只在 `FILED|INVESTIGATING` |

### 2.3 错误码

`VALIDATION_ERROR`(400) / `UNKNOWN_FIELD`(400) / `FINE_OUT_OF_RANGE`(400) / `PENALTY_TYPE_NOT_ALLOWED`(400) / `NOT_FOUND`(404，含越权) / `CASE_ALREADY_EXISTS`(409) / `INVALID_TRANSITION`(409) / `DISCRETION_NOT_CONFIRMED`(409) / `REVIEW_SELF_NOT_ALLOWED`(409) / `DECISION_DOCUMENT_REQUIRED`(409) / `VERSION_CONFLICT`(409) / `HANDOFF_PREREQUISITE_UNAVAILABLE`(409) / `HANDOFF_ALREADY_EXISTS`(409)。

## 3. 审计

模块 `punishment`（"处罚案件"）；动作 `punishment_case_filed / assigned / lead_added / lead_resolved / discretion_drafted / discretion_confirmed / reviewed / decision_issued / decision_revoked / case_closed / case_withdrawn`；`handoff_created` 沿用。 `punishment_reviewed` 的审计 `detail` 必须带 `conclusion` 与 `resulting_status`（审计流要能说清"过了还是打回了"）；所有处罚审计 `detail` 不得带当事人名称（14-12）。路径前缀 `/punishment-cases`、`/penalty-rules`、`/decision-documents` → 模块 `punishment`。

## 4. 前端

处罚页五块：案件管理（按所选交接展示案件或"立案"按钮；案件列表 + 详情 + 事件流）、罚款与裁量（DEMO 档位 + 裁量表单 + 确认）、决定书（生成 / 列表 / 预览文本 / 下载 / 作废）、证据链（材料快照 v2 的 `evidence` 段只读 + `availability.evidence` 文案）、定性复核与待补线索（复核表单 + 线索列表 + 已解决）。告警页"通知处罚部门"→"提交处罚交接"。所有 DEMO 标注与"未接入"文案按决策 14-8/14-10/14-15。

## 5. 种子

`LocalStage14PunishmentSeeder @Order(110)`：接收方 `seed-stage14-recipient-punish`（UAV_PUNISHMENT）；交接 `seed-stage14-handoff-punish`（事件 `seed-stage13-event-confirmed`，快照 v2）；案件 `CASE-20260908-9001`（INVESTIGATING，承办人 admin1）+ DRAFT 裁量 + 一条未解决线索。

## 6. 提请协作者 A

`evidence_link.subject_kind` 增 `CASE` 与 `HANDOFF`（本阶段只读引用 EVENT 主体）；决定书是否入 `evidence_file(kind_code=PENALTY_DOCUMENT)` 由 A 的上传接口决定，本阶段不写 A 的表。

## 7. 待确认（客户）

罚则金额档位与处罚主体（Q 罚则）；外部处罚系统与通知渠道（Q4）；证据保留（Q7）。全部 DEMO。
