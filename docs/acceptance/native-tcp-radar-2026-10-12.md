# 独立 TCP 模拟雷达验证记录（2026-10-12）

## 自动验证

- 后端在 `artifacts/tcp-radar-verify/server` 隔离代码副本执行受影响测试和 `package`，最终 60 项通过，0 失败/错误/跳过。测试类：LocalQaRadarApiTest、LocalQaRadarPostgresTest、LocalQaDeviceGateTest、TcpMonitoringEventTest、LiveRadarSourceObservationPortTest、LiveRadarPromotionTest、LiveRadarPromotionEnabledTest、AuthApiTest、DeviceProtocolApiTest。
- PostgreSQL/PostGIS 使用既有专用 `plan_filing_verify_item1` 库的随机 `qa_radar_*` schema，12 项通过。包含同一设备并发首次登记，只产生一份设备和一次成功登记审计；RTK 不同连接复用帧号、新连接首次帧与历史样本帧号相同均正常写入，同一连接重复帧跳过且历史不变。覆盖帧号 1 和 4294967295；测试结束删除该随机 schema。
- 迁移最终编号使用 `202610129002`，位于已应用的实时通知迁移之后；按最终编号再执行 12 项 PostgreSQL 回归通过。当前工作区另执行 `package -DskipTests` 成功；受影响测试以隔离副本的 60 项及这次迁移复验为准，不将工作区打包描述为全量测试。
- 模拟器：`python -m unittest test_external test_realtime_control test_device_health test_tcp_radar`（PYTHONPATH 指向 tests）53 项通过。TCP 实际套接字测试 8 项，覆盖 CRC/长度、拆包与粘包、数据登录、寄存器、RTK/航迹、重复启动、端口占用、重连、重启新会话和登录失效停发。
- 新增页面 JavaScript `node --check` 通过；页面直接由现有 Python 静态服务提供，本次未修改两个 Vue 应用，因此不重复其全量构建。
- 额外检查的既有 `test_runtime_recovery.py` 为 4 通过、1 失败：原测试仍断言 `/auth/me`，现有运行实现已经使用 `/local-interface-simulator/session`，本轮未修改该鉴权方法，未将这项记作通过。
- 首次沙盒内 Java 测试被 Windows 拒绝 Mockito attach，终止已核对身份的隔离测试 JVM 后，在允许测试代理的环境重跑，结果以上述成功 `package` 为准。

## 运行与浏览器

用户确认“现在加载并验收”后，通过原模拟器停止入口停止场景，重启归属已核对的 API 与模拟器；复用前台、后台、数据库和 MQTT 服务。启动参数明确 `--app.dev-seed.enabled=false`。

浏览器使用用户已登录的模拟器完成登记，设备名“模拟 独立 TCP 雷达”，编号 `QA-LOCAL-RADAR-TCP`，ID `207ae54f-3f71-4f3a-91ce-2fc95b885405`。来源使用 `RADAR_TCP_V3_0_0`，设备和来源的 simulated 均为 true。原有 7 台 `LINGYUN_MQTT_V8_6` 雷达保留，新协议只有 1 台设备。

首次实际接收累计 773 条 RTK，CRC 错误数为 0。页面主动停止后，平台转为 OFFLINE，连续两次只读查询航迹点数均为 895，没有继续写入。重新启动复用同一设备。验收中还遇到平台会话校验接口连接失败，模拟器停止发送并展示实际错误；再次显式启动可恢复。

重启验收发现原生接收端的 RTK 存储仍按“设备 + 线帧号”唯一，跨连接帧号重复触发 PostgreSQL 事务中止。追加迁移及修复按连接报文键去重，历史线帧号保持原样。修复前的 125 条失败诊断保留，不删除或修改成成功。

最终版本实际迁移成功，历史 773 条 RTK 原样保留。新会话已写入 113 条 RTK 后，页面主动停止，连接状态与平台状态均变为 OFFLINE。再次启动复用同一设备，实际查询至少 72 个相同线帧号分属不同新连接并正常存储；此时平台为 ONLINE / LOGGED_IN，CRC 错误为 0，PROCESSED 报文累计 2163，FAILED 仍为原先的 125，没有新增处理失败。随后页面继续发送到 228 帧（北京时间 16:41:08），截图为 `artifacts/tcp-radar-verify/tcp-radar-online.png`。

中途另一次启动遇到后台开发服务热重启（后台日志存在 restartedMain），会话校验接口不可达时发送中止；服务就绪后显式启动成功。后端或模拟器重新加载后应检查该页面状态，需要时再启动，不把开发热重启期间的停发写成持续在线。本次结束时 TCP 页面正在发送，原 MQTT 场景在经用户批准加载服务时停止，未自动重新开启整套业务场景。

管理端新验收标签页未登录，本轮以模拟器实际按钮和平台数据库只读接收结果为依据，不把后台页面视觉检查记作通过。模拟验证不代表真实雷达接入、现场探测能力、坐标标定或健康恢复已验收。
