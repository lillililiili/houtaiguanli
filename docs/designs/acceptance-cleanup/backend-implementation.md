# 后端验收清理实施说明

本文件只列本轮非视频后端修改；修改前已有大量未提交功能，均保留。未改迁移、未改短信/语音逻辑或配置、未删除历史数据。本轮不提交。

## 行为边界

- 正式环境默认为 live；模拟只允许 local+qa 或 test 夹具，prod/production 总是优先阻断。原本仅 local 的种子仍不在 test 中加载，原 test-only 夹具仍只用于 test。
- 启动时拒绝正式环境误开 source-mode=mock/replay、dev-seed、mock adapter、fusion/rule replay、allow-demo-active、mock handoff。
- 正式 MQTT 不能创建或开启 replay 连接/设备；后台协调器也不重连已有 replay。融合与规则正式调度默认可用，只排除非真实来源；不全局关闭业务引擎。
- 正式预案不能创建、修改、发布、绑定模拟版本；已有模拟预案只读且显示历史不可适用。
- 真实天气只保存配置，尚未接入时仍返回无预报；模拟天气仅显式 QA/测试可用。
- 调测对非真实/模拟设备拒绝新任务与启动；真实结果仍取设备适配器回执，历史模拟报告保留原模拟标记。
- DEMO/未确认版本与参数不能在新 live 研判中产生明确正式结论；新结果记录 UNDETERMINED 与原因，不修改历史研判。
- 正式环境不使用模拟移送渠道或模拟回执；未确认处罚规则不能写入/确认裁量，现有唯一 demo-v1 模板禁止正式出具。
- 业务报表汇总/分页/导出统一只计 live；运算报表还排除模拟设备、非真实风险、未确认/证据不足研判、demo模板及未确认处罚规则。API参数不会放开这一口径。
  - 2026-10-07 用户决定：运行统计和数据大屏在允许模拟的环境（local+qa、test）把设备模拟器（replay）的数据也算进来，模拟器数据的研判结论不要求参数已确认；系统自带的演示样例（mock）仍不算；正式环境不变，只计 live。见 `platform/query/StatisticsScope`。业务报表不变。

## 核心方法变更

| 类/文件 | 方法/函数 | 作用 |
| --- | --- | --- |
| SimulationPolicy | allowed/includes/requireSimulation/requireSourceMode/validateConfiguration | 环境、来源与启动配置守卫 |
| AppProperties/application.yml/deploy/compose.yml | 无具体方法 | 正式默认 live，种子关闭，真实融合和规则默认调度可用 |
| MockAdapter/MockSuperiorHandoffChannel/各模拟种子类 | Profile 注解，无具体方法 | 环境隔离 |
| MockWeatherForecast | available | QA/测试天气模拟限制 |
| MqttConfigurationService | mode/validateConnection/enableDevice/sourceAllowed | 服务端创建、启用与来源守卫 |
| MqttSessionSupervisor | reconcile | 不连接历史非真实 MQTT |
| ResponsePlanService | validate/create/newVersion/update/publish/withdraw/bind/binding | 模拟版本只读且不作为正式关联依据 |
| CommissionService | create/connect/start | 新调测必须符合正式来源 |
| NotificationDirectoryService/HandoffNotificationService | simulationEnvironment/localMock/channelBlocker | 模拟移送签收不可进入正式环境 |
| PunishmentCaseService | draftDiscretion/confirmDiscretion/issueDocument | 未确认规则及演示文书阻断 |
| RuleSetManagementService | activate/rollback | 未确认版本正式不可激活 |
| RuleRunService | start | 正式禁止回放运行 |
| LegalityEvaluationService | evaluateInCurrentTransaction | 来源校验与新正式结论可靠性 |
| RuleEngineRepository | pendingSubjects | 非真实目标不挤占正式批次 |
| FusionPipeline/FusionInboxRepository/FusionIngestWorker | processFrame/claimablePrefixes/prefixSql、Profile移除 | 正式处理真实帧，非真实历史不领取 |
| ReportDatasetReader | from | 所有分类报表共同的 live SQL 筛选 |
| ReportingRepository | where/formalEvaluationIds/formalRiskIds/cases | 正式对象、研判、风险、有效处罚结果筛选 |
| ReportingService | operations | 统一正式指标计算与导出 |
| DeviceRepository | overview(String,boolean) | 正式设备指标排除 simulated |

## 精确改动路径

