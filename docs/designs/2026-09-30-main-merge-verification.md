# main 合并与回归记录（2026-09-30）

用户要求将 codex/realtime-simulator 与 codex/acceptance-cleanup 纳入 main，并明确要求先修复全部失败再提交。

## 提交范围

- 起点为 68a91930fbd65b60d16deaedc8cfd079cf3ab0b8。两个分支的历史提交已经是 main 的祖先，本次整合的是任务开始时选定的未提交源码、测试、文档和本轮回归修复。
- 已选源码快照树为 735e5ded36c3a5df6e1e0b61e1387c3a19e93f20；在独立工作区验证，其他任务后续开发的气象、归一化模拟器及浏览器夹具等改动保留在原工作区。
- 本地 application-local.yml、凭据、手工数据文件、输出目录、截图、Office 临时文件、node_modules、dist、target 均不纳入提交。
- 不改已应用迁移；新增迁移随本次源码在独立 PostgreSQL/PostGIS 验证库验证。

## 验证结果

- 后端完整 `mvnw.cmd -B package` 成功，实际发现 2942 项测试，1869 项通过，0 失败、0 错误、1073 项按环境或夹具条件跳过。本轮默认未设置 PostgreSQL 环境变量，相关数据库测试由下述独立执行补充验证。

- 前端：`npm run lint`、`npm test`、`npm run build` 均通过；Vitest 31 个文件、174 项测试全部通过。
- PostgreSQL/PostGIS：22 个相关测试类，按每类最后一次执行报告汇总为 389 项，384 项通过，0 失败、0 错误、5 项跳过。5 项均为需要显式开启的浏览器服务夹具。
- PostgreSQL 首轮有 4 项独立子进程环境错误；修复后完整重跑 `AutomationMqttAcceptancePostgresTest`，24 项全部通过。其余 21 类在相同生产代码及数据库配置下已通过。
- 时间精度回归：固定微秒加 700 纳秒的时钟，旧代码 2 项稳定失败；修复后 H2 的 40 项后台测试全部通过，继承的 PostgreSQL 测试也通过。

验证使用 Java 17.0.20.1、PostgreSQL 16/PostGIS 3.5 和独立 `stage456_verify_` 验证数据库；凭据仅由本地环境注入。Windows 中文用户目录下通过 `-Dtest.unix-socket-directory=E:\houtaiguanlii\server\target` 指定 ASCII socket 临时目录。后端命令在 `server/` 执行，前端命令在 `ruoyi-ui/` 执行。

本地原始证据保存在 `server/target/merge-main-frontend-final.log`、`merge-main-pg-final.log`、`merge-main-pg-retry.log`、`merge-main-pg-verified-reports/`、`merge-main-package-verified.log`。日志与构建输出不纳入 Git 提交。

## 本轮回归修复的代码变更说明

以下列出为消除回归失败新增的修复。原分支待提交功能的完整文件清单以本次 Git 提交为准。

