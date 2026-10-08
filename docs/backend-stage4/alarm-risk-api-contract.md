# 阶段 4 告警与飞行风险接口契约

## 2026-10-07 按关注分组筛选与待处置统计

`GET /api/v1/alarms` 与 `GET /api/v1/alarms/export.csv` 新增可选筛选 `attention_group`：逗号分隔的 `CURRENT`、`AWAITING_CONFIRMATION`、`HISTORY`，与每行返回的 `attention_group` 用同一个即时分类表达式，`total`、分页、导出与审计筛选条件一致；未知、重复、空项或重复传参返回 400 `VALIDATION_ERROR`。告警页“待处置”统计 = `state=CONFIRMED&attention_group=CURRENT,AWAITING_CONFIRMATION`：已核实属实、处置尚未结束（含执行中的反制/干扰和未完成的急停核查），不限日期；结束依据与下文分组规则相同，分组规则调整时统计随之变化，不另立口径。

同日新增可选筛选 `violation_reason`（单个代码）：按告警当前违规原因匹配完整代码，升级过取最近一次升级的累计原因，否则取告警明细的 `violation_reasons`，与列表、详情返回的 `violation_reasons` 同源。可选值为规则引擎会写入告警的原因：`INSIDE_RESTRICTED_AIRSPACE`、`AIRSPACE_ALTITUDE_EXCEEDED`、`TEMPORARY_RESTRICTION_ACTIVE`、`ROUTE_DEVIATION`、`TIME_WINDOW_OVERRUN`、`NIGHT_FLIGHT`、`PLAN_ALTITUDE_EXCEEDED`、`BVLOS_EXCEEDED`、`NO_AUTHORIZATION`；其他值或重复传参 400 `VALIDATION_ERROR`。告警页“类别”下拉改按违规原因筛；`alarm_type` 筛选保留兼容。

导出表在"告警类别"之后加"违规原因"一列（UX-61）：取值与上面的 `violation_reasons` 同源，按告警页的说法译成中文，几条原因用顿号连接；没有记录原因的告警该格为空。

## 2026-10-07 告警关注次序与观测时效

`GET /api/v1/alarms` 和详情新增 `observation_status`（`CURRENT` 观测有效、`EXPIRED` 观测已过期、`UNKNOWN` 观测待确认）与 `attention_group`（`CURRENT` 当前事项、`AWAITING_CONFIRMATION` 状态待确认、`HISTORY` 历史）。它们是查询时计算的告警摘要，不是核实状态；`state`、历史和动作资格不变，alarm:read 可读这两项摘要，不额外暴露目标标识、坐标或观测时间。

默认 `priority` 先当前、再待确认、最后历史；前两组按当前等级降序、接收时间降序，历史仅接收时间降序，同时间以 alarm_id 升序稳定分页。删除新到 5 分钟置顶和同等级旧告警优先规则。显式指定排序仍尊重原 sort/order；CSV 共用同一次序，末尾追加“观测状态”“关注分组”，原列位置不变。

观测来自告警所关联且同组织、区域、来源模式的 target_latest_state，使用生效融合参数 identity.terminate_after_ms；恰好到期算过期。缺失位置/观测、未来时间、TIME_UNTRUSTED 或目标关联不一致均为 UNKNOWN，不以 received_at 冒充有效观测。未核实也自动下沉，恢复有效观测后自动重新进入当前组。经相同事件、组织、区域和来源关联的设备反制/干扰 APPROVED、EXECUTING，以及急停设备 confirmed_at 为空且 stop_status 非 NOT_REQUIRED，优先保留当前组。FALSE_POSITIVE 或最新实际设备处置 COMPLETED 归历史；完成时间须不早于最新告警升级（无升级时为告警接收），旧完成记录不能盖住后续未完成的处置。已确认但无结束依据仍按观测分组。不使用已停用的人工现场记录推断飞离，不因失联或到期自动解除告警。

## 交付边界

阶段 4 把来源告警、无人机核实事件和飞行风险切换为后端持久化事实。来源告警保持不可变；同一目标的多条告警分别形成事件。无人机核实属实只表示“已核实，待处置”，不表示反制、干扰或处罚交接已经完成。风险核验通过只进入待通知，不表示已经通知上级。

生产未确认告警/风险权威来源和风险生成规则时，读取返回真实空集合；固定样例仅允许在双门禁的 local/test 环境生成。API 失败不得回退浏览器 Mock。

## 通用约定

