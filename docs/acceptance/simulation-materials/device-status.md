# 独立模拟设备健康输入

协议 A 工参在线不等于健康 GOOD。本轮独立 QA 输入只提供明确标注的模拟状态事实；异常恢复、运维恢复核验、完成仍由现有接口及页面分别办理。不修改协议 A 映射，不写入工单 PASS、异常恢复或指令结果。

## 环境与权限

- 后端仅在 `local,qa` 或隔离测试环境加载；`prod`、`production` 禁用。
- 显式设置 `app.qa.device-setup.enabled=true`，验收服务保持 `app.dev-seed.enabled=false`。
- 接口为 `POST /api/v1/local-interface-simulator/device-status`，需要既有 Bearer 会话、`interfaces.op`、`devices.auth`、`monitoring.op` 及 ALL 范围。
- 只接受当前启用、来源匹配、`replay`、`simulated=true` 且有 MQTT 绑定的设备；不适用于现场设备或普通 live/mock 来源。
- 可通过原模拟器登录会话和已有 `/api/external/request` 转发，不在文件中保存口令或会话。新增白名单仅允许该精确 POST 路径。

## 请求与回读

下列为字段示例，ID 必须来自本批次实际绑定回读；`observed_at` 必须替换为当前 epoch 毫秒。不能直接提交占位值。

```json
{
  "message_id": "qa-health-unique-observation-id",
  "device_id": "本批次实际设备ID",
  "source_id": "该设备当前来源ID",
  "observed_at": 0,
  "connectivity": "ONLINE",
  "health_code": "GOOD",
  "has_alarm": false
}
```

`connectivity` 支持 ONLINE/OFFLINE/ABNORMAL/UNKNOWN；`health_code` 支持 GOOD/BAD/DEGRADED/UNKNOWN。健康与连接分别提供，不能根据在线自动填 GOOD。原始报文及哈希保存在接入记录，返回 `source_type=LOCAL_QA_STATUS`、`simulated=true`。状态界面保留“独立模拟健康输入，非协议 A 工参”来源说明。

平台拒绝超过 30 秒、未来或不晚于当前设备状态的新消息。同一 `message_id` 同内容重试返回首次回执，不刷新心跳或状态版本；同 ID 改内容返回冲突。超时或结果未知时先保留原报文并核对结果，不换消息号盲目补发。

## 运维演练顺序

1. 使用独立故障设备，保存其来源、异常和运维待办关联。按原页面开始处理、填写实际模拟处理进展、提交核验。
2. 停止该设备原工参发送，防止后续协议 A 的 UNKNOWN 健康覆盖独立 QA 事实。停止发送本身不表示恢复。
3. 明确选择本次模拟状态。例如“在线但仍 BAD/有告警”应继续阻断；恢复场景再输入 ONLINE、GOOD、无告警的新状态及心跳。每次新观测使用新消息号。
4. 在原异常页面办理异常恢复；健康上报不会替代这一步，也不自动关闭异常。
5. 在运维待办执行“恢复核验”，检查所有门槛及实际原因。仅通过后才能点击完成；完成时平台再次验证当前状态、心跳和未恢复异常等条件。
6. 若办理耗时使心跳超出原有效窗口，补充新的模拟状态观测后重新核验。不得修改窗口、沿用旧 PASS 或将只读历史标成恢复成功。

正式 API、环境门禁和隔离 PostgreSQL 并发测试与实际浏览器工单完成分别记录。接口测试通过不等于工单闭环已验收。
