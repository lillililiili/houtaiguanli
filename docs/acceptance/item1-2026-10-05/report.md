# 第一条实施与验证记录

实施开始：2026-10-05。验证记录跨至 2026-10-06 UTC。

**当前结论：代码与自动化回归已完成，协议 A、受控资料及模拟器生命周期的本机联合验证已执行；合法性 DEMO v1 已按用户授权启用，并实际生成浏览器可见的研判记录。业务授权处置等剩余浏览器验证尚未完成，因此第一条暂不标记完成。** 验收库原缺少单位、区域及 MQTT 连接；用户明确授权后，已通过现有接口创建“验收模拟单位”“验收模拟区域”和启用的 `local-lingyun-replay` 连接，并回读确认。浏览器发现的航线回读层级问题已修复、通过回归及实际复测。既有本地 QA 策略允许激活 DEMO 版本，用于明确标记的模拟数据；真实来源仍受参数确认校验约束。不启用演示种子，不把 DEMO 参数改成 CONFIRMED。

## 改动范围

| 位置 | 最终行为 |
| --- | --- |
| 后端 `LingyunSenseDataMapper` | 只把有效 uavSN 映射为 identity_clue；型号留在 quality.uav_model |
| 后端 `TargetReadRepository` | 列表、详情共用查询：融合目标取当前 identity_clue，空值不回退旧 SN；非融合目标保持原行为 |
| 模拟器 `protocol_a.py` / `engine.py` | 六类感知设备按各自字段生成报文；AOA 方位、TDOA 可选 SN、空目标帧、停止目标上报但继续工参 |
| 模拟器 `fullchain.py` / `prerequisite_check.py` | 同一提交保留幂等身份；未知结果先回读；发送观测前核对计划 SN、时间、范围、航线版本、几何、高度基准与设备绑定 |
| 模拟器 `platform_client.py` | 复用稳定设备时核对既有单位、区域，防止跨范围错误复用 |
| 模拟器 `protocol_b.py` / `server.py` | 精确订阅当前 replay 设备主题；原样回传字符串 msgNo；17 条白名单；非阻塞延迟线程及暂停/停止/断连约束 |
| 现有设备选项、场景 JSON 与运行日志 | 增加已接入设备类型和测试参数，复用原入口；无业务菜单或独立页面 |

业务前台和管理端 Vue 代码未改动。无新增数据库迁移、生产依赖；未修改业务授权、审批、融合权重、身份来源白名单、规则判断、处置状态、超时及通知观察时长；未回填历史 SN、历史研判或冻结材料。

开始时已有的四通道 TCP、计划时间修复及相关文档/测试改动保留。本报告不能把这些已有改动重复算为本轮新增功能。

## 实际测试结果

逐套统计见 [test-results.json](test-results.json)。脱敏 MQTT/数据库结果见 [mqtt-evidence.json](mqtt-evidence.json)。不提交包含凭据、会话或业务原始记录的测试日志。

| 验证组 | 执行数 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| 后端相关 H2/API/领域测试，10 套 | 119 | 119 | 0 | 0 |
| PostgreSQL/PostGIS，含跨语言 MQTT，6 套 | 138 | 136 | 0 | 2 |
| 模拟器 Python 全套 | 200 | 200 | 0 | 0 |
| 模拟器现有 Node 页面/契约测试 | 59 | 59 | 0 | 0 |
| 后端 package | 1 次 | 构建成功 | 0 | 测试复用上表结果 |

PostgreSQL 两个跳过项是原有 `serveOrdinaryLateReplyBrowser` 和 `serveEmergencyStopLateReplyBrowser` 人工浏览器驻留工具，分别要求专用系统属性；未将其计为通过。普通授权、急停、迟到、去重等自动断言实际执行。生产浏览器矩阵仍单独列为未完成，不以这两个工具的跳过掩盖缺项。

修复前先复现：型号缺 SN 时错误得到型号身份；融合身份为空时页面错误显示旧 SN。`item1-red.log` / `item1-red-read.log` 记录了预期的失败，修复后相关测试通过。初次 PostgreSQL 运行因既有夹具的专用库名前缀/环境变量要求产生错误与跳过；切换到对应隔离库后，目标读取 3 项、计划资料 24 项均通过。跨语言测试初次缺少 OutboxWorker 测试 Bean，补充测试配置后通过。这些失败未从说明中抹去，也未计入最终通过数。