| 改动文件/类 | 改动方法/函数或配置块 | 方法作用及本次修改的代码作用 |
| --- | --- | --- |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/AutomationMqttAcceptancePostgresTest.java` | runChild | 子 JVM 继承已验证的 socket 目录并明确输出 UTF-8 日志，修复 Windows 独立进程重启测试的网络初始化及日志解码错误 |
| `server/src/main/java/com/uav/lowaltitude/modules/disposal/application/DisposalJammingChain.java` | chain | 创建续链授权时对齐数据库微秒精度，防止舍入后 valid_from 稍晚于立即复核时刻而漏下发；保留资格复核及父授权有效期上限 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/application/DirectDisposalBackgroundTest.java` | directJammingRecordsDispatchAfterItsAuthorization | 固定时钟在微秒舍入边界，同时验证接受与拒绝派发事件，修复前稳定 2 项失败，修复后 40 项后台测试通过 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/DeviceMonitoringPostgresFixture.java` | springProperties | 为只继承 test profile 的 PostgreSQL 子类也设置有界且不保留空闲连接的测试池，防止遗漏连接耗尽问题 |
| `server/pom.xml` | 无具体方法：Surefire 配置 | 设置测试 JVM socket 目录并保留调用方 argLine |
| `server/src/main/java/com/uav/lowaltitude/platform/audit/AuditLabels.java` | 无具体方法：模块及动作映射 | 补充接口接入、模拟通知连接及回执动作的中文审计标签 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/DevSeedProfileGateTest.java` | hasTestOnlyProfile / excludesFormalProfiles | 按编译后的 Profile 表达式校验，识别常量引用并覆盖生产组合与单独 local |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalCountermeasure4ChSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalMqttSimSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage2TargetSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage4AlarmSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage4RiskSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage5HandoffSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage7RuleEngineSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage8FusionReplaySeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/LocalStage9AirspaceSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionDevSeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionReportingSeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage13SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage14SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage15SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage3SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage4SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage5SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage7SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage85SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage8SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/integration/mock/ProductionStage9SeedIsolationTest.java` | 隔离测试方法 / assertIsolated / context 或 start | 合法生产配置验证无演示数据；ProductionDevSeedIsolationTest 另以实际启动验证非法开关被拒绝 |
| `server/src/test/java/com/uav/lowaltitude/modules/alarm/api/DualChannelReceiptPostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/alarm/api/NotificationPresenceTimingPostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/assessment/api/SpatialBoundaryAcceptancePostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/assessment/api/SpatialVersionLifecyclePostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/assessment/engine/LegalityEvaluationServiceTest.java` | missingParameterIsADeploymentErrorAndWritesNothing | 断言当前 ApiException 与 RULE_CONFIGURATION_INVALID，继续确认失败不写研判记录 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/EoManualTrackApiTest.java` | insertTarget | 使用统一测试时钟构造稍早的位置时刻，避免数据库精度造成未来位置 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/FusionInputAcceptancePostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/AutomationIdentityComparisonPostgresTest.java` | 无具体方法：环境门禁；database / initialize（适用时） | 保留 stage456_verify_ 专用库白名单，修复门禁继承或旧固定库名，允许本次隔离数据库验证 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/DisposalExecutionTest.java` | fixture / cleanup / anyDevice / 执行拒绝测试 | 独立创建并清理有明确权限范围的设备夹具，取消无设备空跑，补上真正的错误码断言 |
| `server/src/test/java/com/uav/lowaltitude/modules/handoff/api/HandoffAutomaticMatrixApiTest.java` | prepareSupplementalChannel / independentGenerationDeliveryAndManualRetryBatches | 只启用本用例的精确接收单位，排除其他测试残留单位 |
| `server/src/test/java/com/uav/lowaltitude/modules/handoff/api/HandoffPunishmentMaterialsApiTest.java` | fixture / disposal | 允许子类共享当前接收单位 ID，并生成长度合法且不碰撞的授权编号 |
| `server/src/test/java/com/uav/lowaltitude/modules/identity/application/LocalStage2AccessSeederTest.java` | Profile 门禁测试 / 隔离上下文夹具 | 对齐显式 local+qa 模拟环境和生产启动时禁止演示数据的既有规则 |
| `server/src/test/java/com/uav/lowaltitude/modules/target/api/TargetSummariesApiTest.java` | allItems / 查询断言方法 | 按接口 total 分页读取，避免把第一页当完整结果集 |
| `server/src/test/resources/application-postgres-test.yml` | 无具体方法：datasource.hikari | 限制测试池为 4 个连接、最小空闲为 0，防止多上下文耗尽数据库连接 |
| `server/src/test/resources/application-test.yml` | 无具体方法：datasource.url | 每个独立 Spring 测试上下文使用唯一 H2 库，避免夹具串扰 |

## 边界

- 生产隔离测试采用合法配置验证不产生演示数据，并保留非法生产开关实际启动失败的验证；没有放宽生产门禁。
- 真实时序保持短信送达后 3 秒、电话播放完成后 10 秒；未通过缩短窗口让测试通过。
- 独立只读代码复核已检查本轮修复；Surefire 参数覆盖问题已修复并复核。
- 验证库仅用于测试，未操作业务库。跳过项与本次执行范围在验证结果中明确列出；构建通过不等于浏览器人工验收。
