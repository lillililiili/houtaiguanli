# 全链路设备模拟器实施与验收记录

日期：2026-09-30。实际代码位于 `E:/rwurenji/_rong/tools/device-simulator` 和本仓库 `server`，两仓库工作分支均为 `codex/realtime-simulator`；保留此前所有工作区修改，未提交、未删除历史数据。

## 实施结果

| 部分 | 改动文件/类及方法 | 作用 |
| --- | --- | --- |
| 统一控制 | `realtime_control.py`：`validate_config/prepare_scene/RealtimeController.configure/start/stop/snapshot` | 管理正常/异常模式、组件启停、实例锁、租约及统计；启动、停止、配置按同一生命周期锁串行 |
| 场景集成 | `server.py`：`Runtime.start/stop_all/status/check_session/run`、`Handler.do_GET/do_POST`、`ExternalBridge` | 接入本地控制接口；实际 MQTT 连接与最近上报可观察；旧草稿另存，禁止实时模式走数据库 seed |
| 四通道 | `countermeasure_tcp.py`：`CountermeasureSimulator.start/stop/snapshot`、协议读取和校验 | 回环 TCP 模拟查询/开关/状态、失败模式和严格帧校验，不产生真实射频动作 |
| 通知与保活 | `realtime_notification_receiver.py`：`receive/next_outcome/step`；`device_presence.py`：`step` | 每消息冻结结果模式与播放时长；统一控制接管后旧保活不会恢复旧设备 |
| 地图控制界面 | `web/realtime.js`、`web/realtime.css`、`web/index.html`、`web/runtime.js` | 正常/异常表单、六类通道配置、TCP/通知/上报状态、明确停止全部；时长显示与原始草稿一致 |
| 六类收件箱 | `notification_inbox.py`：`read_inbox`；`web/external.js/html` | 增加计划反馈和运维通知，复用权威业务历史及分页；不把签收当设备恢复 |
| 启动辅助 | `start-realtime-workers.ps1` | 调用统一控制 API，取消并行启动旧保活进程的行为 |
| 后端登记 | `LocalQaDeviceService.prepare/matches`、`LocalQaDeviceRepository.existing` | 重复准备同范围同连接的 QA 设备返回既有设备；配置、来源或范围不符返回冲突，不授予权限 |
| 后端配置 | `application-qa.yml`（无具体方法） | local+qa 启用自动短信、语音和模拟设备准备，继续校验所有既有业务资格 |

模拟器新增测试：`test_countermeasure_tcp.py`、`test_realtime_control.py`、`test_notification_inbox.py`、`test_realtime_ui.cjs`、`test_inbox_categories.cjs`。后端扩展 `LocalQaDeviceApiTest`。文档更新：模拟器 README、后端 README 和本地续测说明。

## 已验证

- 模拟器目录：`python -m unittest discover -s tests -q`，132 项通过，0 失败。既有 HTTPError ResourceWarning 不影响测试结果。
- 模拟器目录：`node --test tests/*.cjs`，43 项通过，0 失败；修改的 JavaScript 语法检查通过。
- 四通道通过真实 socket 验证二进制/ASCII、分片、重复、无回执、坏帧、端口冲突和停机重启。
- 生命周期验证：通知处理方式跨重启冻结；禁用通知后不复用旧 Receiver；并发启动/停止串行；未关联计划时不遗留组件；登录失效停止收发。
- 后端定向 H2 两组：71 项通过；74 项通过并有 5 项 PostgreSQL 门禁跳过。隔离 PostgreSQL/PostGIS 两组：49 项与 6 项通过。以上不同命令可能覆盖相同类，不将数量相加冒充去重用例数。
- 后端 `mvnw -DskipTests package` 已通过；构建产物为 `server/target/low-altitude-server-0.1.0-SNAPSHOT.jar`。
- 实际页面检查确认新入口可显示，旧 Python 进程缺少新版接口时提示尚未加载实时收发模块。旧进程不能因此被宣称已启用。

## 未完成的运行验收

自动审批拒绝了备份运行包并停止、重启现有 8081/8766 的组合命令，原因 `blocked by policy`。命令未执行，未改用其他工具或命令绕过。

拒绝后只读核实：后端仍为 PID 30804，模拟器仍为 PID 37396。二者是旧进程；新构建包尚未部署到运行包，Python 新逻辑尚未加载。8767/18091 保持原样。

因此以下项仍未验收：新进程至少五分钟连续上报与平台实际观测更新、关闭页面后继续收发、六类真实平台请求的逐类回执、真实平台光电/反制联动及断线恢复。测试通过不代替这些证据。

## 用户重启后续验步骤

1. 后端须加载新构建包，同时保留现有 `local,qa` profile、DB_URL、录音与媒体配置；现有运行包为 `server/target/qa-runtime/realtime-simulator.jar`，启动配置位于同目录 `start-realtime-simulator.ps1`。当前运行包不可在 Java 仍使用时覆盖。保留旧包备份。
2. 模拟器使用 `E:/rwurenji/_rong/tools/device-simulator/server.py --port 8766`，保持默认数据目录；重新登录本机 8081，不提供密码给日志或聊天。
3. 在“正常／异常设置”选择已有模拟计划作为反制设备归属；若无计划经资料输入提交，不能直接插数据库。正常模式默认持续运行。
4. 启动后记录 MQTT 确认数量、平台设备最近接收时刻、目标 last_seen_at、TCP 与通知接收数量，在至少五分钟后复核。测试结束停止全部收发，保留生成的历史记录。
5. 按原授权分别验证光电/四通道动作和停止核查；六类通知由真实业务请求触发，禁止绕过核实、联系人、范围及反制授权补造结果。
