# 第二条实施与验收记录（2026-10-06）

**状态：阶段性交付，第二条尚未达到全部完成条件。** 本报告只记录已实际执行的检查，不以自动化测试替代未完成的业务浏览器闭环。

后续三条链的实际批次、修复、权限补齐和最新测试结果见 [闭环续测记录](closure-progress.md)。下文保留当时测试事实，历史阻塞以续测记录为准。

上级飞行计划及飞手接口尚无文档或脱敏样例。按用户确认，本轮先验证模拟链路；未编造上级接口结构、真实飞手核验、审批人或处罚办结。当前合法性参数仍为 `DEMO`，该属性原样保留。

## 范围补正：三条业务链与上级同步（2026-10-06 用户澄清）

前一版第二条计划只明确列出了无人机处置和设备运维两条业务链，遗漏了系统已有的飞行风险通知链。完整验收范围应包含：

1. 无人机处置：目标 → 合法性研判 → 告警 → 飞手通知与位置观察 → 授权处置 → 回执 → 处罚交接。
2. 设备运维：异常 → 内部运维待办 → 处理 → 恢复核验 → 完成。
3. 飞行风险：鸟类等目标观测或有效气象风险依据 → 与计划/航线的空间、时间等关系判断 → 全部风险事件 → 核验 → 通知上级 → 送达、签收及处理结果回读。风险通知与无人机处罚交接使用不同业务类型；提交、送达或签收不自动证明风险解除。

现有 `FlightsPage.vue` 的“全部风险事件”已调用 `RISK_NOTICE`，后端接收方统一为“上级”。本轮应复用该链路，不能新增平行流程。天气预报与经过确认的气象风险依据分别处理，不能仅凭“雷雨”等文字生成已确认风险。

飞行计划及其中的飞手信息、空域资料均按用户明确的上级同步来源处理。空域已有 `POST /api/v1/integrations/airspaces/messages` 接收入口及本地授权模拟入口，现有逻辑校验来源、专用接收账号、消息幂等、递增版本、生效时间、更新和撤销。具备本地入口不代表甲方真实接口已经联通；本轮仍使用明确标识的模拟输入，不另建一套人工业务主数据。

光电跟踪和视频是上述相关流程复用的能力，单独测试，但不因此替代第三条业务链。

**新增到验收清单、尚未完成的条目：**

| 编号 | 必测内容 | 当前状态 |
| --- | --- | --- |
| R01 | 鸟类目标与航线/计划关联，核验后通知上级，回读送达与处理结果 | 实际生成 BIRD 目标；空间风险规则无生效版本，尚未生成对应风险事件 |
| R02 | 有效气象风险与计划时段/范围关联，核验、通知上级和结果回读；普通预报不冒充已确认风险 | 实际接入三条独立模拟气象风险并关联计划；通知上级通道尚未配置 |
| R03 | 通道未接通、失败、超时、重复及错误关联回执不推进其他风险 | 已有部分共享交接回归，未完成本链逐项验收 |
| R04 | 上级空域新增、更新、撤销、重复及旧版本；生效后页面和判断依据一致 | 本机实际 API 新增、更新、撤销、重复及旧版本检查通过，PostGIS 9 项通过；页面最终核对未完成 |

第三条通知的接收主体是上级，其资格与通道应独立检查，不把“缺执行飞手”笼统列为本链的阻断条件。此范围补正不改变既有规则，也不把新增条目预先计为通过。

### 第三链续测记录

本次追加自动化共 157 项执行通过，失败 0、错误 0、跳过 0（按运行计数，不与此前结果相加声称唯一用例数）。命令、日志哈希和隔离库见 [test-results.json](test-results.json)。

| 组 | 执行数 | 环境 |
| --- | --- | --- |
| 风险读取、核验、权限与接口门禁 | 65 | test/H2 |
| 风险排除空间依据、空域读取、独立接收器 | 42 | `stage456_verify_risk_20261006` / PostGIS |
| 上级空域版本、幂等、来源与权限 | 9 | `airspace_verify_risk_20261006` / PostGIS |
| 独立气象风险输入、时效及重复 | 6 | `weather_risk_verify_risk_20261006` / PostGIS |
| 通知准备及资料资格，含新增“上级通知不制造飞手核验”用例 | 35 | `plan_filing_verify_risk_20261006` / PostGIS，修正测试后完整复测 |

