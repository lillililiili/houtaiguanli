# 事件反制急停接口

> 2026-09-15 维护归属：本文为 `houtaiguanli` 唯一后端的正式接口说明。`server/` 为本仓库目录，业务前台位于同级 `../dongyiwurenji/dongying-vue/`。正文的2026-09-14部署、数据批次、测试、页面与日志记录均为旧环境历史事实，不能据此认定新库已有该演示批次或当前页面已完成验收。迁移后状态见[2026-09-15核对清单](../旧后端剩余改动迁移清单-20260915.md)。


路径基址：`/api/v1/uav-events/{eventId}/emergency-stop`。Bearer 会话及 `ApiResponse` 包装沿用现有接口；所有时刻为 UTC epoch 毫秒，缺失字段省略。

| 方法 | 路径 | 请求体 |
| --- | --- | --- |
| GET | 基址 | 无，查询平台记录和设备指令当前状态 |
| POST | 基址 | `{}`，无需事前填写理由 |
| POST | `/{stopId}/notes` | `{"note":"补充原因"}` |
| POST | `/{stopId}/devices/{deviceId}/retry` | `{}` |
| POST | `/{stopId}/devices/{deviceId}/manual-confirm` | `{"note":"现场核查依据"}` |

写入须携带 8–128 字符 `Idempotency-Key`，重放不再次下发设备指令。说明为 1–500 字符，记录为追加方式。读取需 `disposal:read` 与事件可见范围；写入需 `disposal:stop` 与事件可见范围。客户端不能指定操作者或设备结果。

所有接口返回同一概览：`event_id`、`applicable`、`requires_device_stop`、`allowed_actions`、可选 `block_reason`、`authorizations` 与可选 `latest_stop`。`requires_device_stop` 是必填布尔值，由当前控制范围是否包含曾执行的任务推导；仅待执行任务为 false，活动干扰关联已完成父反制时为 true，无当前控制目标为 false。前端据此决定“急停”或“撤销”，不自行根据历史授权猜测。

- 授权摘要：`authorization_id/action_type/status/device_id/channel`。
- 停止记录：`stop_id/requested_at/requested_by_name/reason_pending/note/devices/events`。
- 设备任务：`device_id/device_name/channel/source_mode/simulated/command_id/command_status/stop_status/detail/allowed_actions`，现场确认后附 `confirmed_by_name/confirmed_at/confirmation_note`。
- 操作记录：`event_id/kind/actor_name/occurred_at/note`。
- 概览动作：`EMERGENCY_STOP/ADD_NOTE`；设备动作：`QUERY/RETRY_STOP/MANUAL_CONFIRM`。

人工处置未绑定设备时，`device_id` 为 `manual-{authorization_id}`，表示现场核查任务键，并非真实设备 ID；该行 `channel=MANUAL`，不得提供设备详情链接。

设备状态包括 `QUEUED/WAITING_FEEDBACK/CONTROLLER_ALL_OFF_ACK/FAILED/TIMED_OUT/OFFLINE/UNSUPPORTED/MANUALLY_CONFIRMED/NOT_REQUIRED`。`NOT_REQUIRED` 表示该任务尚未执行、已阻止启动，不需要设备停止，不阻塞后续新申请。四通道全关回码仅映射为 `CONTROLLER_ALL_OFF_ACK`，不证明物理停机。现场核查记录与设备回码分开；仅 `MANUALLY_CONFIRMED` 表示操作员已按所填依据确认停止。模拟标识始终保留。

停止事务持久化本次停止意图、关联授权、逐设备任务及审计；取消本次未发送的启动指令，阻止旧授权迟到完成后接续干扰。停止不解除告警，不恢复旧授权；未确认任务处理完成后可重新申请。其他事件共用同一设备时，远程全关的单事件范围无法保证，服务端阻止急停并明确原因，不越权透露不可见事件内容，也不停止其他事件。

控制范围仅包含当前活动的反制/干扰授权及活动干扰通过 `chained_from_authorization_id` 明确关联的父反制，不包含该事件全部历史完成/失败记录。概览与提交用同一范围判断共用设备。只有历史终态、没有活动链也没有既有急停记录时，不推断设备仍在执行，不提供急停；一旦已经发起急停，未确认任务不随授权终态消失。平台取消已发送命令的重投也不证明实际停止；其设备任务仍须核查。

取消范围包括上述授权关联的全部四通道启动与凌云启动指令，不仅是授权的 `execution_command_id`。已停止或被急停覆盖的已知处置授权，不能通过原设备直控接口再次排队或重投启动。检查与急停共用授权行锁，防止检查后并发插入漏单。四通道全关/单通道关闭及凌云 `operation_type=0` 属于停止操作，不被该启动屏障拦截；其他授权或既有外部协议授权引用的语义未在本次扩大修改。

## 实施验证（2026-09-14）

- 先验证缺失接口：1 条用例期望 200、实际 404；历史设备范围回归也先验证原实现错误返回 409。
- 最终专项：`EmergencyStopApiTest` 23 条、`EmergencyStopPostgresTest` 25 条、`AuditLabelsCoverageTest` 1 条、`AuthApiTest` 9 条、`Countermeasure4ChApiTest` 1 条、`LingyunControlMqttTest` 4 条，共 63 条，失败/错误/跳过均为 0。覆盖权威文案字段与旧直控启动屏障。PostgreSQL 使用专用 `stage456_verify_emergency_20260914` 数据库及每轮随机 schema，验证迁移、锁、并发幂等和追加记录防篡改；不使用业务库。
- 受影响旧授权/执行/设备网关/规则/四通道与认证曾合并运行 103 条，全通过；随后追加的范围与投递回归纳入上面的最终专项。
- 全量 `./mvnw package` 已执行：1034 条，7 失败、3 错误、138 跳过。发现的本任务审计标签缺失已经修复并专项验证；其他失败位于已有演示 Seeder、飞行核查等测试，未在本任务修改。
- `./mvnw -DskipTests package` 随后通过，生成 JAR；这是构建产物验证，不表示全量测试通过。`git diff --check` 通过。

未进行真实设备停机联调。本说明不把继电器回码或模拟回执当作物理停机证据。

## 迁入唯一后端（2026-09-15）

新后端现行端口8081，默认开发库为houtaiguanli；不迁入旧运行数据。事件急停使用新增迁移202609150002、PostgreSQL保护202609150003；报备字段使用202609150004。旧140004/140005/140006对应内容通过新编号承接，已应用历史脚本保持不变。现行代码与87项不同环境用例验证记录见[完整迁移清单](../旧后端剩余改动迁移清单-20260915.md)；本轮未做真实设备物理停机或新页面浏览器验收。
