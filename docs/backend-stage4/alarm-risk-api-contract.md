# 阶段 4 告警与飞行风险接口契约

## 交付边界

阶段 4 把来源告警、无人机核实事件和飞行风险切换为后端持久化事实。来源告警保持不可变；同一目标的多条告警分别形成事件。无人机核实属实只表示“已核实，待处置”，不表示反制、干扰或处罚交接已经完成。风险核验通过只进入待通知，不表示已经通知上级。

生产未确认告警/风险权威来源和风险生成规则时，读取返回真实空集合；固定样例仅允许在双门禁的 local/test 环境生成。API 失败不得回退浏览器 Mock。

## 通用约定

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

## 飞行风险

```text
GET  /api/v1/risks
GET  /api/v1/risks/{risk_id}
GET  /api/v1/risks/{risk_id}/verifications
POST /api/v1/risks/{risk_id}/verifications
```

风险过滤：`state,severity,plan_id,occurred_from,occurred_to,owner_org_id,district_id,source_mode,page,size`。固定排序为 `received_at DESC, risk_id DESC`。风险关联固定计划、航线版本和可选研判版本；读取历史依据时不能改用最新版本。

风险状态：

```text
PENDING_VERIFICATION → PENDING_NOTIFICATION
PENDING_VERIFICATION → EXCLUDED
```

本阶段不写 `NOTIFIED`。未知高度或 AGL/AMSL 缺少换算依据时不判断安全，也不以 0 补值。

## 写请求、并发与审计

写请求头必须携带 8–128 字符 `Idempotency-Key`。两个 POST 的 JSON 对象只允许下列三个键，每键必须且只能出现一次，不接受数组、额外键、重复键或尾随 JSON 内容：

| 字段 | 类型/范围 | 无人机事件 | 飞行风险 |
| --- | --- | --- | --- |
| `conclusion` | string | `CONFIRMED/FALSE_POSITIVE` | `CONFIRMED/EXCLUDED` |
| `note` | string，去首尾空白后 1–1000 字符 | 必填 | 必填 |
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