- 新增三个可导入模板，见 [risk-scenes/README.md](risk-scenes/README.md)。标准化鸟类、协议 A 鸟类、独立气象预警分别准备，均通过现有场景校验，不下发反制指令。
- 鸟类批次 `sim-1006030813-b317`：三路标准化观测进入真实平台，目标 `2b51955d-8dca-4843-9249-daa657edbe1d` 的类别为 `BIRD`，没有无人机 SN。配套计划为 `2e806154-3e92-4bc2-a6a7-7a4e0c836d74`。当前 `SPACE-RISK-DEMO.active_version_id` 为空，不能把目标存在当风险研判完成。
- 气象批次 `sim-1006031001-d2fa`：通过既有 QA 预警接口生成大风、雷暴、低能见度三条 `mock` 风险，关联计划 `73d356c1-1c16-4561-87db-9fb3fd6700cb`；普通预报内容为“模拟多云”，风险来自独立预警输入。已从平台现有上下文读回风险-1006-001/002/003。
- 空域 `risk-airspace-cc0d1ffb5d69`：实际通过授权模拟 API 新增 revision 1、更新 revision 2、撤销 revision 3；相同消息返回原结果，改内容复用消息号及旧版本均被后端 409 拒绝。回读为 revision 3 / WITHDRAW。测试只处理这个新增模拟空域。
- 气象同消息重发返回相同风险 ID；改严重程度后复用消息号被 409 拒绝。上述空域与气象实际 HTTP 检查合计 9 项。现有模拟器代理将下游 409 展示为自身 HTTP 400，并保留后端 409 原因；测试按实际契约检查。
- 验收库只读诊断确认 `risk-superior` 为 `NONE / enabled=false`，合法性规则启用不代表空间风险规则或通知通道已启用。诊断未修改业务表。
- 业务前台会话已失效，已请求通过现有登录窗口重新登录；核验、通知、送达、签收及处理结果浏览器环节仍未完成。没有主动制造通知回执。
- 本次结束时后端健康 `UP`，模拟器保持登录并已停止场景和全部收发；恢复运行前的收发配置，未启动新的后台模拟任务。验收种子仍关闭。

续测中的初始问题单独记录：沙箱内首次编译不能访问 Maven 缓存、Docker 启动失败，改为授权运行相同隔离测试；新增测试首次把读取时生成的 `captured_at` 当成持久资料比较，修正为只忽略该动态时间后复测。实际 HTTP 脚本首次期待错误码字段，而现有代理只返回含 409 的错误说明；核对原请求与回读后复用原消息继续，没有创建替代成功记录。

## 1. 本轮变更

代码位于前台仓库的 `tools/device-simulator/`，本轮没有修改后端业务规则和业务页面。

| 文件 | 修复/资料 | 验证 |
| --- | --- | --- |
| `notification_response.py` | 通知后运动必须核对本批次来源关联、当前观测、唯一目标和身份；标准化观测源不作为运维设备查询；缓存的目标也再次校验；MQTT 使用真实报文 objectId 关联 | `test_item2_response.py`，旧实现失败、修复后通过 |
| `full_scenario.py` | 在批次清单中保存既有 MQTT objectId，不改变线上的协议字段 | MQTT 关联回归 |
| `engine.py`、`fullchain.py` | 场景可声明 1—3 路独立标准化模拟观测源；默认 1 路保持旧场景兼容。各源通过现有授权接口注册及输入，同一时刻的事实一致、消息 ID 独立 | `test_item2_sources.py`；实际三源融合回读 |
| `fullchain.py` | 相同空域重复运行先回读，仅在已受理且 ID、版本、动作及完整定义一致时复用；改变范围、归属、高度或有效期仍按原规则提交 | 先复现 409；单测及两次完整重跑复用同一第 1 版 |
| `build_item2_samples.py`、`scenarios/item2/` | 14 份场景，六份既有通知接收端配置；运维模板含独立未关联目标的检查计划 | 编译、导入导出往返、现有配置契约测试 |
| `tests/test_item2_*.py` | 正式行为回归：跨批次、错误来源、过期/未来观测、身份冲突、多源输入、资料复用等 | 已并入 Python 全套测试 |

