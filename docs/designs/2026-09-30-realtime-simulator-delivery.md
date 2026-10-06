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

## 2026-09-30 09:57 部署续接实测

- 当前工作区实际为管理仓库 `main`、模拟器仓库 `E:/rwurenji/_rong` 的 `codex/realtime-simulator`。未切换分支，未恢复、删除或覆盖既有源码修改。上文分支与 PID 是历史记录。
- 本次会话执行权限为 unrestricted / approval never。当前 PowerShell 7.6.5 的有效策略为 RemoteSigned，无 MachinePolicy/UserPolicy。首次调用 Windows PowerShell 5 启动脚本被其脚本策略拒绝；随后使用当前 PowerShell 7 的 RemoteSigned 正常执行原脚本，未修改策略、未使用 Bypass，也未出现本轮自动审批拒绝。
- 重启前实测 8081 为 PID 47096，8766 为 PID 37396。先备份旧包至 `server/target/qa-runtime/realtime-simulator.before-20260930-095611.jar`，停止已核对身份的后端进程，再将现有构建包复制到运行包。原启动脚本及数据库、媒体、录音、local/qa 配置保持原样。
- 后端新 PID 60292，09:57:24 完成启动；`http://127.0.0.1:8081/actuator/health` 返回 HTTP 200、UP。构建包和运行包 SHA-256 均为 `4313DCB4E357463D0D5B9AEA64C6261CBA5544088DE3AE694BC6F7EE92EDBB1F`。旧包 SHA-256 为 `AA986D918D868FF5A5C4FEA29BEB7BA4A8DCDA63579294A8361D71BF58CCF266`。
- 模拟器新 PID 36548，沿用 `server.py --port 8766` 和默认 `.data`；首页 HTTP 200，状态接口返回 `scene_mode=realtime` 和新版 `realtime` 对象。8767/PID 27872、18091/PID 41944 保持原样。后台进程均隐藏窗口，日志使用新的时间戳文件。
- 运行证据：`server/target/qa-runtime/realtime-deployment-evidence-20260930-0957.json`；后端日志 `realtime-deploy-20260930-095714.out.log`，模拟器日志 `simulator-20260930-095627.out.log`（均在同一 qa-runtime 目录）。这些是本地运行产物，不提交。
- 当前阻塞是重启清除了模拟器内存会话，`connected=false`；已请用户在 8766 页面重新登录本机 8081，不要求将密码发到聊天。未绕过认证、未直接插库、未补造收发结果。
- **五分钟实际收发验收尚未开始，不能据 HTTP 200 宣称通过。** 下一步：用户登录后，通过既有资料 API 选取模拟计划，启用正常连续收发；以 MQTT PUBACK 对应的 `sent`、平台设备接收时间、目标 `last_seen_at`、TCP/通知计数进行至少 300 秒前后采样，结束后停止全部收发。六类真实业务通知、光电/反制动作、关页持续运行及断线恢复仍需分别记录实测证据。

## 2026-09-30 全类型关联场景实施与待部署状态

本节承接用户批准的“全类型、完整关联数据模拟方案”。当前为代码和定向验证完成、最终部署与实际验收受阻；不可把前述历史运行的 MQTT 数量当成本次新增场景的证据。

### 已实现的输入与读取

