# 实测反制完成后的自动移送：2026-10-27 合并回归

## 本次口径

有预测来源的同次风险，实际进入本禁飞区后，允许按已启用的通知处罚规则自动移送。必须同时具有本风险独立、充分、可追溯的实测违法依据，以及本风险反制完成和原流程要求的真实停机成功记录。研判及其观测必须早于本次完成；与完成同毫秒或晚于完成的依据均不用于本次移送。

预测、预测核实、风险核实账本和反制完成本身均不改写为原事件的违法结论。纯预测、区域或来源不符、旧授权没有明确区域归属、停机结果未知、依据不充分时继续阻断。原始材料、通知内容、回执和人工反制要求保持。

## 修正内容

- 以 `risk_case_id` 绑定移送和冻结的实测依据；同一实测事件关联多个禁飞区时，各区域分别判断、分别防重。
- 只有选中 case 与事件、目标、区域、单位、辖区和来源对应时，才按该 case 判断是否具有预测来源。另一区域的预测不能把独立实测区域一并排除；无关 case 不能绕过守卫。
- 自动移送和完成扫描只读取已保存的 case 身份及办理事件，不反复计算当前空间风险。历史实测证明、完成回执和发送边界校验仍完整执行。
- 等待规则的移送保留首次冻结材料；发送时重验原授权、完成时刻、研判和轨迹点引用，不借用之后的新授权或新事实。
- 扫描超过一页不合格候选后仍能发现后续合格项；不存在“前 50 条无效记录一直挡住新记录”的情况。

## 环境和执行结果

Java 17；H2；隔离 PostgreSQL/PostGIS 数据库下的随机 schema。`stage456_verify_…` 用于风险、移送和迁移测试；短信 PostgreSQL 测试要求独立的 `advisory_verify_…` 数据库。所有数据库夹具只存在于隔离测试环境。

| 执行 | 用例 | 结果 |
| --- | ---: | --- |
| clean 后首轮整合回归 | 495 | 0 断言失败、7 错误、41 跳过；不是一次全通过 |
| 移送定向复验 | 42 | 全通过，0 跳过；含 PG 21、H2 投影/起源/完成守卫 21 |
| 旧普通实测移送兼容复验 | 88 | H2 44、PostgreSQL 44，全通过，0 跳过 |
| 正确隔离数据库上的短信回归 | 41 | PostgreSQL 全通过，0 跳过 |
| 补齐完成同毫秒边界后的最终移送 PG 复验 | 22 | 全通过，0 跳过 |
| `package -DskipTests` | — | 构建成功；没有把跳过测试的打包算作测试通过 |

首轮七个错误均已处理：三个新增反例夹具错误地修改只追加证据或使用协议不允许的 mask，改为插入时构造反例/使用合法非零 mask；两个并发用例暴露历史移送重复计算当前空间风险，已解耦并保留原 8 秒事务上限；两个旧夹具在 PG 自动创建 case 后改了告警目标，修正为构造一致的测试身份，没有放宽产品身份守卫。短信 41 项首轮因库名前缀不符合专用测试保护而跳过，已在正确库实际执行通过。

最终覆盖 **496 个不同用例**：首轮无需重跑且已通过的 324 项，加最终新移送 43 项、旧移送兼容 88 项和短信 PostgreSQL 41 项。重复运行不重复计数。其中原短信/核实/反制守卫及认证相关 229 项、远端合并的飞行检查/设备距离 9 项、三组迁移谱系 17 项已通过。

关键实际覆盖：live/replay；纯预测；缺少核实、高度、正确来源或充分研判；完成前、同时和之后的实测；未知停机及非全关指令；旧无区域授权；两个区域共用事件；双线程重复自动移送；冻结材料不可改；101 个不合格候选后的合格记录；旧规则等待、人工接管和投递防重。并发测试禁止调用当前空间 case 读模型，并且仍使用真实的多个数据库连接和原 8 秒事务上限。

隔离测试读模型改用现有 Hikari 连接池（上限 8），避免逐语句新建连接。101 候选用例由首轮约 304 秒降至定向复验约 21 秒；这是测试夹具/查询路径的测量，不代表生产性能承诺。

## 实际命令

以下均在 `server/` 执行，使用 `JAVA_HOME=C:/Users/19221/.jdks/temurin-17`，`MAVEN_OPTS=-Djava.io.tmpdir=C:/Temp`。数据库账号通过 `POSTGRES_TEST_USER` / `POSTGRES_TEST_PASSWORD` 提供，不写入仓库。

首轮使用 `stage456_verify_…`：

```powershell
.\mvnw.cmd clean test '-Dtest=AutoSmsApiTest,AlarmRuleVerificationTest,AutoSmsQueueContinuityTest,UavRiskCaseServiceTest,CommandSettlementSignalTest,EmergencyStopApiTest,DirectDisposalAccessTest,DirectDisposalBackgroundTest,AuthApiTest,UavAdvisoryHandoffProjectionTest,HandoffPredictionCompletionGuardTest,DisposalPredictionOriginTest,HandoffPunishmentPrerequisiteTest,HandoffAutomaticMatrixApiTest,FlightCheckTest,CounterFlightDistanceApiTest,PredictionDispatchGuardTest,UavRiskCaseReadPostgresTest,AutoSmsPostgresTest,HandoffAutomaticMatrixPostgresTest,MigrationLineagePostgresTest,FeatureMigrationLineagePostgresTest,VoiceFlightMigrationLineagePostgresTest' '-DargLine=-Djava.io.tmpdir=C:/Temp'
```

修复后按受影响范围复验：

```powershell
.\mvnw.cmd test '-Dtest=UavRiskCaseReadPostgresTest#measuredPunishment*+aSharedEvent*+concurrentAutomatic*+completedPunishment*,UavAdvisoryHandoffProjectionTest,HandoffPredictionCompletionGuardTest,DisposalPredictionOriginTest' '-DargLine=-Djava.io.tmpdir=C:/Temp'
.\mvnw.cmd test '-Dtest=HandoffAutomaticMatrixApiTest,HandoffAutomaticMatrixPostgresTest' '-DargLine=-Djava.io.tmpdir=C:/Temp'
# 切换 POSTGRES_TEST_URL 到 advisory_verify_… 隔离库
.\mvnw.cmd test '-Dtest=AutoSmsPostgresTest' '-DargLine=-Djava.io.tmpdir=C:/Temp'
# 切回 stage456_verify_…，补齐同毫秒边界后最终复验
.\mvnw.cmd test '-Dtest=UavRiskCaseReadPostgresTest#measuredPunishment*+aSharedEvent*+concurrentAutomatic*+completedPunishment*' '-DargLine=-Djava.io.tmpdir=C:/Temp'
.\mvnw.cmd package -DskipTests
```

本机日志保存在忽略目录 `artifacts/local-runtime/measured-handoff-*.log`。

## 运行包与边界

已检查最终 JAR：移送脚本为 `V202610270003` / PostgreSQL `V202610270004`；260001 电话顺序与航班避让原脚本分别仅位于 `db/lineage/voice-order`、`db/lineage/flight-avoidance`。远端 `V202610270001__counter_flight_distance.sql` 和追加兼容 `V202610270005` / `V202610279001` 均存在。四个改号/移位前的旧构建路径均不存在；本轮先 `clean`，未依赖残留 target 资源。迁移谱系另见同期合并迁移验收记录。

本轮完成代码整合、隔离自动化回归和打包，**没有部署、重启运行服务、修改运行库或进行真实账号浏览器验收**。运行配置未更改，`app.dev-seed.enabled=false` 保持；测试中的既有合成夹具没有进入验收运行服务。