三源输入用于明确标识的业务模拟组，并未降低置信度阈值；不能作为协议 A 提供可信高度、SN 或真实多设备测量的证明。稳定 SN 可跨批次复用，运动关联必须同时核对本批次来源和当前观测，不能仅凭 SN。

## 2. 环境与基线

- 管理仓库 HEAD：`7510b2ecc717448acb98b5228c8475d9e8f4d730`。
- 业务仓库 HEAD：`1aaee712e040409600a5f2bed7cb7bde975df55c`。
- 两仓库均有先前本地改动，已保留；上述 HEAD 不代表当前工作区全部内容。本轮没有提交、覆盖或撤销协作者改动。
- Java 17、Spring Boot；测试在 `server/target/item2-build/server` 隔离构建副本运行，避免运行中服务热加载未完成编译的文件。
- 验收后端 `8081`，管理端 `5175`，业务前台 `5173`，模拟器 `8766`；本机 MQTT `1883`。
- 验收服务 `local,qa`，`app.dev-seed.enabled=false`；自动光电总开关保持关闭，实际浏览器验证使用人工补跟踪。
- PostgreSQL/PostGIS 容器为既有 `deploy-db-1`，端口 `5432`。测试只使用下表所列隔离库及独立 schema；未直接写验收业务表。
- 固定要求 `25432` 的两个通知测试临时使用仅绑定 `127.0.0.1` 的转发，连接到同一隔离测试库。测试完成后已停止转发，没有修改测试保护条件。
- MediaMTX `v1.21.1`，FFmpeg `9.0.2`；复用既有 QA 媒体配置，凭据只放忽略的本机文件中，没有进入报告或场景。

## 3. 实际自动化结果

表中是各次运行的实际数量，存在不同环境重复执行的同一用例，**不可直接相加为唯一用例总数**。

| 测试组 | 执行总数 | 失败 | 错误 | 跳过 | 说明 |
| --- | ---: | ---: | ---: | ---: | --- |
| 后端 H2/单元基线 | 137 | 0 | 0 | 1 | PostGIS 位置观察缺环境；随后在 advisory 组实际通过 |
| stage456 PostgreSQL 组 | 230 | 0 | 0 | 14 | 12 项移送通知因库名前缀不匹配；随后独立补跑通过；另 2 项为需要显式启动的浏览器驻留夹具 |
| advisory PostgreSQL | 87 | 0 | 0 | 0 | 短信、电话、位置观察、视频租约及目标视频 |
| maintenance_flow PostgreSQL | 42 | 0 | 0 | 0 | 恢复核验、旧 PASS、并发及版本等 |
| maintenance_notice PostgreSQL | 14 | 0 | 0 | 0 | 内部消息、已读、通知去重等 |
| handoff_notice PostgreSQL 补跑 | 12 | 0 | 0 | 0 | 消除 stage 组库名前缀导致的 12 项跳过 |
| 控制/媒体专项 | 45 | 0 | 0 | 0 | 凌云控制、TCP、视频登记/媒体客户端、停止生命周期、EO 信封与报告映射 |
| 接口专项补充 | 136 | 0 | 0 | 0 | 光电人工跟踪、MQTT、QA 视频、直接授权、短信调度、录音、移送材料与回执 |
| 实时通知计时 PostgreSQL | 3 | 0 | 0 | 0 | 实际墙钟 3 秒/10 秒、PostGIS 位置观察、未核验飞手阻断、回执关联 |
| 模拟器 Python（最终实际目录） | 231 | 0 | 0 | 0 | 最终修复部署后完整运行 |
| 模拟器 Node | 69 | 0 | 0 | 0 | 既有页面与契约 |
| 管理端 Vitest | 178 | 0 | 0 | 0 | 31 个测试文件 |
| 业务前台 Node | 61 | 0 | 0 | 0 | 相关契约 |
| 业务前台 Playwright 指令详情 | 2 | 0 | 0 | 0 | 使用已安装 Chrome；仅该场景，不代表全套 E2E |

