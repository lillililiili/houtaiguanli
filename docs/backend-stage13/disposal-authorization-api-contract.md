# 处置授权 API 契约（阶段 13，v1.1）

## 2026-10-08 增量：四通道反制设备开着算反制中（新-20，确认书 3-9 / 3-6）

本节修订第 2 节“回执同步”中四通道的部分。凌云 B、`MANUAL` 通道不变。

- 四通道（`COUNTERMEASURE_4CH`）启动指令（COUNTERMEASURE `0x0F`、JAMMING `0x0D`）回 `SUCCEEDED` 只表示设备打开了：授权保持 `EXECUTING`（反制中），记 `RECEIPT`“设备回执：已打开，反制中；满 N 秒系统自动全部关闭，也可以随时急停”，并在 `disposal_device_run` 记下 `on_at`（设备回执时间）与 `off_due_at = on_at + policy.device_run_seconds`。`device_run_seconds` 在策略 `demo-v1` 中为演示值 60，待客户确认；策略缺这一项时报 `POLICY_PARAM_MISSING`，不猜缺省值。
- 反制完成后自动接信号干扰（13-34）改为反制设备打开后立即接续。干扰那条打开后沿用来源反制的 `off_due_at`，两条一起关；来源反制的关闭时刻已过时，干扰一打开就关。
- `DisposalDeviceRunTimer`（`app.disposal.device-run.enabled` 缺省开，`poll-millis` 缺省 5000，`max-off-attempts` 缺省 3）到时以最近一次下发它的人的名义下发全关 `0x00`：转干扰时由仍在执行中的干扰那条下发，来源反制不单独关。下发受理后 `execution_command_id` 改为这条关闭指令，记 `DEVICE_ALL_OFF_ISSUED`“反制已满设定时长，系统自动下发全部关闭”，`snapshot.source=RUN_DURATION_REACHED`。
- 全关回 `SUCCEEDED` 才 `EXECUTING→COMPLETED`（`RECEIPT`“设备回执：已全部关闭”+ `COMPLETE`）；来源反制随干扰一起完成（`RECEIPT`“设备回执：已全部关闭（随信号干扰 AUTH-… 一起关闭）”+ `COMPLETE`），然后照常自动移送处罚。处罚交接、“已反制”统计因此比以前晚一个运行时长。
- 全关失败、超时或下发被拒：授权保持 `EXECUTING`，下一轮再试；试满 `max-off-attempts` 次仍不成功就不再重试，记 `RECEIPT`“……设备可能还开着，请按急停或到现场关闭设备”。设备停没停只由人按急停或到现场确认，系统不替人认定。设备已不是在线的四通道设备、找不到下发人账号时同样不再重试并记这一条。
- 反制中照常可以 `POST /{id}/stop` 或事件急停；已 `STOPPED` 的授权不再自动关闭。锁序与急停相同：事件→授权→设备→指令。
- 同一台四通道设备正开着另一条授权（自己的来源反制除外）时执行被拒：记 `DEVICE_BUSY` 事件，授权保持 `APPROVED`，409 `DEVICE_BUSY`“这台反制设备正在执行另一条反制，本次没有下发；等那条反制到时自动关闭或急停后再执行”。
- 不新增事件种类、不改接口字段。迁移：`V202610089002__disposal_device_run.sql`（新表 `disposal_device_run`，策略 `demo-v1` 参数加 `device_run_seconds`）。代码生效需要迁移和后端重启。

## 2026-09-17 增量：直接反制权限

本节替代旧稿中“所有新反制一律逐次审批”的描述。普通申请仍走原来的申请、异人审批和执行流程。