浏览器首次运行 08 模板时，资料已提交，但新增回读代码未兼容航线回执的嵌套 `result.route_version_id`，因此在发送前失败。按实际响应补充复现用例并修复；同时把计划复用和未知结果回读改为使用 `payload.message_id` 中的提交身份，避免混用平台生成的回执 ID。修复后的 Python 全套 200 项通过，旧直接结果格式仍兼容。失败批次保留，未删除或直接修改业务库。

04 多源场景最初复用了独立目标场景的设备/目标来源链路，实际回读为两个既有目标。改用固定专用的 `item1-multi-radar` / `item1-multi-tdoa` 后，实际回读为同一目标的两条来源链路，身份由 TDOA 提供。只调整验收模板与说明，不改融合算法或旧链路；模板生成和契约检查 4 项复跑通过。

## 命令与环境

Windows / PowerShell；Java 17.0.20.1、仓库 Maven Wrapper、Python 3.11、已有 paho-mqtt 2.x、现有 Node。没有为本轮新增依赖。Python 的 paho 来自原本机运行环境 `C:/Temp/houtaiguanlii-local-runtime/python-deps`。

后端命令均在 `server/`：

```powershell
.\mvnw.cmd "-Dtest=LingyunSenseDataMapperTest,TargetReadApiTest,AttributeSelectorTest,PlanMatchCheckTest,LingyunEnvelopeTest,LingyunControlMqttTest,MqttIngressTest,LocalInterfaceSimulatorApiTest,SimulationPolicyTest,LocalCountermeasure4ChLifecycleTest" test
.\mvnw.cmd "-Dtest=LingyunControlPostgresTest,MqttIngressPostgresTest,DisposalMqttFlowPostgresTest,TargetReadPostgresApiTest,LocalPlanFilingPostgresTest" test
# 修正既有夹具专用环境后补跑，27 项实际通过：
.\mvnw.cmd "-Dtest=TargetReadPostgresApiTest,LocalPlanFilingPostgresTest" test
.\mvnw.cmd "-Dtest=ProtocolABPythonPostgresTest#pythonSixSensorsAndFiveReplyModesTravelThroughRealMqtt" test
.\mvnw.cmd -DskipTests package
```

隔离数据库实际在本机 PostgreSQL 16.9/PostGIS 容器 `deploy-db-1`，宿主端口 5432：

- `stage456_verify_item1_20261005`：接入、协议 B、处置与 Python/Java MQTT 集成；每套随机独立 schema。
- `stage2_target_verify_item1`：既有 TargetReadPostgresApiTest 硬性限定库名前缀，随机独立 schema。
- `plan_filing_verify_item1`：既有 LocalPlanFilingPostgresTest 专用环境变量和库名前缀，随机独立 schema。

后两者沿用已有防误连限制，均不是验收业务库；未为统一名称放宽安全检查。测试凭据通过进程环境变量传入。测试夹具仅在隔离 Spring 测试上下文显式启用。验收 `8081` 的数据库 `houtaiguanli` 不承载这些测试夹具。

跨语言测试设置 `ITEM1_SIMULATOR_ROOT=D:/沉积岩/demo-ronghe/tools/device-simulator`、`ITEM1_PYTHON=D:/Software/python311/python.exe`。它启动临时 loopback MQTT Broker，并真实启动 Python `tests/run_protocol_ab_peer.py`；配置不是手工成功回执，也未向验收库插入测试记录。

模拟器命令在 `D:/沉积岩/demo-ronghe/tools/device-simulator`：

```powershell
python -m unittest discover -s tests
$cases = Get-ChildItem tests -Filter '*.cjs' | ForEach-Object FullName
node --test $cases
```

运行日志保留在后端 `target/item1-*.log` 与 `target/item1-evidence/`，均属本机不提交文件。最终统计由 Surefire XML 提取，未复制含系统属性的 XML 到交付目录。

## 真实 MQTT 证据范围

跨语言测试对六类来源分别核对 inbox 为 DONE、实际 source_observation 存在、SN/位置/高度语义正确。雷达/5G-A 不生成 SN；TDOA/DCD/RID 原始 SN 保留；AOA 无有效位置；所有未知 altitude 未进入已知海拔列。测试不是只数 PUBACK。

