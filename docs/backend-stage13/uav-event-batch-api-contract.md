# 无人机事件批量操作

2026-10-20。三个新增 POST 接口位于 `/api/v1/uav-events/batch`，沿用 Bearer 会话、snake_case、字符串 ID 和 `{ok,data,error}`。没有整批授权或整批设备指令；每项调用既有单事件用例。

每批 1–20 项，输入顺序等于输出顺序。同批重复 `event_id` 返回整批 400 `DUPLICATE_EVENT`，不执行任何项。非法 JSON、重复 JSON 键、外层/成员未知字段、非法事件 ID、空数组/超量返回整批 400。逐项业务错误保持在结果中。

## POST /precheck

```json
{"items":[{"event_id":"<event-id>"}]}
```

`data.items` 成员：

```json
{
  "event_id":"<event-id>",
  "status":"READY",
  "event":{"event_id":"<event-id>","alarm_id":"<alarm-id>","state":"PENDING_VERIFICATION","version":3,"allowed_actions":["VERIFY"]},
  "allowed_conclusions":["FALSE_POSITIVE","CONFIRMED"],
  "verification_block_reason":"",
  "counter":{"can_request":false,"can_direct":false,"block_reason":"先完成核实"}
}
```

`event` 为现有单事件 DTO 的完整结构；示例省略时间和依据字段。`allowed_conclusions` 无权限/非待核实为空；有核实权限时允许误报；仅现有 `verification_basis.confirmable=true` 时增加属实。核实结论分别为 `FALSE_POSITIVE`、`CONFIRMED`。

`counter` 复用当前通知处置概览的 `can_request_counter/can_direct_counter/counter_block_reason`，只提示当前事件资格。设备、通道、策略、当前版本、来源、有效期、证据、占用及执行资格在提交时按原用例重新检查；READY 不承诺动作可执行。预检不核实、不发送通知、不调度设备、不创建授权。预检失败保留成员，`status=FAILED|UNKNOWN`，并提供 `error:{code,message}`，不返回无权对象详情。

## POST /verify

```json
{"items":[{"event_id":"<event-id>","expected_version":3,"conclusion":"CONFIRMED","note":"逐项核对后的说明","idempotency_key":"<per-item-key>"}]}
```

每项复用 `UavEventVerificationService.verify`，包含动作/读取权限、数据范围、版本、证据、状态、幂等、历史及审计。note 选填，沿用单事件 1000 字限制。事件读取/核实动作缺失时整批 403，不执行任何项。

## POST /counter

```json
{"items":[{
  "event_id":"<event-id>","expected_version":4,"idempotency_key":"<per-item-key>","mode":"REQUEST",
  "request":{"action_type":"COUNTERMEASURE","subject_kind":"UAV_EVENT","subject_id":"<event-id>","device_id":"<device-id>","channel":"LINGYUN_B","reason":"选填","countermeasure_mode":"FORCE_LAND"}
}]}
```

`mode=REQUEST` 委托原申请服务，保留原审批和策略；`mode=DIRECT` 委托原直接执行服务，要求原独立直接反制权限及设备操作权限。`request` 严格采用现有处置请求；必须指向本项同一事件且动作必须是 COUNTERMEASURE，不允许跨对象替换。`countermeasure_mode=FORCE_LAND|DRIVE_AWAY`；兼容旧接口的省略行为不变。通道、设备和其他参数按原服务校验。

单项事务按原事件锁顺序先锁定可见事件，校验本项 `expected_version`，再调用原处置服务。同事务内保留全部单事件校验、授权/指令/审计和既有原子性。批量成员依次处理；选中同一设备的后续项仍会受既有设备占用和回执约束拒绝或保留待审批，批量接口不会抢占、停止或伪造回执。

## 提交响应与恢复

```json
{"ok":true,"data":{"items":[
  {"event_id":"<event-a>","status":"SUCCEEDED","data":{"event_id":"<event-a>","state":"CONFIRMED","version":4}},
  {"event_id":"<event-b>","status":"FAILED","error":{"code":"VERSION_CONFLICT","message":"事件已更新，请刷新后逐项核对"}},
  {"event_id":"<event-c>","status":"UNKNOWN","error":{"code":"OUTCOME_UNKNOWN","message":"结果暂无法确认，请保留该项并刷新核对，勿自动重复提交"}}
],"succeeded":1,"failed":1,"unknown":1}}
```

HTTP 200 表示批量结果可读，不表示所有项成功。每项成功在返回前独立提交，后项业务失败不回滚前项。失败单项审计在该项业务事务回滚后单独记录。`SUCCEEDED` 表示原核实/申请/直接执行操作已受理；申请不表示审批通过，直接执行受理不表示设备执行完成，完成仍以真实回执为准。

若失败审计存储本身不可用，保留该项唯一结果及已知授权数据，返回 `UNKNOWN/AUDIT_UNAVAILABLE` 并写服务错误日志；不可宣称失败审计已落库，也不可自动重试业务动作。其余项继续逐项校验。

`DIRECT` 已建立授权但执行受阻时保留事务及原受阻事件，成员返回 `FAILED` + `COUNTER_EXECUTION_BLOCKED`，并保留原 `data`（包括授权 ID/状态/执行受阻原因），供进入已有授权继续核对。

`idempotency_key` 各项独立、8–128 字符，复用原 IdempotencyGuard。同一用户键重放返回 `FAILED/IDEMPOTENCY_REPLAY`，键冲突返回 `IDEMPOTENCY_KEY_REUSED`，不会再次执行动作。反制提交先校验当前事件版本，版本已变时可能先返回 VERSION_CONFLICT。没有整批键、自动重试或失败项一键换键再发；网络中断、UNKNOWN 或响应缺失时保留全部未确认项，先读取现有事件/授权结果再决定下一步。

批量接口不代替自动核实、通知、自动规则调度，不修改已有规则状态或模拟/真实来源规则，不引入新表、迁移或业务种子。

## 后端验证记录（2026-10-21）

`mvnw.cmd -Dtest=UavEventBatchApiTest,UavEventBatchPostgresApiTest,UavEventBatchServiceTest,UavEventBatchItemServiceTest test -q`：共24项，0失败、0错误、0跳过。H2接口8项，PostgreSQL 16.9/PostGIS隔离schema接口8项，聚合用例单元5项，单项委托单元3项。

覆盖部分失败独立提交、重复提交不重复核实、缺依据仅可误报、重复事件和未知字段、权限与范围、陈旧版本、同事件并发、mock/replay/live缺证据分支、UNKNOWN无重试、主体替换拒绝、REQUEST/DIRECT原权限及原请求委托、直接授权受阻保留授权、失败审计不可用保留唯一结果。测试运行保持 `app.dev-seed.enabled=false`，不发送真实通知或设备指令。此记录不代表浏览器页面或真实设备链路已经验收。