### 2026-10-06 计划当前异物风险聚合

`GET /api/v1/risks/current` 在权限与数据范围过滤、持续状态判定之后，按目标 ID、计划 ID、航线版本、来源编码与模式、单位/区域、规则集版本、规则版本及异物子类合并 `SPACE_OBJECT` 连续评估。只合并具备目标关联和完整 `space_risk_fact` 的记录；其它风险类型及依据缺失的记录分别保留。不能按名称、坐标、分钟窗口或展示文案判重。

每组返回发生时间最新的一条未解除/未排除记录；同发生时间按接收时间、风险 ID 确定稳定顺序。代表记录的等级、当前状态、核验状态、动作权限和详情 ID 均来自该原始记录，不拼接其它记录的办理状态。`total/current_total/uncertain_total` 与分页均在聚合后计算，先列当前仍存在，再列待确认；不按记录年龄自动删除待确认风险。

本接口仅改变当前列表投影，不改写风险、通知、核验或解除证据。`GET /api/v1/risks`、详情、历史与导出继续保留逐次评估。已排除/已确认解除不返回，后续再次命中的新记录可独立成为当前项；缺少权威解除依据的旧目标不得因模拟器停止上报或名称相同而合并/解除。

融合输入约束：C04/C05 对已有 FUSED 轨迹的目标，要求最新位置、观测时间及接收时间与 `MEAS` 点一致。`PRED`、`BRIDGE` 或停报后只刷新的最新状态不能生成新风险；未进入融合层的规范化接入维持原输入契约。该限制统一适用于 mock/replay/live，不按模拟器批次、名称或坐标猜测目标身份。

历史误评估只读纠正：仅当 C04 原始位置快照与当时同目标的 `PRED/BRIDGE` 点、发生时间吻合，且风险接收前同观测时刻没有 `MEAS` 点时，该条评估不参与当前聚合与计数。历史/详情仍返回 `UNKNOWN` 并说明预测或插值依据，不伪造人工排除或物理解除。缺少历史证据不触发该过滤；真正观测产生的旧风险即使后来停报/预测，仍按原持续状态规则处理。

- 基础路径 `/api/v1`，响应使用下文定义的成功/失败互斥包络，JSON 使用 snake_case。
- ID 为字符串，REST 时间为 epoch 毫秒，业务取时使用 `AppClock`。
- 列表请求沿用 `page`、`size`，默认 1/20，最大 100；响应为 `items/page/size/total`。`page_size` 属于未知参数并返回 400，避免破坏已冻结的公共分页契约。
- 重复、未知或非法的 query/body 字段返回 400；写请求体只允许 `conclusion,note,expected_version`，同名 JSON 字段重复也拒绝。时间过滤为 `[from,to)`，且 `from < to`。
- 所有 GET/POST 必须先完成动作权限校验，再解析筛选、路径 ID 或读取对象；未授权请求即使同时携带非法/重复参数或无效 ID 也统一先返回 403，避免通过 400/404 差异探测接口和对象。写接口缺少任一所需的读/写动作权限均按 403 处理。
- 列表、详情、历史、`total` 与 `allowed_actions` 使用同一权限和组织/区域范围谓词。
- `ASSIGNED` 必须匹配同一个有效 `(owner_org_id,district_id)` 授权元组；跨范围对象详情按 404 处理。
- `ALL` 仍要求对象的组织、区域目录存在且启用，不能绕过缺失或停用归属；列表、详情、历史和关联字段使用相同判断。

## 权限

| 接口 | 权限 |
| --- | --- |
| 告警列表/详情、无人机事件详情/历史 | `alarm:read` |
| 提交无人机核实 | `alarm:read` + `alarm:verify` |
| 风险列表/详情/历史 | `risk:read` |
| 提交风险核验 | `risk:read` + `risk:verify` |

写接口先验证源对象读权限和动作权限，再按当前范围锁定对象。菜单可见性不是动作授权；只有写权限也不能读取或修改对象。

关联 ID 不是风险/告警读权限的附赠信息。告警 `target_id` 需要 `target:read`；风险 `plan_id`、`route_version_id`、`assessment_id`、`target_id/track_id` 分别需要 `flight:read`、`route:read`、`assessment:read`、`target:read`。即使具备对应动作权限，关联对象也必须仍属于操作者可见的同一个有效组织/区域元组，否则字段省略。使用 `target_id` 或 `plan_id` 作为筛选条件同样需要对应关联读权限，避免通过 `total` 或空列表探测猜测的 ID。