后端打包、管理端构建、业务前台构建均成功。前台有既有 chunk 大小提示；未为了消除提示修改无关页面。

未隐藏的初始失败：通知目标关联回归在旧实现失败；空域重复准备曾返回 409；首次 Playwright 缺安装的 Chromium，改用已有 Chrome 后两项通过。两个 `serveEmergencyStopLateReplyBrowser` / `serveOrdinaryLateReplyBrowser` 驻留夹具没有启动，其跳过不计作浏览器验收通过。

### 实际命令和隔离库

共同 Maven 前缀（工作目录仅 `server/`，本轮使用上述隔离副本）：

```powershell
.\mvnw.cmd -Dmaven.repo.local=C:/Users/19221/.m2/repository -o "-Dtest=<下列测试类>" test
.\mvnw.cmd -Dmaven.repo.local=C:/Users/19221/.m2/repository -o -DskipTests package
```

`POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:5432/<库名>`，用户名/密码沿用本机测试配置，不写入资料包。

| 组 | 库名或环境 | `-Dtest` |
| --- | --- | --- |
| 基线 | test/H2 | `AutoSmsApiTest,AutoVoiceApiTest,PostgisPilotDepartureWatchTest,UavDepartureObservationTest,DecisionAssuranceAlgorithmTest,EoTrackingCommandSafetyTest,EoTrackingPolicyTest,DeviceMaintenanceWorkflowApiTest,DisposalExecutionTest,DisposalReceiptSyncTest` |
| stage | `stage456_verify_item2_20261006` | `DisposalMqttFlowPostgresTest,DisposalJammingFailurePostgresTest,DirectDisposalBackgroundPostgresTest,EoTrackingPostgresTest,EoEdgeMonitoringPostgresTest,HandoffAutomaticMatrixPostgresTest,HandoffNotificationPostgresTest,DeviceMonitoringEventPostgresTest,CommissionScopePostgresApiTest,ProtocolABPythonPostgresTest` |
| advisory | `advisory_verify_item2_20261006` | `AutoSmsPostgresTest,AutoVoicePostgresTest,UavAdvisoryPostgresTest,PostgisPilotDepartureWatchTest,QaVideoStreamPostgresTest,TargetVideoPostgresTest` |
| maintenance | `maintenance_flow_verify_item2_20261006` | `DeviceMaintenanceWorkflowPostgresTest` |
| maintenance-notice | `maintenance_notice_verify_item2_20261006` | `DeviceMaintenanceNoticePostgresTest` |
| handoff-notice | `handoff_notice_verify_item2_20261006` | `HandoffNotificationPostgresTest` |
| control-video | `stage456_verify_item2_20261006` | `LingyunControlPostgresTest,TcpMonitoringPostgresTest,VideoStreamRegistryTest,VideoMediaClientTest,LocalCountermeasure4ChLifecycleTest,EoEdgeEnvelopeTest,EoTrackingReportMapperTest` |
| 接口补充 | test/H2 | `EoManualTrackApiTest,EoEdgeMqttTest,QaVideoStreamApiTest,DirectDisposalAccessTest,DirectDisposalApiTest,DirectDisposalBackgroundTest,AutoSmsPolicyTest,AutoSmsSchedulingTest,AutoVoiceRecordingTest,HandoffReceiptResultApiTest,HandoffPunishmentMaterialsApiTest,HandoffSimulatorReceiptServiceTest,SimulatorNotificationRepositoryTest` |
| 通知计时 | `stage456_verify_item2_20261006`，临时本机端口 25432 | `NotificationPresenceTimingPostgresTest,SimulatorNotificationRepositoryPostgresTest` |

其余命令、汇总和日志位置见 [test-results.json](test-results.json)。日志、JUnit XML 和带原始业务关联的回读仅保存在忽略目录 `server/target/item2-evidence/`，不加入源码提交。

