# 阶段 5 工作台与业务交接接口契约

> 最新用户确认：提交成功为已通知，确认回执后为已回执；此前通知口径以 [风险通知状态口径](../风险通知状态口径.md) 为准。

> 2026-09-10 MQTT 修复补充：仅提交交接仍不表示通知完成；渠道同时返回有效 DELIVERED、ACKNOWLEDGED 及对应时间且没有阻断原因时，在同一事务内将待通知风险推进 NOTIFIED、版本加一并写审计。材料保留提交时版本。模拟渠道不得推进 live 风险；缺回执不推进；重复请求复用原记录。详见 [修复复测结果](../MQTT问题修复与复测结果-20260910.md)。

> 状态：领导冻结稿（2026-09-05）。执行者按本文实现；需要改动契约时先向领导提出，不得各自扩展。

## 交付边界

工作台只聚合无人机事件、飞行风险和设备异常三类源事项，不拥有第二套状态，所有动作委托源模块接口。交接把已核验风险的结构化材料持久化给逻辑接收方；提交成功只表示材料入库（`PENDING_DELIVERY`），不表示已发送、已送达或处罚办结。本期不接真实通知渠道、不接反制/干扰完成事实、不建设处罚立案，普通已核实无人机的 `UAV_PUNISHMENT` 交接一律阻断。

## 通用约定

沿用阶段 4 契约：`{ok,data}` / `{ok:false,error:{code,message}}`；snake_case；字符串 ID；epoch 毫秒；`page,size → items/page/size/total`（默认 1/20，最大 100；`page_size`、未知、重复参数 400）；所有 GET/POST 先完成全部动作鉴权再解析 query/path/body；`ASSIGNED` 匹配同一有效 `(owner_org_id,district_id)` 元组；`ALL` 仍要求归属目录存在且启用；越权对象 404；缺动作权限 403。时间过滤半开区间 `[from,to)`，`from<to`。

## 权限

| 接口 | 权限 |
| --- | --- |
| 工作台列表/详情 | `workbench:read`，且每类源分别还需：`UAV_EVENT`→`alarm:read`；`RISK`→`risk:read`；`DEVICE_INCIDENT`→`device:read` **加** 既有运维菜单读取许可 `monitoring.read`（`AccessService.requireBusinessData` 语义） |
| 详情时间线中的交接记录 | 额外 `handoff:read`；缺失时省略该段并给可用性标记 |
| 接收方目录 `GET /handoff-recipients` | `handoff:read` 或 `handoff:create` 任一（发起交接的角色不必同时拥有读权限；两者皆无 403） |
| 交接列表/详情/投递记录 | `handoff:read` |
| 创建交接 | 源对象读权限（`RISK`→`risk:read`）+ `handoff:create` |

没有某类源读权限时，工作台该类返回 `source_availability[kind]="FORBIDDEN"`、`counts_by_kind[kind]=null`，列表不含该类；不泄露其总数。设备映射表为空时 `DEVICE_INCIDENT` 为 `UNCONFIGURED`，同样 `null`，不能解释成“无异常”。

## 源事项身份

`kind ∈ {UAV_EVENT, RISK, DEVICE_INCIDENT}`；`source_id` 分别为 `event_id` / `risk_id` / `incident_id`，绝不是 `target_id`、`alarm_id` 或 `device_id`。不同 kind 下相同 `source_id` 是两条不同事项。同目标多条无人机事件、同设备多次异常都各自保留。

## 设备业务范围（领导提供）

表 `device_business_scope(ops_device_id PK→ops_device, owner_org_id→app_org, district_id→app_district)`（迁移 030）。类 `com.uav.lowaltitude.modules.device.infrastructure.DeviceBusinessScopeRepository`：