- 角色管理 → 动作权限 → 处置授权新增 `disposal:direct`“直接反制（免逐次审批）”。仅显式 `OP` 生效；管理端显示“无/允许”，接口拒绝 READ/AUTH。迁移不授予任何角色，ROLE-ADMIN 全动作目录和本地种子也不继承此项；角色权限变更继续作废旧会话。
- `POST /api/v1/disposal-authorizations/direct-execute` 使用与普通申请相同的六字段请求体和 `Idempotency-Key`。要求直接权限、主体读权及数据范围；设备通道另需 `devices.op`。按现有策略校验事件/目标、现场条件、急停阻断、并发上限和有效期。直接权限只免去本次审批步骤，不代表取得法定反制资格或放宽这些条件。
- 成功建单返回 201：`authorization_id,authorization_no,status,version,authorization_mode=DIRECT,execution_block_reason?`。真正受理执行后为 EXECUTING；设备预检受阻则保留 APPROVED（表示授权窗口有效），记录阻塞事件并返回枚举原因，与详情字段相同。两种都不代表设备执行成功；参数/权限/主体错误事务回滚。
- 列表、详情新增 `authorization_mode=REVIEW|DIRECT`。旧记录默认 REVIEW，历史审批人与事件不回填、不篡改。DIRECT 的 `requested_by/at` 表示直接发起人/时间，`approved_by/at` 必须为空，`valid_from/until` 明确，事件为 `DIRECT_AUTHORIZE`，不伪造 APPROVE。
- DIRECT 记录的后续执行和人工结果仅原发起人且仍持有 direct 可操作；普通 `disposal:execute` 不会代替 direct。停止仍用既有独立停止权限。直接权限不能给普通申请自己审批。
- 排队设备启动以及反制完成后的自动干扰重新检查 DIRECT 发起人的当前权限、用户/角色状态、数据范围和有效期；干扰沿用 DIRECT，窗口不超过父授权，不产生假审批人。真实设备回执及急停核查沿用原链。
- advisory overview 新增 `can_direct_counter`，与 `can_request_counter` 独立计算。业务前台直接资格成立时优先显示“直接反制”，否则有申请资格时显示“申请反制”；每次提交仍由后端重新校验。
- 迁移：`V202609170030__direct_disposal_permission.sql`。只增目录/模式/约束，不自动触发处置、不写真实角色授权。代码生效需要迁移和后端重启。


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
| POST | `/disposal-authorizations` | `disposal:request` + 主体读权限 | body `{action_type, subject_kind, subject_id, device_id?, channel, reason}`；策略校验：`requires_confirmed_event` 时事件须 `CONFIRMED`（否则 409 `POLICY_REQUIRES_CONFIRMED_EVENT`）；同主体同类型已有活动授权 → 409 `ACTIVE_AUTHORIZATION_EXISTS`；所选设备停用、离线、上报工作异常、没有上报状态或上报故障 → 409 `DEVICE_UNAVAILABLE`，不建授权，message 写明是哪一种（2026-10-06 BUG-03；设备忙不在此拦，执行时再查）；对 `TARGET` 申请需要已核实事件的动作时，409 `POLICY_REQUIRES_CONFIRMED_EVENT` 的 message 说真实原因：类别未确认、没有违规告警、告警还没核实或已核实为误报（ZT-18） |
| GET | `/disposal-authorizations?subject_kind&subject_id&status&exclude_status&action_type&page&size` | `disposal:read` | 分页；`exclude_status` 排除某一状态（处置授权队列默认排除 `COMPLETED`） |
| GET | `/disposal-authorizations/{id}` | `disposal:read` | 含 `allowed_actions`（按状态与调用者权限计算：APPROVE/REJECT/EXECUTE/STOP/CANCEL/MANUAL_RESULT） |
| GET | `/disposal-authorizations/{id}/events` | `disposal:read` | 只增事件流 |
| POST | `/{id}/approve` `{expected_version, note?}` | `disposal:approve` | 两人规则：审批人 ≠ 申请人 → 否则 409 `TWO_PERSON_RULE`；REQUESTED 以外 409 `INVALID_TRANSITION` |
| POST | `/{id}/reject` `{expected_version, note}` | `disposal:approve` | — |
| POST | `/{id}/execute` `{expected_version, operation_params?}` | `disposal:execute`（设备通道还需 A 的 `devices.op`，13-9） | 必须 APPROVED 且在时限内（过期 409 `AUTHORIZATION_EXPIRED`）；LINGYUN_B → **调 A 之前**按 A 的公开判据自检四种受阻（13-29，顺序：指令码未开通 → 未绑定 → 未启用/不在线）：`PROTOCOL_NOT_OPENED` 事件 + 409 `DEVICE_CONTROL_UNAVAILABLE`；`DEVICE_NOT_BOUND` 事件 + 409 `DEVICE_NOT_BOUND`；`DEVICE_OFFLINE` 事件 + 409 `DEVICE_OFFLINE`；全部通过才从 `policy.command_map` 取码调 A 的 `enqueue`，受理则 EXECUTING。A 仍可能因请求本身拒绝（参数非法、设备类型与指令码不同族、设备不存在）：原样透出、不记事件、不改状态（13-14）。`COUNTERMEASURE_4CH` → COUNTERMEASURE 全开 `0x0F`、JAMMING 驱离 `0x0D`；设备须是四通道协议且在线，否则 `DEVICE_CAPABILITY`/`DEVICE_OFFLINE`；DECOY/DISPERSAL 选四通道 → 400 `VALIDATION_ERROR`，不发射。受阻时授权保持 APPROVED。A 已开通 `ifr`（60002/60003）/`dec`（50002）/`bsc`（70001）：未绑定或离线时阻塞为 `NOT_BOUND`/`DEVICE_OFFLINE`，绑错类型为 400 `VALIDATION_ERROR`；尚未映射的码族仍 `PROTOCOL_NOT_OPENED`。`MANUAL` → EXECUTING，等 `manual-result` |
| POST | `/{id}/manual-result` `{expected_version, result: SUCCEEDED|FAILED, detail}` | `disposal:execute` | 仅 MANUAL 通道 |
| POST | `/{id}/stop` `{expected_version, note}` | `disposal:stop` | APPROVED/EXECUTING → STOPPED。凌云 B 尝试急停不可用记 `DEVICE_STOP_UNAVAILABLE`（13-4）。四通道尝试 `SET_MASK 0x00` 全关，成功记 `DEVICE_ALL_OFF_ISSUED`（不是急停）；不以凌云 MQTT 绑定判断四通道 |
| POST | `/{id}/cancel` `{expected_version, note?}` | 申请人本人（`disposal:request`，直接授权凭 `disposal:direct`）或 `disposal:approve` | REQUESTED/APPROVED → CANCELLED；`result_code` 记 `CANCELLED_BY_REQUESTER` 或 `CANCELLED_BY_APPROVER`。已批准还没执行的授权（例如批准后设备掉线）可以撤销，撤销后同一主体可以马上重新申请（2026-10-06 BUG-03） |
| GET | `/disposal-policies` | `disposal:read` | 当前策略与参数（含 DEMO 标记） |