| 模式 | 实际平台结果 |
| --- | --- |
| 诱骗成功、干扰成功、驱鸟炮成功 | SUCCEEDED，分别 1 条回执 |
| 失败 | FAILED，1 条失败回执 |
| 不回执 | TIMED_OUT，0 条回执 |
| 迟到 | 保持 TIMED_OUT，1 条 PROTOCOL_B_LATE 证据 |
| 重复 | SUCCEEDED，仍仅 1 条结算回执 |

跨语言用例中的超时通过已有服务的 timeout 入口显式触发，便于稳定校验迟到关联；生产超时参数未修改。超时轮询、重复/并发、错误来源和错误关联等由现有 Java 回归覆盖。17 条白名单 × 3 个操作类型通过 Python 参数化响应断言；正常重复指令复用首次结果。额外覆盖了出队后网络发送前刚好暂停的竞态。

跨语言测试使用隔离测试账号和协议测试授权标识，只证明技术控制链路，不声明业务审批已通过或实物已经动作。

## 浏览器及本机验收进度

已确认 `8081` 健康、`5173` 业务前台、`5175` 管理端和 `8766` 模拟器可访问。后端/模拟器已加载修复；启动脚本明确设置 `APP_DEV_SEED_ENABLED=false`，Flyway 校验通过且无新增迁移。

已完成：用户实际登录；新增设备选项显示；旧场景仍可读取、保存；通过既有 JSON 编辑器载入并启动场景。浏览器扩展不允许读取本地文件，文件选择器导入尚未验证，未为测试修改浏览器安全权限。场景文件本身全部通过编译与契约检查。

实际批次证据见 [browser-evidence.json](browser-evidence.json)，B 生命周期见 [browser-b-lifecycle.json](browser-b-lifecycle.json)。回读中的观测总数可能包含稳定来源链路历史观测；`current_observation_facts` 只取观测时间不早于本批创建时间的事实，不把累计总数当作本批发送数。

| 浏览器 / 本机用例 | 实际结果 |
| --- | --- |
| 08 受控资料与重试复用 | 航线版本、计划 SN/时间/范围/高度基准、设备绑定回读一致；修复后复用了首次已创建的计划及设备 |
| 01 六类设备 | 设备在线；六类原始观测均回读成功，read_errors 为空；DCD/RID 原始 SN 保留、融合身份仍为空 |
| 02 TDOA 缺 SN | 型号 `SIM-SAME-MODEL` 保留；原始身份和当前融合身份均为空 |
| 03 同型号不同 SN | 两个目标分别回读 `SIM-ITEM1-TDOA` / `SIM-ITEM1-TWO`；前台刷新后读取两个目标 |
| 04 同目标多源 | 专用固定来源实际关联到一个目标，两条来源链路；停止后重复运行仍保持关联并复用设备；先前未关联批次如实保留 |
| 05 AOA 方位语义 | 在 01 六类场景中实际验证：方位 90°，没有有效位置，不把协议中的无效坐标提升为位置 |
| 06 空目标帧 | 55 个已确认空目标帧、28 个设备工参；无新增观测，无回读错误；未据此认定飞离 |
| 07 停报 | 10 个目标帧后停报，工参持续到批次约 39.5 秒；未据此认定风险解除 |
| 运行生命周期 | 启动、暂停、继续、停止实际操作成功；最后停止全部收发并恢复原场景草稿 |
| 协议 B 生命周期 | 六个精确绑定设备启动正常；未收到平台指令时 commands / published / pending 都为 0；停止后 stopped=true |
| 管理端资料 | 查询可见模拟设备及其继承的“验收模拟单位 / 验收模拟区域” |
| 现有 TCP / 通知接收端 | 08 中 TCP 实际接收 21 次请求；通知租约有效，未收到业务通知；不据此宣称通知内容/回执闭环已验收 |

页面截图：[六类设备接入](six-devices-browser.jpg)、[设备归属](device-scope-browser.jpg)、[一个目标的雷达/TDOA 两条来源](multisource-browser.jpg)。

**初次浏览器验证时的阻碍：** 当时 `LEGALITY-DEMO` v1/v2、`SPACE-RISK-DEMO` v1 均为 `PUBLISHED / DEMO / mock`，两个规则集的 active/shadow 指针均为空，研判页面为空。后续用户授权启用合法性 v1，结果见下文。尚未完成全部“目标列表/详情/研判三处一致”场景、基于实际研判和授权的协议 B 成功/失败/超时/迟到浏览器证据，以及既有 C/通知/处置能力的浏览器回归。隔离环境的技术闭环测试已通过，但不替代这些实际页面验证。协议 C、光电视频及整条处置链的完整验收仍属于第二条，不扩为第一条的新增开发范围。