## 4. 真实传输及浏览器证据

### 目标、研判和资料重用

- 单源首次运行得到融合置信度 0.65、证据不足，按原规则阻断；没有调低门槛。
- 三路标准化输入得到 `LEGAL / SUFFICIENT / FULL`，高度为明确 AMSL；页面仍提示参数为 DEMO。
- 禁飞区场景实际得到 `ILLEGAL / SUFFICIENT`，无阻断未知原因，生成当前告警并在现有页面人工核实。未直接设置研判结论或写告警表。
- 最终完整合法模板连续两次运行 `sim-1006023641-b18e`、`sim-1006023825-88fc`，均 RUNNING、无准备错误，复用同一空域 ID 和 revision 1；再次回读仍为 LEGAL/SUFFICIENT。
- 资料不足的飞手和报送单位保持缺失；未把模拟输入升级成真实上级数据。

### 光电与实际 HLS

- 批次 `sim-1006020947-6ed7`，人工补跟踪后平台实际下发协议 C Begin，模拟器收到后返回回执及当前任务跟踪报告。
- 当前任务 `7f67aee5-4b36-4911-bce8-dd53791e2ef6`，设备 `a8bf747a-7667-46c1-a3f5-81d0fcbebba2`，回读 TRACKING/PUBLISHING。
- 浏览器通过平台鉴权 HLS 实际播放动态测试图，960×540，`readyState=4`，未暂停；播放时间从 30.654282 秒推进到 49.255302 秒，画面连续变化，有 TEST VIDEO 标识。
- 收起播放器后视频 DOM 为 0，任务仍 TRACKING；刷新仍为同一任务，没有重复开始。
- 通过“暂停并结束”发送实际 End，任务及视频回读 STOPPED；随后解除本目标测试暂停标记，总开关保持原值。
- 浏览器控制台未发现本次光电操作错误。

![实际动态测试视频](video-playing.png)

另有真实 MQTT + FFmpeg + RTSP + HLS 冒烟测试，两种输入源通过；匿名读取、错误发布身份被拒绝，重复 Begin 复用进程、旧任务不能接管、End 清理推流。**该冒烟中的平台 API 是内存契约替身，不能单独计为完整平台验收。** 上面的浏览器运行才使用实际验收后端。

### 运维

- 独立雷达故障窗口中收到 ABNORMAL，随后工参恢复 ONLINE；健康仍 UNKNOWN，符合现有协议字段边界。
- 通过现有授权接口建立独立起飞前模拟计划；在飞行计划页面点击该雷达的异常通知，实际建立内部待办。
- 管理端未读数由 1 变 0，工单仍待处理；显式开始处理、保存进展、提交核验后进入 PENDING_VERIFICATION。
- 工单 `c22c8f2a-66d2-4aca-bedd-a686586aeadb` 核验结果 UNKNOWN，原因“连接或健康指标未知；恢复心跳不代表整体健康恢复”，未出现完成操作；刷新后结果与时间线保留。
- 没有伪造调测通过或恢复 PASS，没有关闭尚未恢复的异常。

![恢复未知保持未完成](maintenance-unknown.png)

## 5. 必测矩阵实际状态

“自动化通过”指相关服务/接口/数据库用例已通过，**不代表同编号的全部浏览器和现场条件完成**。

