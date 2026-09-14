# 处置授权 API 契约（阶段 13，v1.1）

2026-09-10 修订：UAV_EVENT 授权的 `source_mode` 继承源告警，TARGET 授权继承目标，均不硬编码 live。页面新增独立“处置与处罚 → 处置授权”队列，先审批、执行、核对结果，再提交处罚交接。

> 状态：v1.3（2026-09-09，四通道原生 TCP 设置：`COUNTERMEASURE`→`SET_MASK 0x0F`，`JAMMING`→`0x0D`，停止=全关 `0x00`；DECOY/DISPERSAL 走四通道 400。v1.2 为 A 开通 `dec`/`ifr`/`bsc`；v1.1 为 2026-09-08 验收修订；v1.0 冻结稿 2026-09-07）。依据：计划 `docs/superpowers/plans/2026-09-07-collaborator-b-stage-13-disposal-authorization.md`、决策 13-1…13-9、协作者 A 的 P5 与四通道 REST。通用约定同前：`{ok,data}` 包络、snake_case、字符串 ID、epoch ms、`page,size→items/page/size/total`、先鉴权再解析、精确 `(owner_org_id,district_id)` 元组、越权 404、`Idempotency-Key` + `expected_version`。

## 1. 资源

`disposal_authorization`
| 字段 | 说明 |
| --- | --- |
| `authorization_id` | 字符串 ID |
| `authorization_no` | `AUTH-YYYYMMDD-NNNN`，按日递增（13-5） |
| `action_type` | `COUNTERMEASURE`（反制）/ `JAMMING`（信号干扰）/ `DISPERSAL`（驱离）/ `DECOY`（诱骗） |
| `subject_kind` / `subject_id` | `UAV_EVENT` / `TARGET`（13-24：`TARGET` 只允许 `requires_confirmed_event=false` 的动作，即驱离；`subject_id` 落库为经 `target_current_alias` 解析后的存活目标；目标不在范围内 404、无观测/超过 C03 `fresh_seconds` 409 `TARGET_NOT_ACTIVE`，13-31）。`RISK` 不作处置主体：400 `SUBJECT_KIND_NOT_SUPPORTED`。决策 18-14 已定死——风险的流程到"通知上级"为止，回执"已驱离"即闭环，不再进处置授权，不是"待某阶段再议"。主体名单里已无 `RISK`；表 CHECK 的枚举保留不动，因为历史行还挂着它，改约束会让老数据违约 |
| `target_id` | 可空；主体为事件时取事件关联目标 |
| `device_id` / `channel` | 执行设备与通道：`LINGYUN_B`（协议 B 经 A）/ `COUNTERMEASURE_4CH`（四通道网络控制器，经 A 原生 TCP 下发；驱离/诱骗不能走该通道）/ `MANUAL`（人工执行） |
| `status` | `REQUESTED / APPROVED / REJECTED / EXECUTING / COMPLETED / FAILED / STOPPED / EXPIRED / CANCELLED` |
| `requested_by/at`、`approved_by/at`、`decision_note`、`valid_from`、`valid_until` | 审批与时限；`valid_until = approved_at + policy.time_limit_min[action_type]` |
| `execution_command_id` | 关联 A 的 `device_command.command_id`（LINGYUN_B 或 COUNTERMEASURE_4CH） |
| `result_code` / `result_detail` | 完成/失败/停止的结果 |
| `execution_block_reason` | 只读，执行被阻时由最近一条阻塞事件的 `event_kind` 一一对应推导（13-22）：`DEVICE_CAPABILITY`（所选设备不能走该通道，例如 4CH 通道配了雷达）/ `PROTOCOL_NOT_OPENED`（A 未开放该指令码族，厂家未确认；A 返回 **400** `PROTOCOL_UNSUPPORTED`，按错误码而非状态码判定）/ `NOT_BOUND`（设备未登记凌云 MQTT，A 返回 409 `CONTROL_NOT_ENABLED` 于 `enqueue`）/ `DEVICE_OFFLINE`（设备未启用或不在线，A 返回 409 `DEVICE_NOT_OPERABLE`，13-14）；无阻塞为 null（13-12）。A 的 `DEVICE_NOT_FOUND`（404）与指令码/设备类型不匹配（400 `VALIDATION_ERROR`）属调用方错误：原样透出，不记事件、不改授权状态 |
| `device_stop_result` | 只读，由事件推导：`NOT_ATTEMPTED`（未尝试设备停止）/ `EXECUTED`（设备急停已受理，本期凌云 B 不会产生）/ `ALL_OFF_ISSUED`（四通道已下发全关，记 `DEVICE_ALL_OFF_ISSUED`，不是急停）/ `UNAVAILABLE`（协议未提供，记 `DEVICE_STOP_UNAVAILABLE` 事件）/ `NOT_BOUND`（设备未登记凌云 MQTT，可由运维补配置，记 `DEVICE_NOT_BOUND` 事件，13-11）；前端在 STOPPED 旁必须带限定语（13-10） |
| `policy_version`、`owner_org_id`、`district_id`、`source_mode`、`version` | — |