回执同步：`DisposalReceiptSync`（随 A 的 `device_command` 状态）`SUCCEEDED→COMPLETED`、`FAILED|TIMED_OUT→FAILED`（四通道启动回 `SUCCEEDED` 仍是 `EXECUTING`，到时全关回 `SUCCEEDED` 才 `COMPLETED`，见 2026-10-08 增量）；到期任务 `DisposalExpiryJob`（`app.disposal.expiry.enabled`，缺省关）把超过 `valid_until` 的 APPROVED 置 EXPIRED。

## 2.1 错误码汇总
`VALIDATION_ERROR`(400) / `SUBJECT_KIND_NOT_SUPPORTED`(400) / `POLICY_REQUIRES_CONFIRMED_EVENT`(409) / `ACTIVE_AUTHORIZATION_EXISTS`(409) / `DEVICE_UNAVAILABLE`(409，申请时) / `TARGET_NOT_ACTIVE`(409) / `TWO_PERSON_RULE`(409) / `INVALID_TRANSITION`(409) / `AUTHORIZATION_EXPIRED`(409) / `DEVICE_CONTROL_UNAVAILABLE`(409) / `DEVICE_NOT_BOUND`(409) / `DEVICE_OFFLINE`(409) / `VERSION_CONFLICT`(409) / `NOT_FOUND`(404，含越权)。前端逐码文案在 `ui/disposalAuthModal.js`（确定失败码集合 + 文案），状态/动作/通道/阻塞原因字典在 `ui/labels.js`；未列出的码走服务端消息兜底。

## 3. 事件（只增）
`event_kind ∈ REQUEST|APPROVE|REJECT|EXECUTE|RECEIPT|STOP|COMPLETE|FAIL|EXPIRE|CANCEL|MANUAL_RESULT|DEVICE_STOP_UNAVAILABLE|DEVICE_CONTROL_UNAVAILABLE|PROTOCOL_NOT_OPENED|DEVICE_NOT_BOUND|DEVICE_OFFLINE|DEVICE_ALL_OFF_ISSUED`（13-11/13-14/13-22：`DEVICE_CONTROL_UNAVAILABLE`=所选设备不能走该通道；`PROTOCOL_NOT_OPENED`=A 未开放码族；`DEVICE_NOT_BOUND` 表示设备未登记凌云 MQTT；`DEVICE_ALL_OFF_ISSUED`=四通道已下发全关；同一设备上另一事件急停下发全关时，也给本设备上已批准或执行中的其他授权补记一条，见急停接口文档），带 `actor_id`、`note`、`snapshot`（状态、时限、命令号）。

## 4. 与其它模块
- `HandoffRules`：`UAV_PUNISHMENT` 前提 = 该 `uav_event` 存在 `COMPLETED` 授权（13-6）。
- 工作台：已核实事件的"联动反制"动作可用（打开申请）；反制完成后自动接信号干扰（13-34），续上的干扰沿用原授权的批准：`approved_by`/`approved_at` 照抄原授权，`APPROVE` 事件 `actor_id` 为空、`snapshot.approval_source=CHAINED_FROM_PARENT`，有效期不超过原授权，原授权已过期就不续（2026-10-06 ZT-41）；处罚交接在至少一条授权 `COMPLETED` 且干扰未进行中时作为下一步。
- 处罚页"反制与公安信号干扰授权记录"区块 = 按主体列出授权及事件。
- 告警页 KPI：联动反制 = 今日 `COUNTERMEASURE` 授权数（按状态分）；信号干扰同理。列表「状态」列在 `CONFIRMED` 上按处置进度展示，不改 `uav_event.state_code`。

## 5. 待确认（客户 Q5）
授权条件、审批层级、时限、急停能力、哪些设备可自动执行。确认前策略 `demo-v1` 全部 DEMO，页面标注"演示策略，待业务确认"。