```java
boolean configured();                                   // 映射表是否有任何行；false → UNCONFIGURED
String scopedIncidentSql(AccessDecision access, Map<String,Object> params); // 派生表 SQL（不含外层括号）
long countIncidents(AccessDecision access);
List<ScopedIncident> listIncidents(AccessDecision access, int offset, int size);
ScopedIncident findIncident(String incidentId, AccessDecision access);
record ScopedIncident(String incidentId, String deviceId, String deviceNo, String deviceName, String incidentType,
        String severity, String stage, long detectedAt, Long closedAt, String reason, boolean simulated,
        String sourceMode, String ownerOrgId, String districtId)
```

`scopedIncidentSql` 输出的列：`incident_id, device_id, device_no, device_name, incident_type, severity, stage, detected_at(毫秒 BIGINT), closed_at, reason, simulated, source_mode, owner_org_id, district_id`；它已经包含映射存在、组织/区域启用及（`ASSIGNED` 时）用户精确元组谓词，并向 `params` 写入保留参数名 `device_scope_user_id`。工作台把它作为 `UNION ALL` 的一支使用，过滤必须发生在数据库分页与 count 之前。

## 工作台

```text
GET /api/v1/workbench/items
GET /api/v1/workbench/items/{kind}/{source_id}
```

过滤：`kind,state,severity,occurred_from,occurred_to,owner_org_id,district_id,source_mode`。`state` 必须与 `kind` 成对出现，否则 400 `STATE_REQUIRES_KIND`。固定排序 `severity_rank DESC, received_at DESC, kind ASC, source_id DESC`；`severity_rank`：`CRITICAL=4, HIGH=3, MEDIUM=2, LOW=1`，其他 0。

列表响应 `data`：`{items,total,page,size,counts_by_kind,source_availability,as_of}`。`counts_by_kind` 与 `items/total` 使用同一筛选和同一查询快照；`as_of` 由 `AppClock` 取自本次读取。

`items[]` 固定字段：`kind,source_id,state,severity,received_at,title,summary,allowed_actions,blocked_reason,source_mode,links`；可省略：`updated_at`、`version`（设备异常没有版本，省略）、`occurred_at`（未知省略）。各类映射：

| kind | state | received_at | updated_at | version | allowed_actions | blocked_reason |
| --- | --- | --- | --- | --- | --- | --- |
| UAV_EVENT | `uav_event.state_code` | `alarm.received_at` | `uav_event.updated_at` | `uav_event.version` | 源 `allowed_actions`（`VERIFY`） | `CONFIRMED` 时 `COUNTERMEASURE_NOT_CONNECTED`；否则 null |
| RISK | `flight_risk.state` | `flight_risk.received_at` | `updated_at` | `version` | 源 `VERIFY`；`PENDING_NOTIFICATION` 且有 `handoff:create` 时 `NOTIFY` | 无接收方时 `RECIPIENT_NOT_CONFIGURED` |
| DEVICE_INCIDENT | `device_incident.stage` | `detected_at` | `closed_at`（有则） | 省略 | `PENDING`+`monitoring.op`→`REBOOT`；`PENDING_VERIFICATION`+`monitoring.op`→`VERIFY_RECOVERY`；其余 `[]` | `PROCESSING` 时 `WAITING_RECEIPT`；否则 null。原 `DEVICE_RECOVERY_NOT_CONNECTED` 已被设备异常写接口取代 |

`title/summary` 只用安全字段（类型、等级、设备编号/名称、风险原因文案），不含原始 payload、凭据或无权关联 ID。`links` 只含经授权的现有 hash 路由（`#/alarms?...`、`#/risk?...`、`#/monitor?...`），不允许外部 URL。`source_mode` 取源表值；设备异常取 `ops_device.source_mode`。

详情响应：`{item, timeline, availability}`。`timeline` 只读有范围的核实历史（UAV/RISK，`version ASC`）、交接记录（RISK，需 `handoff:read`；缺权限则 `availability.handoffs="FORBIDDEN"` 并省略）、设备异常事实；禁止查询全局审计表。

工作台不提供任何写接口；核实走 `/uav-events/{id}/verifications`、`/risks/{id}/verifications`，通知走 `/handoffs`，设备异常重启/恢复校验走 `/device-incidents/{id}/reboot` 与 `/device-incidents/{id}/recovery-checks`。

