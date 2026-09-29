# 设备监测事件记录

本次补齐凌云 MQTT、光电 MQTT、雷达 TCP 和四通道设备已有入口的监测事件。天气传感器仍按既有档案与外部天气流程处理，不增加设备接入协议。

## 展示与接口

- `GET /api/v1/device-events` 保留 `device_id/after_seq/limit`，新增 `latest=true`，仅在游标为 0 时取最近 N 条，再按序号升序返回。省略参数保持原增量行为。默认 100 条，最大 200 条。
- `event_seq/next_seq` 是已提交可见事件的投递顺序。历史序号保留；后续投递映射与原始事件分离，避免较低原始序号晚提交后永久漏读。客户端不能把序号当发生时间，也不能从其他设备复用游标。
- 先验证 `monitoring.read` 与组织/区域精确范围，指定不存在、删除或范围外设备返回 404，无权限返回 403。所有接入类型统一过滤；ALL 用户可见未映射设备，受限范围用户不可见。
- 页面第一次读取最新 100 条，之后每 2 秒增量刷新、按投递序号倒序展示，最多保留 100 条。日志请求与状态/运行信息/趋势相互独立；失败时保留已有日志并明确提示。切换设备重置游标，旧请求不串入新设备。

## 事件与计数

| 事件 | 含义 |
| --- | --- |
| REPORTING_STARTED | 启用本逻辑后该设备/来源模式的首条有效上报，不代表历史第一次上报 |
| CONNECTED / DISCONNECTED / RECOVERED | 按既有协议有效上报与超时规则形成的上线、离线、恢复 |
| STATE_CHANGED | 明确的工作状态变化，包括雷达模式码与四通道实际通道状态 |
| REPORT_SUMMARY | 服务器接收时间按固定 30 秒窗口聚合的心跳、工参、感知、状态报文数量 |
| REPORT_LATE_SUMMARY | 报文因排队延后处理，原接收窗口已汇总时追加的补充计数；只含本次新增数量，不重复报告完整窗口 |

凌云静态报文归工参，感知报文归感知；光电 HeartBeat 归心跳、跟踪观测归感知、成功 CameraStatus 归状态；雷达航迹/点迹归感知、RTK 归状态、寄存器归工参；四通道查询响应归状态。一条有效报文只计一个类别，零目标感知帧仍计一条。重复、冲突、过期与被拒绝报文不计入汇总，既有诊断与历史 inbox 保留。

MQTT 服务连接不表示设备上线，感知报文与相机回复不替代心跳。恢复在线不自动关闭异常单。光电使用各类观测时间水位防止旧报文回退工态，失败或重复结束跟踪回执不修改设备工作状态。

## 一致性与运行

上报业务更新、事件、窗口计数使用同一事务；同一设备按状态行、窗口行的顺序加锁。窗口计数与首次记录标记持久化，后台默认每秒处理到期窗口，每台设备单独事务；日志插入与窗口删除一起提交。重启后会继续输出未完成窗口，不需要下一条报文触发。历史日志不回填、不清理。

雷达航迹、点迹、RTK、寄存器处理以完整 inbox/业务数据/监测事件事务提交，失败后以独立诊断记录保存原因，不把已成功的原报文改成失败。寄存器与 RTK 以连接会话和协议帧号去重，同一连接重投不重复计数，重新连接可复用帧号。四通道仍只记录既有轮询回执，不增加控制动作。

新增迁移 `202609280002` 至 `202609280006` 分别建立汇总状态、光电水位、投递序号与已关闭窗口标记；不修改历史迁移。`app.device-monitor-events.enabled` 默认 true，`app.device-monitor-events.flush-millis` 默认 1000；关闭后台任务仅暂停输出汇总，已接收计数仍保留。

## 本次代码变更说明