## 响应包与字段归属

成功响应固定为 `{ "ok": true, "data": ... }`，失败响应固定为 `{ "ok": false, "error": { "code": "...", "message": "..." } }`；两者不混入另一侧字段。以下未标为“可省略”的字段必须存在。可省略字段在数据未知、无关联权限或关联对象已不可见时不出现在 JSON 中，不使用空字符串、零值或占位 ID 代替。

| 响应 | 固定字段 | 可省略字段及条件 |
| --- | --- | --- |
| 告警列表/详情项 | `alarm_id,event_id,alarm_type,severity,received_at,source_code,source_mode,owner_org_id,district_id` | `event_id` 在尚无事件时仍必须显式为 `null`；`state` 在无事件时省略；`occurred_at` 未知时省略；`target_id` 仅在具备 `target:read` 且目标仍匹配同一有效范围元组时返回 |
| 无人机事件详情/写成功 | `event_id,alarm_id,state,version,created_at,updated_at,allowed_actions` | `target_id` 仅在具备 `target:read` 且目标仍匹配同一有效范围元组时返回 |
| 无人机核实历史项 | `history_id,previous_state,resulting_state,conclusion,note,version,actor_id,created_at` | 无；只提供操作归属 ID，不扩展账号、姓名、联系方式或其他用户资料 |
| 风险列表/详情/写成功 | `risk_id,source_risk_id,risk_type,severity,state,reason_code,reason_text,received_at,source_code,source_mode,owner_org_id,district_id,version,allowed_actions` | `occurred_at` 未知时省略；`observed_altitude_m,observed_altitude_datum,height_relation` 仅返回已保存事实，未知项省略或保持明确 `UNKNOWN`，不得推导；关联 ID 按下表权限分别返回 |
| 风险核实历史项 | `history_id,previous_state,resulting_state,conclusion,note,version,actor_id,created_at` | 无；`actor_id` 只提供操作归属 ID，不扩展用户资料 |
| 任一列表/历史页 | `items,page,size,total` | 无；空页为 `items:[]`，`total` 仍使用与 `items` 相同的权限、范围与过滤谓词 |

风险关联字段的最小权限和范围如下；缺少任一条件时仅省略对应字段，不把整个风险变成无权：

| 风险关联字段 | 附加动作权限 | 对象可见性 |
| --- | --- | --- |
| `plan_id` | `flight:read` | 已保存计划与风险属于同一有效组织/区域元组 |
| `route_version_id` | `route:read` | 已保存航线版本属于风险所关联计划，且该计划与风险属于同一有效元组 |
| `assessment_id` | `assessment:read` | 已保存研判属于同一计划和航线版本，且计划与风险属于同一有效元组 |
| `target_id,track_id` | `target:read` | 目标与轨迹属于同一目标，且目标与风险属于同一有效元组 |

`allowed_actions` 的阶段 4 固定词典只有 `VERIFY`。只有当前状态允许核实、操作者同时具备源读权限与对应 verify 权限、对象仍匹配精确范围元组时才返回 `VERIFY`；否则返回空数组。它表示可以提交该资源支持的任一核实结论，不表示反制、通知、交接或处置已接入。

## 告警与无人机事件

```text
GET  /api/v1/alarms
GET  /api/v1/alarms/{alarm_id}
GET  /api/v1/uav-events/{event_id}
GET  /api/v1/uav-events/{event_id}/verifications
POST /api/v1/uav-events/{event_id}/verifications
```

告警过滤：`state,severity,target_id,occurred_from,occurred_to,owner_org_id,district_id,source_mode,page,size`。固定排序为 `received_at DESC, alarm_id DESC`。无事件时 `event_id` 显式为 null；GET 不创建事件。无目标读取权限时不返回目标引用。

无人机状态：

```text
PENDING_VERIFICATION
  → CONFIRMED / FALSE_POSITIVE
```

核实结论只允许属实或误报。`EVIDENCE_REQUIRED` 不再作为可提交结论或当前事件状态；已落库的核实历史仍可保留该结论。终态不由本接口重开。