**规则前提的更正：** 原报告将“已确认规则”笼统列为模拟浏览器验收的必要前提，表述过严。`SimulationPolicy` 允许非生产的 `local & qa` 环境使用模拟能力；`application-qa.yml` 已配置 `allow-demo-active=true`，`RuleSetManagementService.activate` 按此策略允许激活 DEMO 版本。`LegalityEvaluationService` 和 `DecisionAssuranceAlgorithm` 对真实来源继续要求确认参数，对明确标记的模拟来源保留既有模拟验证路径。因此下一步应核对并通过现有授权接口选择适用的模拟规则版本，再按既有申请、审批或已具备的直接授权流程验证指令；不需要为模拟验收将 DEMO 伪装成 CONFIRMED。本次说明更正没有激活规则、修改阈值或新增授权记录。规则启用后仍需实际执行待测项，不能提前宣布通过。

单位/区域要求的历史：`MqttConfigurationService` 的必填校验来自 2026-09-14 提交 `0404a5e7`；模拟器沿用 Broker 范围来自 2026-09-23 提交 `9058a4e0`。本轮未新增该业务约束。一次性配置程序经用户明确授权后调用现有 `/organizations`、`/districts` 和 `/mqtt-brokers` 等接口，不绕过权限，不直接写数据库，不保存密码。可复用 PowerShell 脚本只选择已有范围并配置 Broker，不自动创建范围资料。

## 后续：合法性研判版本启用与运行验证

用户明确要求从现有研判规则中选取并启用版本后，使用本机一次性工具经现有账号登录，调用 `POST /api/v1/rule-sets/LEGALITY-DEMO/activate`，携带原头版本、幂等键及操作说明。2026-10-05 23:37（本机 UTC-04:00）回读确认 `active_version_id=seed-stage7-rsv-1`，产生 ACTIVATE 记录，规则集头版本为 1。所有成员和参数前后摘要一致，参数状态仍为 DEMO；C02-3 航线偏离容差保持 20 米。没有启用 50 米容差的 v2，也没有改变空间风险规则集。临时登录会话用后注销，未保存密码。

随后通过模拟器已有 JSON 编辑器运行原有 04 多源模板，批次 `sim-1005233857-db6d` 持续 39 秒，Broker 确认 114 条，接口回读无错误。实际调度生成 9 次 DONE 运行，9 次研判；首次为 UNDETERMINED，后续 8 次为 ILLEGAL，证据充分性均为 INSUFFICIENT。新增 1 个告警、后续合并 7 次，后台日志 errors=0。该模板没有匹配的计划身份，且缺已知海拔高度，因此结果保留计划授权与高度依据缺口，不将模拟告警认作处置授权。

业务浏览器刷新后显示 1 个目标的最新研判、规则触发依据和“回放 / 参数未确认”提示；接口回读的当前目标身份为 `SIM-ITEM1-RADAR`。此轮证明规则已启用、自动调度已执行及页面可显示结果，不能代替全部身份场景或后续处置审批回执验收。结束后停止全部模拟收发并恢复操作前的原草稿；已启用的规则保留。

证据：[启用及运行回读](rule-activation-evidence.json)、[合法性页面](rule-activation-browser.jpg)。数据库只读查询核对运行和研判数量；本次仅配置及实际联调，没有修改业务代码，因此未重复运行编译和 514 项回归，也未将这 9 次调度运行计入自动化测试数。

## 交付物与已知边界

- 代码及正式测试：后端仓库和现有模拟器目录，保留原有未提交改动。
- 13 个可复用场景及操作说明：`D:/沉积岩/demo-ronghe/tools/device-simulator/scenarios/item1/README.md`。
- MQTTX 离线样本生成：`export_mqttx_messages.py`；协议 A 输出正常/独立异常样本，B 从实际收到的指令生成关联回执测试样本，不主动发送。
- 已导出 [协议 A 样本](mqttx-a-messages.json) 和 [协议 B 已捕获指令示例](mqttx-b-captured-example.json)。这是 topic/payload 样本，不是 MQTTX 原生连接导入文件。A 需按本轮运行重新生成时间和序号；B 示例来自隔离测试的历史模拟指令，新测试必须从本次实际指令重新生成，不能直接拿历史 msgNo 证明当前指令完成。
- 本报告、逐套统计及脱敏 MQTT 结果。