## 交接

```text
GET  /api/v1/handoff-recipients?handoff_type=RISK_NOTICE
POST /api/v1/handoffs
GET  /api/v1/handoffs
GET  /api/v1/handoffs/{handoff_id}
GET  /api/v1/handoffs/{handoff_id}/deliveries
```

POST 头：`Idempotency-Key`（8–128）。body 只允许 `source_kind,source_id,handoff_type,recipient_id,expected_version`；夹带 `delivery_status/receipt_status/delivered_at` 等任何其他字段 400 `UNKNOWN_FIELD`（沿用阶段 4 严格解析：坏 JSON、重复键、类型错误同样 400）。

规则：

- `source_kind=RISK` 且源风险当前状态 `PENDING_NOTIFICATION`，否则 409 `INVALID_TRANSITION`；`expected_version` 不等于当前版本 409 `VERSION_CONFLICT`。创建前锁定源风险。**回执确认"已驱离"时把风险推进到"已通知"并把版本 +1**（决策 18-14：闭环判据是回执已驱离，不是送到了；停在"待通知"会让值班台一直把它当未办事项）——只对 `RISK_NOTICE` 且回执为 `DISPERSED` 生效，`NOT_DISPERSED` 与未接通渠道都保持"待通知"。推进与交接落库在同一事务、同一把源风险的锁下；这一步不新记审计动作，结果写在该笔交接的审计详情里（`risk_state=NOTIFIED`），否则查审计的人会看到风险状态变了却找不到任何一条记录说明为什么。
- `handoff_type=UAV_PUNISHMENT`（任何 `source_kind`）本期一律 409 `HANDOFF_PREREQUISITE_UNAVAILABLE`。其余未知 `source_kind/handoff_type` 400。
- 接收方从 `handoff_recipient` 中 `enabled=true` 且 `handoff_type` 匹配的行选择；目录为空 409 `RECIPIENT_NOT_CONFIGURED`；给定 `recipient_id` 不在可用目录 404 `RECIPIENT_NOT_FOUND`。生产不自动插入接收方。
- `recipient_id` 可缺省（决策 18-14）：不传时依次找：该 `handoff_type` 下 `is_default=true` 且 `enabled=true` 的接收方 → 该类型**恰好只有一个**启用接收方时用它（决策 18-16：只有一个的时候没有可选的余地，再要求值班员显式指定就是让他把唯一的答案抄一遍）→ 零个或多个且都没标默认，才 400 `RECIPIENT_REQUIRED`。缺省只是"由服务端定收件人"，其余校验与显式传值完全一致，落库与响应里的 `recipient_id` 都是实际生效的那个。幂等键按"客户端这次发的请求"计算：不传接收方与显式传了默认接收方是两个不同的键。
- 逻辑唯一 `(source_kind,source_id,handoff_type,recipient_id)` 由数据库唯一约束保证；命中 409 `HANDOFF_ALREADY_EXISTS`，错误体只有 code/message。
- 事务：鉴权 → 锁源对象 → claim 幂等键 → 版本/状态/接收方检查 → 插入 `handoff` + `handoff_material_snapshot` + 首条 `handoff_delivery(attempt_no=1, delivery_status=PENDING_DELIVERY, receipt_status=NOT_EXPECTED, blocked_reason=CHANNEL_NOT_CONNECTED)` + 成功审计 → 提交。任何失败整体回滚，失败审计走事务外统一路径。
- 快照白名单：风险 `risk_id,source_risk_id,risk_type,severity,state,reason_code,reason_text,occurred_at,received_at,version`、核实历史（`conclusion,note,resulting_state,version,created_at,actor_id`）、当时可见的关联引用及版本（`plan_id,route_version_id,assessment_id,target_id,track_id`）。没有文件就没有文件名/哈希/下载链接。`schema_version=1`。
- 读取快照时重新检查交接归属与当前源对象/关联对象权限，不可见的关联引用从响应删除，不提示“有 N 个无权对象”。

