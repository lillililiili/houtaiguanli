# 设备运维流程与站内提醒

接口沿用 `/api/v1`、Bearer 会话、snake_case 和 `{ok,data,error}`。状态变更均由服务端裁决，不调用设备重启、调测启动或修改设备状态。

## 任务与状态

既有列表字段保留。`status=PENDING` 表示未完成任务（包括处理中、待核验），`status=HANDLED` 包括本流程完成及历史反馈。新增 `workflow_state` 精确区分 `PENDING / PROCESSING / PENDING_VERIFICATION / COMPLETED / LEGACY_HANDLED`。迁移只把既有 HANDLED 标记为历史已反馈，不生成恢复通过记录，不修改通知快照。

`GET /device-maintenance-tasks` 的 status 增加 `ACTIVE / NOT_STARTED / PROCESSING / PENDING_VERIFICATION / COMPLETED`，原 `ALL / PENDING / HANDLED` 保留；ACTIVE 与原 PENDING 均覆盖全部未完成任务。NOT_STARTED 仅匹配 workflow_state=PENDING，供“待处理”筛选使用；任务开始处理后从 NOT_STARTED 移出，但仍出现在 ACTIVE 和兼容的 PENDING 中。列表与 total 计数使用相同条件。

`GET /device-maintenance-tasks/{taskId}/workflow` 返回：

- `task`：既有平铺任务 DTO，追加 workflow_state。
- `state, version, assigned_to_name`：当前阶段、乐观锁版本、最初开始操作的处理人。未开始时处理人省略。
- `events`：按任务版本升序排列的 event_id、action、note、actor_name、occurred_at。核验事件保留 PASS/FAIL/UNKNOWN 与原因。
- `commission_tasks`：已关联调测的 commission_id、status、simulated。
- `recovery`：最近有效轮次的 result、reason、checked_at；未核验或恢复处理后省略。历史核验仍在 events 中。
- `allowed_actions, blocked_reason`：当前动作与阻断原因。无阻断时原因省略。
- `open_incidents`：当前设备未恢复事件的 incident_id、reason、stage、allowed_actions；只从当前可见运维任务关联设备中读取。前端可显式调用既有事件核验接口，流程本身不自动关闭事件。

`POST /device-maintenance-tasks/{taskId}/workflow/actions` 必须携带 Idempotency-Key（8–128 字符）。请求为 `{action,expected_version,note?,commission_id?}`；成功返回 Workflow。相同用户、键、任务和请求返回按当前权限重新投影的最新 Workflow，重复不追加事件、不重复执行，不让延迟重试把界面退回旧阶段。键复用不同请求、版本过期返回 409。权限和数据范围在重放前仍检查。

| action | 阶段及约束 |
| --- | --- |
| START | 待处理 → 处理中，保存真实处理人 |
| SAVE_PROGRESS | 处理中追加 2–1000 字说明 |
| LINK_COMMISSION | 处理中关联已存在、同设备且同来源的可见调测任务；另需 commissioning.read，不启动调测 |
| SUBMIT_VERIFICATION | 处理中 → 待核验；2–1000 字说明；设备不得有执行中的指令、调测或跟踪任务 |
| VERIFY_RECOVERY | 待核验阶段记录 PASS/FAIL/UNKNOWN，仍停留待核验 |
| COMPLETE | 待核验 → 完成；2–1000 字说明；既有 PASS 不超过 300 秒，且本次重新核验仍 PASS |
| RESUME | 待核验 → 处理中，清除当前轮核验摘要、保留全部事件 |

恢复要求当前来源与报修时设备来源一致，模拟标识一致；设备启用；状态与心跳均为报修后、非未来且 300 秒内；在线、GOOD、明确无告警；不存在未恢复异常及执行中任务。未知健康不能被在线心跳替代。模拟来源通过明确标记不代表真实设备恢复。任一未恢复事件均阻断办结，须先通过既有设备异常流程核验；本接口不改设备和异常状态。

旧 `POST /device-maintenance-tasks/{taskId}/handling` 现返回 409 `MAINTENANCE_WORKFLOW_REQUIRED`，避免旧“一次反馈”绕过处理和恢复核验。此为有意的行为兼容性变更，调用方必须使用新流程。LEGACY_HANDLED 与 COMPLETED 均不可重新开始。

## 站内消息

`GET /device-maintenance-messages?page=1&size=8&unread_only=false` 需要 monitoring.read + monitoring.op，返回 `{items,total,page,size,unread_count}`。每条包含 task_id、device_id、device_name、reason、reported_at、read_at（未读时省略）、workflow_state。消息直接派生于当前可见任务，不另广播、不复制通知、不发生 GET 写入；总数与未读数均只覆盖当前授权范围。分页最大 100。

`POST /device-maintenance-messages/{taskId}/read` 接受空请求体，返回 `{task_id,read_at}`，同用户重复读取保留第一次时间。已读不改变任务状态、版本或通知回执。

## 验证

H2 专项：`./mvnw.cmd -Dtest=DeviceMaintenanceWorkflowApiTest,DeviceMaintenanceNoticeApiTest test`。

PostgreSQL/PostGIS 专项：隔离库名必须为 `maintenance_flow_verify_*`，通过 POSTGRES_TEST_URL / POSTGRES_TEST_USER / POSTGRES_TEST_PASSWORD 注入连接，再执行 `./mvnw.cmd -Dtest=DeviceMaintenanceWorkflowPostgresTest test`。该类覆盖迁移历史保留、只读 GET、并发 START/已读及继承的端到端测试；未配置时跳过，不连接业务库。