人工核实依据（2026-10-06，ZT-43）：人工核实为属实（CONFIRMED）前，服务端按系统已有事实检查依据，缺依据时 422 `VERIFICATION_BASIS_MISSING`，`message` 说明缺什么，事件不变、不写历史；核实为误报不受限。依据为：告警关联到与事件同一组织区域的目标（含融合后的当前目标）；有不早于“事件建立时刻 − 有效时长”的正式（ACTIVE）合法性研判；目标数据仍在有效时长内（生效规则集 C03 `fresh_seconds`），或已有关联本事件、或本次告警期间关联该目标的可用光电截图、光电录像、现场照片。缺项码：`NO_TARGET`、`NO_EVALUATION`、`NO_CURRENT_DATA`、`NO_FRESHNESS_RULE`（未配置有效时长）。自动核实规则另行判断，不受此检查影响。

事件详情与写成功响应在当前用户可核实（`allowed_actions` 含 `VERIFY`）时另带 `verification_basis`：`{confirmable,missing[],message?,checked_at}`，读取时现算；页面据此提示“缺少依据，不能核实为属实”，提交时仍以服务端检查为准。不可核实时省略该字段。

## 飞行风险

```text
GET  /api/v1/risks
GET  /api/v1/risks/{risk_id}
GET  /api/v1/risks/{risk_id}/verifications
POST /api/v1/risks/{risk_id}/verifications
```

风险过滤：`state,severity,plan_id,occurred_from,occurred_to,owner_org_id,district_id,source_mode,page,size`。固定排序为 `received_at DESC, risk_id DESC`。风险关联固定计划、航线版本和可选研判版本；读取历史依据时不能改用最新版本。

2026-09-17 可选展示筛选补充：`GET /api/v1/risks`、`GET /api/v1/risks/export.csv` 与 `GET /api/v1/space-risks/summary` 支持 `exclude_demo_samples=true|false`，省略为 false。空值、重复值及其他取值返回 400 `VALIDATION_ERROR`。

- true 仅排除 `source_mode=mock` 且满足以下任一结构化标志的记录：来源 `source_code=WEATHER-DEMO`，或 `source_risk_id` 以 `pending-plan-notice-demo-` 开头。
- 筛选在分页、计数、CSV 上限判断和空间汇总前执行；不改变排序、风险类型、时间窗、范围权限或汇总维度。
- `mock` 的规则研判结果、MQTT `replay`、`live` 和其他气象风险继续保留；`space-risk-demo-v1` 是规则集版本，不能据此排除实际规则产出的风险。
- 仅改变显式请求的读结果，不删除样例，不更改详情、核验、通知、回执或审计；默认接口保持兼容。

风险状态：

```text
PENDING_VERIFICATION → PENDING_NOTIFICATION
PENDING_VERIFICATION → EXCLUDED
```

本阶段不写 `NOTIFIED`。未知高度或 AGL/AMSL 缺少换算依据时不判断安全，也不以 0 补值。

## 写请求、并发与审计

写请求头必须携带 8–128 字符 `Idempotency-Key`。两个 POST 的 JSON 对象只允许下列三个键，每键最多出现一次，必填性见表；不接受数组、额外键、重复键或尾随 JSON 内容：

| 字段 | 类型/范围 | 无人机事件 | 飞行风险 |
| --- | --- | --- | --- |
| `conclusion` | string | `CONFIRMED/FALSE_POSITIVE` | `CONFIRMED/EXCLUDED` |
| `note` | string，去首尾空白后最多 1000 字符 | 可省略或为空，记录为空字符串；前台不再输入 | 选填；省略、null、空白均记录为空字符串 |
| `expected_version` | JSON integer，`>= 0` 且可装入 Java `long` | 必填 | 必填 |

JSON 语法、结构、未知/重复/缺失键或字段类型错误返回 `INVALID_REQUEST`；已正确解析后的非法 conclusion 返回 `INVALID_CONCLUSION`，说明长度或版本取值错误返回 `VALIDATION_ERROR`。动作鉴权通过后，先完成 query/path/body 的纯语法与字段校验，再按范围锁对象并产生业务写入；对象可见后由 `IdempotencyGuard` 校验并占用幂等键。可观察的事务内顺序为：按范围锁对象、占用幂等键、检查版本与状态、条件更新一行、追加历史、成功审计、提交。

幂等 operation 的稳定输入必须使用无歧义序列化，覆盖资源类型、资源 ID、结论、去空白后的说明和 `expected_version`；不得直接用可出现在说明中的分隔符拼接，避免两组不同字段得到同一请求摘要。

- 同键同请求：409 `IDEMPOTENCY_REPLAY`。
- 同键不同请求：409 `IDEMPOTENCY_KEY_REUSED`。
- 旧版本或条件更新失败：409 `VERSION_CONFLICT`。
- 状态不允许：409 `INVALID_TRANSITION`。
- 结论非法或说明非法：400。