- `deploy/compose.yml`
- `server/src/main/java/com/uav/lowaltitude/integration/MockAdapter.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalAirspaceRiskDemoSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalAlarmFlowRuleSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalCountermeasure4ChSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalCountermeasure4ChSimulator.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoRolesSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoVolumeAlarmSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoVolumeDeviceSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoVolumeDisposalSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoVolumeHandoffEvidenceSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalDemoVolumePunishmentSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalFlightDeviceMqttSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalFlightPathDemoSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalFlightPlanDetailsSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalFlightPlanEnrichmentSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalMqttSimSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalPendingPlanDemoSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalQaRuleCatalog.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage13DisposalSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage14PunishmentSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage15DemoReviewerSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage2TargetSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage3PlanningSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage4AlarmSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage4RiskSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage5DeviceScopeSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage5HandoffSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage7DemoVolumeSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage7RuleEngineSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage8FusionReplaySeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage9AirspaceSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalStage9SpaceRiskSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalWeatherRiskSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/MockWeatherForecast.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/RuleDemoVolumeReplayRunner.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mock/RuleReplayRunner.java`
- `server/src/main/java/com/uav/lowaltitude/integration/mqtt/MqttSessionSupervisor.java`
- `server/src/main/java/com/uav/lowaltitude/integration/replay/FusionReplayRunner.java`
- `server/src/main/java/com/uav/lowaltitude/integration/replay/LingyunMqttReplayExporter.java`
- `server/src/main/java/com/uav/lowaltitude/integration/replay/ReplayAdapterPort.java`
- `server/src/main/java/com/uav/lowaltitude/modules/airspace/api/LocalAirspaceSimulatorController.java`
- `server/src/main/java/com/uav/lowaltitude/modules/assessment/application/RuleSetManagementService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/assessment/engine/LegalityEvaluationService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/assessment/engine/RuleEngineRepository.java`
- `server/src/main/java/com/uav/lowaltitude/modules/assessment/engine/RuleRunService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/api/LocalQaDeviceController.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/application/CommissionService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/application/LocalDeviceSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/application/LocalQaDeviceService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/application/MqttConfigurationService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/device/infrastructure/DeviceRepository.java`
- `server/src/main/java/com/uav/lowaltitude/modules/directory/application/NotificationDirectoryService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/flight/application/LocalFlightPlanInputService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/flight/application/LocalPlanFilingService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/fusion/application/FusionIngestWorker.java`
- `server/src/main/java/com/uav/lowaltitude/modules/fusion/application/FusionPipeline.java`
- `server/src/main/java/com/uav/lowaltitude/modules/fusion/infrastructure/FusionInboxRepository.java`
- `server/src/main/java/com/uav/lowaltitude/modules/handoff/application/HandoffNotificationService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/handoff/application/LocalInterfaceReceiptService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/handoff/infrastructure/MockSuperiorHandoffChannel.java`
- `server/src/main/java/com/uav/lowaltitude/modules/identity/application/LocalStage2AccessSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/modules/identity/application/LocalUserSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/modules/integrationconfig/api/LocalInterfaceSimulatorController.java`
- `server/src/main/java/com/uav/lowaltitude/modules/integrationconfig/application/LocalForecastReadService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/integrationconfig/application/LocalInterfaceChannel.java`
- `server/src/main/java/com/uav/lowaltitude/modules/integrationconfig/application/LocalInterfaceSimulatorService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/punishment/application/PunishmentCaseService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/reporting/application/LocalReportingSeeder.java`
- `server/src/main/java/com/uav/lowaltitude/modules/reporting/application/ReportingService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/reporting/infrastructure/ReportingRepository.java`
- `server/src/main/java/com/uav/lowaltitude/modules/responseplan/application/ResponsePlanService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/target/api/LocalAirspaceDemoTargetController.java`
- `server/src/main/java/com/uav/lowaltitude/modules/target/application/LocalAirspaceDemoTargetService.java`
- `server/src/main/java/com/uav/lowaltitude/modules/target/infrastructure/LocalAirspaceDemoTargetRepository.java`
- `server/src/main/java/com/uav/lowaltitude/platform/config/AppProperties.java`
- `server/src/main/java/com/uav/lowaltitude/platform/config/SimulationPolicy.java`
- `server/src/main/java/com/uav/lowaltitude/platform/report/ReportDatasetReader.java`
- `server/src/main/resources/application.yml`
- `server/src/test/java/com/uav/lowaltitude/integration/mock/MockWeatherForecastTest.java`
- `server/src/test/java/com/uav/lowaltitude/modules/fusion/application/FusionFutureObservationTest.java`
- `server/src/test/java/com/uav/lowaltitude/modules/fusion/infrastructure/FusionInboxFreshSourceTest.java`
- `server/src/test/java/com/uav/lowaltitude/modules/reporting/api/BusinessReportingApiTest.java`
- `server/src/test/java/com/uav/lowaltitude/modules/reporting/api/ReportingApiTest.java`
- `server/src/test/java/com/uav/lowaltitude/platform/config/FormalBusinessGuardTest.java`
- `server/src/test/java/com/uav/lowaltitude/platform/config/SimulationPolicyTest.java`
- `server/src/test/java/com/uav/lowaltitude/platform/report/FormalReportDatasetTest.java`
- `server/src/test/resources/application-test.yml`