- 8766 新增“配置全量场景”“完整资料与接口字段”“一键启动全量场景”和逐类回读覆盖。12 类开关，默认持续运行；保留原草稿备份、旧正常/异常模式，新增混合通知模式。正常/偏航/禁飞/超高/超时/无计划/通知后撤离/停报恢复各用独立目标；单鸟、5只鸟群、未知、识别中、气球、人员、车辆、船舶和遥控器保持类别独立。
- 连续航点、升降、停留、往返、闭合巡航使用同一运动采样产生位置、速度和航向。正常目标无需风险行也上报；停报使用真实时效，不自动视为飞离。MQTT 继续使用已有协议码，气球为规范化 UNKNOWN + BALLOON。
- 配套顺序为授权资料准备→连续观测→平台回读。无人机计划和观测共用批次序列号；鸟/气球邻近航线使用独立配套无人机计划，不把鸟/气球本体登记成无人机。空域发布五类，并对本批临时区域在120秒更新、180秒撤销。
- 新增航线、规范化观测源和目标观测的模拟接口；观测通过 inbox 原始载荷、source_observation、融合和轨迹链。保持模拟环境门禁、Bearer 会话、操作权限、组织/区域、来源身份与消息幂等；消费时重新校验设备/来源和冻结范围。不同消息编号提交同一来源/目标/时间的样本返回冲突。
- 独立模拟气象设备每5秒提交8项实测指标；预报和强风/雷暴/低能见度预警分别调用既有接口。新增最新观测和历史分页接口，以及管理端设备详情的实测/趋势展示。15秒后过期状态在详情、列表、筛选、总览和分组一致，不启用真实 WEATHER_PENDING 设备。
- 目标默认1秒、设备心跳2秒、气象5秒；后台线程不依赖页面保持打开。通知支持成功、失败、延迟、不回执和电话仅接通，结果按消息冻结。人工核实、直接反制、审批、急停、短信3秒/电话播放后10秒约束保持。

### 代码变更说明

以下仅列本次全量扩展涉及的代码，工作区其他既有改动未回退、未提交。

| 改动文件/类 | 改动方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| 模拟器 `full_scenario.py`、`fullchain.py` | `full_scene/allocate_identities/prepare/request/tick/publish/record_mqtt` | 生成输入、关联资料和发布 | 全类型草稿、稳定身份、接口资料准备、规范化目标与气象上报、空域版本变化、独立接入/处理计数 |
| `engine.py`、`web/app.js` | `compile_scene/target_motion/target_sample/target_reporting/messages`、目标编辑 | 校验和运动计算 | 无风险目标上报、循环/往返/停留/升降、真实停报恢复、协议类别与高度基准明确 |
| `server.py`、`realtime_control.py` | `Runtime._start/run/verify/status`、`prepare_scene/start`、Handler | 生命周期、代理、平台回读 | 全量准备融入真实收发、白名单、分页回读轨迹与类别观测、气象回读、停止全部 |
| `realtime_notification_receiver.py` | `receive/next_outcome` | 接收和回执 | 延迟及混合模式按消息冻结，不重放未知受控指令 |
| `web/fullchain.js`、`web/realtime.js`、`web/runtime.js`、`web/index.html` | `load/edit/adopt/update/settings/applyRuntime`；入口模板无具体方法 | 运行和编辑页面 | 分类开关、现有飞手/来源绑定选择、完整JSON资料、关联ID/时间/接入确认与处理回读分别展示 |
| `NormalizedSimulatorController/Dtos/Service/Repository` | `route/device/observations`、查询及持久化方法 | 授权规范化模拟接入 | 航线/来源登记，消息与采样双重幂等，范围与时间检查 |
| `NormalizedSimulatorFrameMapper`、`FusionConfigLoader`、`FusionPipeline`、`DefaultFusedLayerWriter`、`AttributeSelector`、`IdentityRepository`、`ObservationRepository` | `map/heading`、配置加载、消费/融合/属性优选/血缘方法 | 真实融合处理链 | 规范化来源的 replay 参数、明确子类型、停用/归属变更复核、航向精度归一 |
| `WeatherObservationController/Dtos/Service/Repository`、`LocalWeatherObservationController`、`DeviceRepository` | `register/receive/latest/history`、设备读取查询 | 气象接入、历史、设备有效状态 | 独立模拟来源、实测持久化、精度和幂等守卫、统一15秒时效查询 |
| `ruoyi-ui/src/api/weatherObservations.js`、`WeatherObservationPanel.vue`、`DeviceCatalogPreview.vue` | `latest/history/load`、组件监听 | 管理端读取与详情 | 8指标、观测/接收时间、过期标识、历史趋势、5秒刷新 |
| `V202609300001/0002/0003` | 无具体方法 | 追加数据库结构 | 规范化模拟输入、气象观测、同目标同时刻采样唯一键；已应用脚本不改写 |
| Python/Node/H2/PG/Vue新增与扩展测试；`tests/run_fullchain_acceptance.py` | 用例、`main` | 自动验证和显式实际验收 | 连续运动、非无人机身份、接口权限/来源/时间/幂等、分页回读、气象过期/精度、实际300秒以上逐分钟采样并停止 |
| `scripts/restart-full-simulator.ps1` | 无独立函数；`ShouldProcess` 部署步骤 | 人工可审阅重启 | 核对8081/8766身份、拒绝活动批次、备份旧jar、沿用启动脚本与数据目录、隐藏窗口启动、健康检查；不修改执行策略 |