任一步失败必须回滚状态、历史、幂等占位和成功审计。拒绝审计由业务事务退出后的统一异常路径记录，避免与业务回滚或重复失败审计混淆。

## 稳定错误码与优先级

下表列出进入阶段 4 资源后的稳定业务码。平台在此前仍可能返回既有 `PASSWORD_CHANGE_REQUIRED`（账号必须先改密）等认证前置错误；本阶段不改写这些公共语义。

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | 会话缺失、无效或已过期 |
| 403 | `FORBIDDEN` | 缺少任一所需动作权限；它优先于该请求中的参数、路径 ID、对象存在性和幂等信息 |
| 400 | `VALIDATION_ERROR` | 未知/重复参数、分页、ID、说明、版本或字段值不合法 |
| 400 | `INVALID_REQUEST` | JSON 语法错误、无法解析的请求体或请求结构错误 |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 已授权写请求缺少 `Idempotency-Key`，或其长度不在 8–128 字符范围 |
| 400 | `INVALID_TIME_RANGE` | 时间不是 epoch 毫秒、边界缺失或不满足 `from < to` |
| 400 | `INVALID_CONCLUSION` | 结论不属于对应资源的阶段 4 固定词典 |
| 404 | `ALARM_NOT_FOUND` | 告警不存在或不在当前精确范围内 |
| 404 | `UAV_EVENT_NOT_FOUND` | 无人机事件不存在或不在当前精确范围内 |
| 404 | `RISK_NOT_FOUND` | 飞行风险不存在或不在当前精确范围内 |
| 409 | `IDEMPOTENCY_REPLAY` | 同一键与同一稳定请求已经成功提交 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同一键已用于不同稳定请求 |
| 409 | `VERSION_CONFLICT` | `expected_version` 过期或条件更新未命中一行 |
| 409 | `INVALID_TRANSITION` | 当前状态不允许该结论 |
| 500 | `INTERNAL_ERROR` | 未预期服务错误；不得携带内部 SQL、类名、堆栈或原始载荷 |

优先级固定为：会话认证（401）→ 全部所需动作权限（403）→ query/path/body 语法与字段（400）→ 精确范围内对象查找（404）→ `Idempotency-Key` 格式（400）或冲突（409）→ 版本冲突（409）→ 状态迁移冲突（409）。权限撤销或对象变为不可见时不得为了返回重放结果而泄漏旧请求；通过对象范围校验后，同键重放必须先于旧版本/终态判断。

## 尚未接入

阶段 4 不执行真实反制、信号干扰、上级通知、处罚交接、处罚立案或真实证据保管。对应页面入口保持禁用并明确说明原因；这些缺口不是接口成功的替代状态。

## 2026-10-06 告警升级与升级记录

同一架无人机再次违规时，规则引擎升级它原有的告警，不再另起一条（规则见 `docs/backend-stage7/rule-engine-api-contract.md` 2026-10-06 一节）。`alarm` 行不改，升级事实只追加在 `alarm_escalation`。

告警列表、详情与导出：

- `severity` 改为告警当前等级：升级过取最近一次升级后的等级，否则是告警产生时的等级。列表 `severity` 筛选、`sort=severity` 与默认 `priority` 排序都按当前等级；导出“等级”列同口径，导出列不变。
- 新增固定字段 `original_severity`（告警产生时的等级）、`violation_reasons`（累计的违规原因代码，按出现顺序、不重复；没有时为 `[]`）、`escalation_count`（0 表示没升级过）；可省略字段 `escalated_at`（最近一次升级时刻，没升级过时省略）。
- 告警报表（`/stats` 报表的告警与无人机事件分区）的等级分布同样按当前等级。

```text
GET /api/v1/alarms/{alarm_id}/escalations?page&size     alarm:read
```

权限、范围与 404 同告警详情；只接受 `page,size`，其他参数 400 `VALIDATION_ERROR`。按 `seq` 正序分页，项：`escalation_id, seq, trigger_kind ENGINE|MANUAL, severity_before, severity_after, reasons_added, reasons_after, created_at`，可省略 `note, actor_id, actor_name`（只有人工转告警有操作人；说明为空时省略）。升级不改 `uav_event` 状态，已核实属实的告警升级后不要求重新核实。PostgreSQL 上该表只增，写入时发实时推送 `alarm` 主题。