| 编号 | 自动化证据 | 真实验收服务/浏览器状态 |
| --- | --- | --- |
| B01 | 研判、证据充分度、PostGIS 通过 | 三源 LEGAL/SUFFICIENT，页面与回读一致；DEMO 属性保留 |
| B02 | 处置、续链、移送相关测试通过 | 到 ILLEGAL/SUFFICIENT、告警人工核实；普通异人审批及后续整链未完成 |
| B03/B04 | 3 秒/10 秒真实计时及位置测试通过 | 模板和运动关联回归已备；验收库缺有效飞手/通知前置，未实际走完 |
| B05/B06 | 停报/未知/未核验联系方式阻断测试通过 | 缺飞手及报送单位如实展示；没有补核验 |
| B07 | 通知回执、乱序/失效/关联测试通过 | 不能将无有效接收主体的场景记为通知送达 |
| B08/B09 | 协议 B、授权失败/去重/续链、权限及直接授权回归通过 | 未在验收浏览器走完普通双账号审批及各异常分支 |
| M01 | PostgreSQL 工作流完整成功与阻断回归通过 | 走到待核验；当前协议 A 输入无可信 GOOD，不能标记完成 |
| M02 | 内部通知、并发、版本测试通过 | 实际通知建立、已读不办结、处理记录及刷新通过 |
| M03/M04 | 恢复不足、旧/未来时间、旧 PASS 等回归通过 | UNKNOWN 正确阻断并持久保存；未伪造 PASS |
| E01 | 协议 C/MQTT/跟踪回归通过 | 实际 Begin→报告→TRACKING→End→STOPPED 通过 |
| E02 | 策略、共享、占用回归通过 | 收起、刷新、暂停并结束通过；三页面/自动跟踪浏览器矩阵未全部执行 |
| E03 | 协议信封、报告、持久占用回归通过 | 真实媒体冒烟验证旧/重复指令；所有错误报告浏览器分支未逐一执行 |
| V01 | 注册、就绪、鉴权回归通过 | 实际平台 HLS 连续解码与动态画面通过 |
| V02 | 媒体权限、租约、目标范围回归通过 | 收起、刷新、结束清理通过；中断、过期、越权和会话失效浏览器矩阵未全部执行 |

## 6. 尚需补齐的完成条件

1. **通知资料**：当前可用执行飞手和报送单位绑定为 0；需要通过既有授权入口提供明确标识且具有有效核验依据的测试主体、通知设置和有效录音。上级接口未提供前，不新建自创的同步契约。现有 QA 通道准备接口也会检查主体关联，不能绕过这一点。
2. **普通异人审批**：尚未取得另一现有审批账号的授权登录，不能让同一账号自批，也没有自动授予直接反制权限。需要该审批者参与一次真实模拟申请的审批。
3. **运维完整恢复**：现有协议 A 工参没有足够的健康指标，工作中不能映射为 GOOD。当前路径可验证阻断，完整 PASS 需要现有合法输入能提供真实的健康事实；不能为演示改写健康判定或直接更新业务表。
4. **剩余浏览器矩阵**：通知运动四分支、普通处置/续链/移送、自动跟踪隔离浏览器、视频异常矩阵仍需逐项运行并保存证据。

以上未满足前，本轮只能称“已交付修复、场景和部分联合验证”，不得将第二条登记为全部完成。原始历史、冻结材料、审批方式、权限门槛和观察时长均保持。

## 7. 同日续办：模拟资料已补齐并回读

用户确认先用明确标记的模拟上级资料验证。通过既有授权接口实际创建 4 个模拟联系人、报送单位关联、配套计划，完成 5 类独立本机通知通道和空间风险 DEMO 版本配置。原模拟器回读到可选飞手及有效报送关联，计划主体 LINKED；前文“可用飞手/报送关联为 0”是此前运行时的历史阻塞，当前已解除。

申请人测试账号 `sim_acc23_operator` 同时具备普通申请与执行权限，另一测试账号 `sim_acc23_approver` 负责审批。流程保持“申请人申请 → 异人审批 → 原申请人执行”，没有增加第三个执行环节。两账号尚需本人首次登录改密；ALL 数据范围已在录入页告知并由用户确认。未授予直接反制。

交付 17 份带实际主体关联的场景、8 份通知配置、有效中文测试 WAV，以及上级空域 6 类报文。准备工具 16 项回归通过，文件由既有模拟器校验通过，空域 6 类通过实际 HTTP 验证。详细记录见 [模拟资料验证](../simulation-materials/verification.md) 和 [操作说明](../simulation-materials/README.md)。

上述更新不改变本报告“全部闭环尚未完成”的结论。普通异人审批演练、通知运动分支、处置续链移送、运维可信健康输入与恢复成功分支、余下光电视频异常矩阵仍需继续；本次没有把资料录入当成业务成功。