## 验证

最终从 server/target/acceptance-verify/surefire-reports/TEST-*.xml 汇总：**19 类，123 例，0 失败、0 错误、0 跳过**。认证 9 例、业务报表 24 例、运算报表 H2 12 例及 PostgreSQL 12 例、预案 7 例、规则配置 7 例、判据 15 例，以及模拟门禁/融合/视频相关测试均通过。主代理补充视频登记容量失败审计、范围 NONE 越权、错误 stream、MediaMTX session 参数白名单与平台私有会话映射用例后重跑视频测试，计数由 120 增至 123。

命令在 server/ 执行，Java 17；使用临时 .acceptance-verify-pom.xml，仅把构建目录改为 target/acceptance-verify，原 pom.xml 不变。验收结束后临时 pom 已移至忽略的 target/acceptance-verify-pom.used.xml 留作执行记录；该记录文件不直接作为新构建入口。

测试选择器：
SimulationPolicyTest, FormalBusinessGuardTest, FormalReportDatasetTest, MockWeatherForecastTest, DecisionAssuranceAlgorithmTest, FusionFutureObservationTest, FusionInboxFreshSourceTest, AuthApiTest, ReportingApiTest, BusinessReportingApiTest, OperationsPostgresTest, RuleSetManagementApiTest, ResponsePlanApiTest, EoManualTrackApiTest, QaVideoStreamApiTest, QaVideoStreamPostgresTest, TargetVideoPostgresTest, VideoMediaClientTest, VideoStreamRegistryTest。

执行命令：mvnw.cmd -f .acceptance-verify-pom.xml "-Dtest=上述类名逗号连接" test；最后 mvnw.cmd -f .acceptance-verify-pom.xml -DskipTests package。

第一次隔离执行中 99 例通过，三个完整 PG 类因之前失败运行残留 schema 触发旧迁移跨 schema 同名约束问题；分别使用全新 advisory_verify_acceptance_ops_clean、advisory_verify_acceptance_qa_clean、advisory_verify_acceptance_video_clean 测试库单类重跑，各自成功应用 132 项迁移，分别通过 12/5/4 例。正式报表公共 SQL 测试使用独立 acceptance_test_20260928_cleanup 数据库；融合 SQL 也在真实 PostgreSQL 上通过。未修改历史迁移或业务库。

打包成功，产物 server/target/acceptance-verify/low-altitude-server-0.1.0-SNAPSHOT.jar。本轮 git diff --check 通过。未运行整仓全量测试，也不把其他会话运行的 QA 服务当作本次重新部署验证。

中间失败已保留在 target 日志：首次代码括号/类型引用、负例 mock 用户已修复；视频夹具与 Windows loopback 已由主代理修复；共享 target 曾被外部构建过程修改导致 class 缺失，改隔离构建目录后不再出现。

## 隔离服务验收

使用本轮隔离打包的副本启动 `127.0.0.1:18091`，连接全新 PostgreSQL/PostGIS 库 `advisory_verify_acceptance_runtime_20260928`；132 项迁移通过，health 返回 200。启用 local+qa 和真实 MQTT/outbox，关闭种子、回放、MockAdapter、自动跟踪及业务推进；未停止或改写现有 8081 服务。Windows Java 临时目录显式指向构建目录下 ASCII 路径，解决启动时 loopback socket 报错。

注入夹具前 target、alarm、flight_risk、eo_tracking_task、ops_device 均为 0，证据保存在忽略目录 `server/target/acceptance-runtime/empty-start-counts.txt`。之后仅在隔离库加入范围与目标位置测试夹具；MQTT broker 与 replay EO 设备通过真实平台 API 创建，状态 CONNECTED/subscribed=true。真实任务开始、设备回执、媒体推流由外部模拟器执行，不注入成功任务/回执。

空库 bootstrap 的既有权限差异：auth/me 展示管理员完整权限目录，但 DatabaseAccessControlMapper 仍要求 app_role_permission 中实际 ACTION 行；关闭开发种子后缺少 target:read，导致真实跟踪入口 403。本次仅在隔离夹具显式授予 target:read READ，未修改生产权限体系，未授予 disposal:direct；此既有问题不纳入本轮清理修复。

另外使用相同隔离库与 jar 副本启动 `127.0.0.1:18092`，profile=production、source-mode=live，关闭种子、回放、MQTT/outbox 与业务推进。health 为 200；持有效会话访问 QA 视频登记接口返回 404，即使显式 qa-enabled=true 也不开放生产测试路由。真实 HTTP 创建 replay MQTT broker 返回 409/SIMULATION_DISABLED，未写入模拟配置。证据为 `server/target/acceptance-production/smoke.json` 和 `replay-rejected.json`。此进程使用媒体 session 兼容修复前的打包版本，仅作为门禁验收；18091 已使用最终修复后重新 package 的版本。