POST 成功 201：`{handoff_id,source_kind,source_id,handoff_type,recipient_id,source_version,delivery_status:"PENDING_DELIVERY",receipt_status:"NOT_EXPECTED",blocked_reason:"CHANNEL_NOT_CONNECTED",created_at}`。

回执结果：`handoff.receipt_result` 记录上级回执带回的处置结果，创建响应、**列表**与详情都透出 `receipt_result`（通报记录是列表，详情有而列表没有，页面上那一列就永远空着）。取值 `DISPERSED/NOT_DISPERSED`；只有风险通知类的交接才有结果，处罚交接与尚未送达的交接为空，空值字段按本接口惯例整条不出现。该字段由渠道回执写入，不开放外部写接口。

列表过滤：`source_kind,source_id,delivery_status,created_from,created_to,source_mode`；排序 `created_at DESC, handoff_id DESC`。`delivery_status` 指最新一次尝试。详情增加 `material`（白名单快照）与 `latest_delivery`；投递记录 `attempt_no ASC`。交接归属 `(owner_org_id,district_id)` 复制自源风险，列表/详情/count 用同一范围谓词。

枚举：`delivery_status: PENDING_DELIVERY/SUBMITTED/DELIVERED/FAILED`；`receipt_status: NOT_EXPECTED/PENDING/ACKNOWLEDGED/TIMEOUT`。本期生产写入只产生 `PENDING_DELIVERY/NOT_EXPECTED`；local/test 的历史样例必须 `source_mode=mock` 且只读。不开放任何送达/回执写接口，不放入设备 Outbox，不新增发送 Worker。

## 迁移与文件归属

| 编号 | 所有者 | 内容 |
| --- | --- | --- |
| `V202609050030__stage5_permissions_and_device_scope.sql` | 领导 | 三个动作目录 + `device_business_scope` |
| `V202609050031__handoff_submission.sql` | 执行者 2 | `handoff_recipient,handoff,handoff_material_snapshot,handoff_delivery` |

执行者 1：`modules/workbench/**`、`T/modules/workbench/**`、`F/services/workbenchApi.js`、`F/services/workbenchEvents.js`、`F/pages/WorkbenchPage.vue`。执行者 2：`modules/handoff/**`、`T/modules/handoff/**`、迁移 031、`integration/mock/LocalStage5HandoffSeeder.java`(+Test)、`F/services/handoffApi.js`、`F/pages/PunishPage.vue`。`FlightsPage.vue` 的通知按钮接线在阶段 4 页面恢复完成后由领导另行分配。公共文件（`PermissionCode.java`、鉴权/审计/幂等/异常组件、`apiClient.js`、`accessControl.js`、`navModel.js`、`registry.js`、`HeaderBar.vue`）只有领导可改。

## 稳定错误码

`FORBIDDEN(403)`、`NOT_FOUND(404)`、`STATE_REQUIRES_KIND(400)`、`INVALID_KIND(400)`、`UNKNOWN_FIELD(400)`、`INVALID_REQUEST(400)`、`RECIPIENT_NOT_FOUND(404)`、`RECIPIENT_REQUIRED(400)`、`RECIPIENT_NOT_CONFIGURED(409)`、`HANDOFF_PREREQUISITE_UNAVAILABLE(409)`、`HANDOFF_ALREADY_EXISTS(409)`、`INVALID_TRANSITION(409)`、`VERSION_CONFLICT(409)`、`IDEMPOTENCY_REPLAY(409)`、`IDEMPOTENCY_KEY_REUSED(409)`。阶段 4 已有错误码保持不变。

## 尚未接入

真实通知渠道与回执、无人机反制/干扰完成事实、处罚立案/罚款/结案、真实运维设备归属资料、正式证据文件与保管。设备异常重启/恢复校验已接到源模块（仅 mock 适配器能真正重启；live 协议未声明重启能力时 409）。以上未接入项必须显示为“未接入/禁用”并说明原因，不得显示为已完成。