协议 B 保留字符串 msgNo，与文档递增数字序号的差异没有修改。DCD/RID 仍受原融合身份选源边界约束。空目标帧、目标停报、通知回执、模拟控制成功均不等同于飞离、风险解除或真实设备动作。不得将本报告用于宣称协议 C、视频或完整处置链已验收。

## 2026-10-06：B 更新后的两处验收缺陷修复

本节仅记录 MQTT 通道保持与天气时段筛选修复，不将上文未完成的业务浏览器闭环改为通过。

- 模拟器：尊重显式 `transport`；MQTT 无高度基准时不填 AMSL，编辑、JSON 往返与重复编译后仍保持 MQTT。AGL + MQTT 明确报错，需手动选择规范化通道。未填通道的旧场景推断与既有规范化观测默认值保持兼容。六类设备经过三次编译／JSON 往返均继续产生对应 MQTT 目标帧。
- 天气：在既有区域／历史计划关联上检查严格时间交集，跳过较新但不覆盖计划的记录；缺失／倒置计划窗口不匹配。保留原发布时间、完整预报时段、历史输入与真实／模拟隔离；没有修改天气风险阈值、设备通知方式或异物风险筛选规则。
- 测试夹具：原计划重试测试的 `1000000` 毫秒时间被场景时区／计划偏移推到 epoch 0 之前；仅改用有效的现代固定测试时间，使原重试断言实际执行，没有改变业务时间计算。

验证环境：Windows，Java 17 / Maven Wrapper、Python 3.11、Node；后端在 `server/target/b-review-20261005/server` 隔离源码副本运行，未编译当前验收服务的 `target/classes`。自动化接口使用独立 H2 test 上下文及测试夹具；未连接验收业务库。本次没有 SQL、迁移或生产依赖变更，没有重启后台或模拟器 Python 进程，运行服务需重启后加载新逻辑；浏览器已加载新的静态表单。

| 检查 | 命令／范围 | 实际结果 |
| --- | --- | --- |
| 修复前天气复现 | `mvnw.cmd -Dmaven.repo.local=C:/Users/19221/.m2/repository -o -Dtest=LocalForecastReadServiceTest test` | 13 项，7 失败、0 错误、0 跳过，证实旧实现缺少时段筛选 |
| 修复前 MQTT 复现 | `python -B -m unittest discover -s tests -p test_transport_roundtrip.py` | 4 个测试方法，5 个子用例失败，包含通道被替换、未知基准被补全 |
| 修复后后端回归 | `mvnw.cmd -Dmaven.repo.local=C:/Users/19221/.m2/repository -o -Dtest=LocalForecastReadServiceTest,LocalInterfaceSimulatorApiTest,ExternalInterfaceApiTest,LingyunSenseDataMapperTest test` | 47 通过、0 失败／错误／跳过 |
| 模拟器全套 Python | `python -B -m unittest discover -s tests` | 215 通过、0 失败／错误／跳过 |
| 模拟器页面及契约 | `node --test`，展开 `tests/*.cjs` 全部文件 | 69 通过、0 失败／跳过 |
| 后端打包 | `mvnw.cmd -Dmaven.repo.local=C:/Users/19221/.m2/repository -o -DskipTests package` | BUILD SUCCESS；测试结果以上一行回归为准 |
| 浏览器 | 8766 原有 MQTT 目标保存、AGL 冲突提示、恢复未知基准、刷新回读 | 保存／刷新后仍为 MQTT；未知基准保持未知；AGL 冲突未自动切换通道 |

本轮命令日志在 `server/target/acceptance-fix-{weather-before,backend,python,node,package}.log`；未重新运行上一轮无关的 PostgreSQL 空域接口 404/405 断言失败用例，不声明该项已修复。浏览器证据：

![MQTT 目标保存及刷新验证](transport-fix-browser-20261006.png)

2026-10-06 01:17（America/New_York）用户要求重启后，已重新编译并启动 8081 后端、5173 业务前台、5175 管理端及 8766 Python 模拟器。数据库和 MQTT 容器保持原实例；验收启动显式设置 `APP_DEV_SEED_ENABLED=false`，使用 `local,qa`。后端健康及 readiness、三个页面、三处地图配置检查均通过。B 的两条新增迁移由正常 Flyway 启动流程成功应用，版本为 `202610050002`。本次修复已加载；模拟器内存登录会话因重启清除，收发需重新登录后显式启动，不自动补发旧批次。