| 文件/类 | 方法/函数 | 方法作用与修改效果 |
| --- | --- | --- |
| DeviceMonitoringEventRepository | lock、accepted、changed、workChanged、flush | 事务锁、关键事件、持久化分类计数及正常/补充汇总 |
| DeviceMonitoringEventService、DeviceMonitoringEventJob | flushClosedWindows、flush | 每秒处理已结束窗口，每设备独立提交 |
| DeviceEventPublicationRepository | publishCommitted | 串行分配投递序号，避免事务乱序提交造成漏读 |
| MqttIngressService、MqttRepository | receive、monitoringEvents、expire | 凌云有效上报分类记录，离线状态只记录一次 |
| EoEdgeIngressService、EoEdgeRepository | receive、endTracking、advanceReportWatermark、camera、expire | 光电分类计数、去重水位与心跳边界，防止旧回执回退工态 |
| ProtocolDataRepository | saveTrackBatch、savePointSummary、saveRtk、saveRadarRegisters、saveCountermeasureState、markConnection、recordInboxFailure | TCP 事件事务、真实模式变化与失败诊断 |
| LiveRadarFrameIngestService、LiveDeviceSupervisor | ingestTrack、ingestPoints、ingestRtk、ingestRegisters、handleRadarFrame | 完整帧事务与失败后诊断保存 |
| DeviceMonitorController、DeviceService、DeviceRepository | events、eventScope、eventDeviceVisible | 最新记录与增量查询、全协议范围授权 |
| MonitorView.vue | loadEvents、设备监听、事件模板 | 独立刷新、错误重试、中文级别/来源/空状态与游标隔离 |
| 5 个追加 SQL 迁移 | 无具体方法 | 持久化窗口、水位、投递映射与补充汇总标记 |
| DeviceMonitoringEventTest、DeviceMonitoringEventPostgresTest | 持久化测试 | 并发、回滚、窗口边界、重启与迟到补充 |
| DeviceEventsApiTest、DeviceEventsPostgresApiTest、DeviceMonitoringPostgresFixture | 查询测试与隔离数据库夹具 | 最新查询、权限、乱序提交及 PostgreSQL 完整迁移 |
| MqttIngressTest、EoEdgeMqttTest、TcpMonitoringEventTest 及对应 MqttIngressPostgresTest、EoEdgeMonitoringPostgresTest、TcpMonitoringPostgresTest | 接入回归 | 四类入口、失败回执、重复旧报文、连接会话去重，在 H2 与 PostgreSQL 验证 |
| monitorInformation.test.js | 页面回归 | 首次最近记录、增量与空结果游标、局部失败、设备切换及暂停恢复 |

## 验证记录（2026-09-28）

- Java 17：12 个相关测试类随 `package` 执行，107 项通过；追加 `TcpMonitoringPostgresTest` 的 6 项通过。合计 113 项，失败 0、错误 0、跳过 0，包括 `AuthApiTest`。未宣称整个后端测试全集通过。
- PostgreSQL/PostGIS：使用专用 `stage456_verify_monitor0928` 数据库，每组测试创建独立随机 schema 并清理，完整执行 131 个迁移；验证窗口并发锁、回滚、重启恢复、迟到补充、提交乱序投递、权限过滤、光电水位以及四类接入。未向现有业务库手工插入事件。
- 真实网络测试使用隔离 Moquette MQTT broker，覆盖重投、断线重连及接收服务重建。Windows Java 的 Unix-domain socket 路径使用 ASCII 临时目录；未禁用网络验证。
- 因同仓存在并行构建，本次 Maven 使用临时 POM 指向原项目源码/资源并隔离输出目录，未修改生产 POM 或依赖。运行参数为 `-DargLine="-Djdk.net.unixdomain.tmpdir=E:/houtaiguanlii/server/target/monitor-sockets -Dio.netty.eventLoopThreads=2"`，测试环境变量为 `POSTGRES_TEST_URL/USER/PASSWORD`，凭据不写入文档。
- 前端执行 `npm run lint`、`npm test` 和 `npm run build`，129 项测试全部通过、跳过 0；页面测试覆盖日志请求独立于其他请求、失败保留、旧请求丢弃、最近 100 条及暂停恢复。
- 服务核验：前端在 12:31 重启，后端使用本次打包产物于 12:32 重启，保留既有 `local,qa` 配置。5175、8081 均监听，监测页面与后端健康接口返回 200；后端自动追加迁移至 `202609280006`。
- 浏览器使用已有授权会话与已有测试设备，实际看见开始接收、上线、固定窗口汇总、离线事件以及中文级别和模拟标识；首次 `latest=true`、后续 `after_seq` 返回 200。页面验证复用现有接入产生的记录，不造历史记录。
- 验证边界：四类协议用隔离测试夹具验证；未接入四种实体硬件完成现场验收。没有新上报的设备仍可能没有新监测记录。