## 2. 接口
| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/disposal-authorizations` | `disposal:request` + 主体读权限 | body `{action_type, subject_kind, subject_id, device_id?, channel, reason}`；策略校验：`requires_confirmed_event` 时事件须 `CONFIRMED`（否则 409 `POLICY_REQUIRES_CONFIRMED_EVENT`）；同主体同类型已有活动授权 → 409 `ACTIVE_AUTHORIZATION_EXISTS` |
| GET | `/disposal-authorizations?subject_kind&subject_id&status&action_type&page&size` | `disposal:read` | 分页 |
| GET | `/disposal-authorizations/{id}` | `disposal:read` | 含 `allowed_actions`（按状态与调用者权限计算：APPROVE/REJECT/EXECUTE/STOP/CANCEL/MANUAL_RESULT） |
| GET | `/disposal-authorizations/{id}/events` | `disposal:read` | 只增事件流 |
| POST | `/{id}/approve` `{expected_version, note?}` | `disposal:approve` | 两人规则：审批人 ≠ 申请人 → 否则 409 `TWO_PERSON_RULE`；REQUESTED 以外 409 `INVALID_TRANSITION` |
| POST | `/{id}/reject` `{expected_version, note}` | `disposal:approve` | — |
| POST | `/{id}/execute` `{expected_version, operation_params?}` | `disposal:execute`（设备通道还需 A 的 `devices.op`，13-9） | 必须 APPROVED 且在时限内（过期 409 `AUTHORIZATION_EXPIRED`）；LINGYUN_B → **调 A 之前**按 A 的公开判据自检四种受阻（13-29，顺序：指令码未开通 → 未绑定 → 未启用/不在线）：`PROTOCOL_NOT_OPENED` 事件 + 409 `DEVICE_CONTROL_UNAVAILABLE`；`DEVICE_NOT_BOUND` 事件 + 409 `DEVICE_NOT_BOUND`；`DEVICE_OFFLINE` 事件 + 409 `DEVICE_OFFLINE`；全部通过才从 `policy.command_map` 取码调 A 的 `enqueue`，受理则 EXECUTING。A 仍可能因请求本身拒绝（参数非法、设备类型与指令码不同族、设备不存在）：原样透出、不记事件、不改状态（13-14）。`COUNTERMEASURE_4CH` → COUNTERMEASURE 全开 `0x0F`、JAMMING 驱离 `0x0D`；设备须是四通道协议且在线，否则 `DEVICE_CAPABILITY`/`DEVICE_OFFLINE`；DECOY/DISPERSAL 选四通道 → 400 `VALIDATION_ERROR`，不发射。受阻时授权保持 APPROVED。A 已开通 `ifr`（60002/60003）/`dec`（50002）/`bsc`（70001）：未绑定或离线时阻塞为 `NOT_BOUND`/`DEVICE_OFFLINE`，绑错类型为 400 `VALIDATION_ERROR`；尚未映射的码族仍 `PROTOCOL_NOT_OPENED`。`MANUAL` → EXECUTING，等 `manual-result` |
| POST | `/{id}/manual-result` `{expected_version, result: SUCCEEDED|FAILED, detail}` | `disposal:execute` | 仅 MANUAL 通道 |
| POST | `/{id}/stop` `{expected_version, note}` | `disposal:stop` | APPROVED/EXECUTING → STOPPED。凌云 B 尝试急停不可用记 `DEVICE_STOP_UNAVAILABLE`（13-4）。四通道尝试 `SET_MASK 0x00` 全关，成功记 `DEVICE_ALL_OFF_ISSUED`（不是急停）；不以凌云 MQTT 绑定判断四通道 |
| POST | `/{id}/cancel` `{expected_version}` | 申请人本人或 `disposal:approve` | REQUESTED/APPROVED → CANCELLED |
| GET | `/disposal-policies` | `disposal:read` | 当前策略与参数（含 DEMO 标记） |

回执同步：`DisposalReceiptSync`（随 A 的 `device_command` 状态）`SUCCEEDED→COMPLETED`、`FAILED|TIMED_OUT→FAILED`；到期任务 `DisposalExpiryJob`（`app.disposal.expiry.enabled`，缺省关）把超过 `valid_until` 的 APPROVED 置 EXPIRED。

## 2.1 错误码汇总
`VALIDATION_ERROR`(400) / `SUBJECT_KIND_NOT_SUPPORTED`(400) / `POLICY_REQUIRES_CONFIRMED_EVENT`(409) / `ACTIVE_AUTHORIZATION_EXISTS`(409) / `TARGET_NOT_ACTIVE`(409) / `TWO_PERSON_RULE`(409) / `INVALID_TRANSITION`(409) / `AUTHORIZATION_EXPIRED`(409) / `DEVICE_CONTROL_UNAVAILABLE`(409) / `DEVICE_NOT_BOUND`(409) / `DEVICE_OFFLINE`(409) / `VERSION_CONFLICT`(409) / `NOT_FOUND`(404，含越权)。前端逐码文案在 `ui/disposalAuthModal.js`（确定失败码集合 + 文案），状态/动作/通道/阻塞原因字典在 `ui/labels.js`；未列出的码走服务端消息兜底。

## 3. 事件（只增）
`event_kind ∈ REQUEST|APPROVE|REJECT|EXECUTE|RECEIPT|STOP|COMPLETE|FAIL|EXPIRE|CANCEL|MANUAL_RESULT|DEVICE_STOP_UNAVAILABLE|DEVICE_CONTROL_UNAVAILABLE|PROTOCOL_NOT_OPENED|DEVICE_NOT_BOUND|DEVICE_OFFLINE|DEVICE_ALL_OFF_ISSUED`（13-11/13-14/13-22：`DEVICE_CONTROL_UNAVAILABLE`=所选设备不能走该通道；`PROTOCOL_NOT_OPENED`=A 未开放码族；`DEVICE_NOT_BOUND` 表示设备未登记凌云 MQTT；`DEVICE_ALL_OFF_ISSUED`=四通道已下发全关），带 `actor_id`、`note`、`snapshot`（状态、时限、命令号）。

## 4. 与其它模块
- `HandoffRules`：`UAV_PUNISHMENT` 前提 = 该 `uav_event` 存在 `COMPLETED` 授权（13-6）。
- 工作台：已核实事件的"联动反制"动作可用（打开申请）；反制完成后自动接信号干扰（13-34）；处罚交接在至少一条授权 `COMPLETED` 且干扰未进行中时作为下一步。
- 处罚页"反制与公安信号干扰授权记录"区块 = 按主体列出授权及事件。
- 告警页 KPI：联动反制 = 今日 `COUNTERMEASURE` 授权数（按状态分）；信号干扰同理。列表「状态」列在 `CONFIRMED` 上按处置进度展示，不改 `uav_event.state_code`。

## 5. 待确认（客户 Q5）
授权条件、审批层级、时限、急停能力、哪些设备可自动执行。确认前策略 `demo-v1` 全部 DEMO，页面标注"演示策略，待业务确认"。