完整字段表：`E:/rwurenji/_rong/tools/device-simulator/docs/全类型字段覆盖表-2026-09-30.md`。气象接口：`docs/designs/2026-09-30-weather-observation-contract.md`。

### 本轮验证证据

- 模拟器 Python：158项通过；Node：48项通过。`python-final-tests.log`、`node-final-tests.log` 在模拟器目录，属本地运行产物。
- 后端最终 Java17 定向融合、API、身份、天气、模拟接入及PostgreSQL/PostGIS：118项通过，0失败/错误/跳过；真实PG测试使用 `stage456_verify_realtime_0930` 内的隔离夹具，三份新增迁移均应用验证。日志 `server/target/full-simulator-final-tests.log`。测试夹具的合成seed仅在隔离测试schema，运行服务的dev-seed仍关闭。
- 后端 `mvnw -DskipTests package` 成功，日志 `server/target/full-simulator-package.log`。构建SHA256：`85F6A9B4AA48731AEF3D5F7EE56AA9B5B04A27669A0E0817577FB7A05A3B7922`。
- 管理前端 lint、32个测试文件177项、build已通过。PowerShell手动部署脚本只做语法解析通过，未执行它。
- 页面实际刷新确认全量入口已显示。最后一轮代码尚未全部加载到运行进程，因此界面入口存在不等于运行验收通过。

### 当前阻塞、影响与下一步

- 8081仍为PID60292，运行jar SHA256 `4313DCB4E357463D0D5B9AEA64C6261CBA5544088DE3AE694BC6F7EE92EDBB1F`，与全量新构建包不同。新后端接口尚未部署。
- 8766已于10:32成功重启为PID41308；之后新增最后的轨迹分页回读修正。10:38左右再次核对并重启8766的命令被自动审批以 `blocked by policy` 拒绝，未执行。未改换工具、执行策略或等效命令绕过；当前 `connected=false`。
- 已提供人工重启脚本 `scripts/restart-full-simulator.ps1` 并请用户在PowerShell7审阅后手动执行，再登录8766。该脚本不读取或输出密码；只处理这两个端口，保留默认 `.data` 和旧jar，8767/18091不在脚本操作范围。
- 登录后执行 `python tests/run_fullchain_acceptance.py --output E:/houtaiguanlii/server/target/qa-runtime/fullchain-acceptance.json`（cwd为模拟器目录）或由后续会话执行等效验收。运行至少305秒，每分钟记录PUBACK、平台设备/目标时刻、类别观测、轨迹、天气、TCP、通知；最终停止本批次全部收发。需要现有飞手和来源绑定时先保存完整场景，通过 `--scene` 传入。
- **五分钟实际收发、关页持续、断线/会话失效/服务重启的实际演练，以及六类真实业务通知、光电和反制均尚未验收。** 相应单元测试不代替实际结果。当前光电媒体配置未启用，不能标记可播放。通知缺失核实/接收主体/授权时只列阻塞，不补造审批、处罚决定或执行结果。
- 实施中一次误用 `mvn clean` 在遇到运行文件锁失败前已移除 `server/target/acceptance-baseline` 构建目录。已核实运行jar及备份、默认模拟历史目录和业务数据库保留；该目录旧内容未逐项恢复，不声称“完全无删除”。已向用户披露，后续构建均未使用clean。
