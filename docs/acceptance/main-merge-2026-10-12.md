# 2026-10-12 main 合并与迁移编号协调

本次合并保留 main 的设备运维、扫描范围检查、鸟群数量只影响升级、大屏统计提速，以及本地的人工反制约束、通知飞离快照、移送证据冻结和模拟观测高度处理。

本地已确认的 `C03.no_plan_status=LEGAL` 约定保持。远端 2026-10-09 参数确认记录中的 ILLEGAL 只描述该分支当时环境；新目录迁移只发布，不自动激活或切换既有生效指针，本次合并没有执行参数启用。

## 回归修正

- 共享设备操作的提交响应使用 `devices.op` 与当前提交人校验，避免前台命令已入队但读取响应时被后台监控权限误拒。`GET /device-commands/{id}` 仍要求后台监控权限。四通道回归覆盖两种 mask、重复提交和无操作权限拒绝。
- 权限测试按已确认的前后台身份分离更新：普通自定义角色不能凭旧后台授权进入后台；固定后台权限仍按 18 项白名单严格验证，不默认授予业务写动作。
- 已停用类型通过隔离测试的历史 MQTT 绑定验证兼容协议，登记接口仍必须拒绝新建；不修改生产种子或验收库。
- 补齐设备与任务所属范围的测试关系，复用已有通知测试时钟，等待 Element Plus 弹窗卸载定时器清理完毕。
- PostgreSQL 空间风险夹具使用当前有效计划、当前规则版本、实测 AGL 及与最新状态一致的 MEAS 点；按目标和计划定位风险，保留近远距离、重复评估与历史等级不变的断言。
- 移送冻结证据测试用事务回滚隔离用例，避免清理时删除只增证据；运维冷启动测试向子进程传递 Unix socket 临时目录，支持含中文的 Windows 用户路径。

## 迁移版本

- main 的 `V202610110001__eo_tracking_failure_evidence.sql` 保持原样。
- 本地分支的通知快照迁移改名为 `V202610120001__notification_departure_snapshot.sql`，SQL 内容不变，Flyway 校验和仍为 `-1287981834`。
- 新安装和已使用 main 光电迁移的数据库直接执行正常 Flyway 升级，无需协调历史。

## 已应用本地通知快照旧编号的数据库

这类库已有 `V202610110001__notification_departure_snapshot.sql`，不能直接以新源码启动。先停止应用、完成数据库备份并确认升级窗口，再由获授权的操作者使用 `psql -v ON_ERROR_STOP=1` 执行 `server/scripts/reconcile_notification_departure_migration_20261012.sql`。协调后通知版本为 `202610120001`，main 中尚未应用的低版本迁移需要 `spring.flyway.out-of-order=true`：当前 `local` 配置已开启；其他环境必须在获批升级窗口显式开启一次，完成迁移与校验后恢复原配置。默认关闭乱序迁移时不能直接启动。本次代码合并不自动操作业务数据库。

协调脚本是迁移编号冲突的受控例外，不属于自动迁移。它在事务和排他表锁内严格核对原编号、文件名、描述、类型、校验和、成功状态、既有快照列及目标编号未占用；仅改版本与文件名，保留安装顺序、执行人、执行时间、校验和及业务数据。任何差异均中止，不执行 Flyway repair、不删除历史、不关闭校验，不可用于其他数据库状态。重复执行会中止；完成协调后使用含此合并的新版本，旧分支不再直接用于该库。

`NotificationMigrationMergePostgresTest` 使用独立 `stage456_verify_*` 库中的随机 schema，覆盖新安装、main 升级、本地旧编号升级、数据与执行事实保留，以及错误校验和、文件名、描述、失败历史、缺列和目标编号占用时拒绝协调。最终运行结果以本次测试报告为准。

## 验证记录

执行环境为 Windows、OpenJDK 21.0.10（项目编译目标仍为 Java 17）、Docker PostgreSQL 16.9/PostGIS；数据库专项只连接本次新建的 `*_verify_merge_20261012_*` 隔离库。测试上下文允许显式夹具，业务数据库与运行服务的开发种子开关没有修改。

| 验证 | 命令或范围 | 实际结果 |
| --- | --- | --- |
| 管理前端 | `ruoyi-ui/` 中 `npm ci`、`npm run lint`、`npm test`、`npm run build` | 48 个测试文件、327 项通过；lint 和生产构建通过 |
| 后端完整默认回归与打包 | `server/` 中 `./mvnw.cmd -Dtest.unix-socket-directory=C:/Temp package` | 4665 项：2829 通过、0 失败、0 错误、1836 环境条件跳过；BUILD SUCCESS，耗时 9 分 38 秒 |
| Python 验收脚本单元测试 | 仓库根目录 `python -m unittest discover -s server/src/test/python -p test_acceptance_flows_1_7.py` | 8 项通过 |
| 本地服务脚本 | `scripts/tests/local-service-state.test.ps1`、`local-port-release.test.ps1`、`local-simulator-state.test.ps1` | 分别 8、7、59 条检查通过 |
| PostgreSQL/PostGIS 专项 | 以下 18 个测试类，按隔离库守卫分批运行 | 443 项，442 通过、0 失败、0 错误、1 条浏览器夹具按条件跳过 |

数据库专项在 `server/` 使用 `./mvnw.cmd -Dtest.unix-socket-directory=C:/Temp -Dtest=<类名列表> test`。按测试类的数据库前缀约束注入 `POSTGRES_TEST_URL/USER/PASSWORD`，`FlightScanCoveragePostgresTest` 使用 `FLIGHT_TEST_PG_URL/USER/PASSWORD`，凭据不写入记录。

| PostgreSQL 测试类 | 用例数 | 结果 |
| --- | ---: | --- |
| Stage9PostgresTest | 24 | 通过 |
| ConfirmedRuleCatalogPostgresTest | 9 | 通过 |
| LegalityScopePostgresTest | 31 | 通过 |
| DeviceConsistencyPostgresTest | 14 | 通过 |
| DeviceRecoveryEvidencePostgresTest | 20 | 通过 |
| DeviceMaintenanceCasePostgresTest | 86 | 通过，含独立进程冷启动恢复 |
| AutomationIdentityComparisonPostgresTest | 1 | 通过 |
| AutomationMqttAcceptancePostgresTest | 26 | 25 通过、1 浏览器夹具条件跳过 |
| FlightDeviceReadPostgresTest | 12 | 通过 |
| FlightDeviceReportPostgresTest | 15 | 通过 |
| FlightScanCoveragePostgresTest | 12 | 通过 |
| HandoffFrozenEvidencePostgresTest | 25 | 通过 |
| HandoffNotificationPostgresTest | 16 | 通过，含并发重复通知 |
| DeviceMaintenanceNoticePostgresTest | 15 | 通过，含并发幂等及旧通知迁移 |
| RiskClearancePostgresTest | 61 | 通过 |
| SpaceRiskReadPostgresTest | 10 | 通过 |
| ConfirmedSpaceRiskPostgresTest | 57 | 通过 |
| NotificationMigrationMergePostgresTest | 9 | 通过 |

上述数据库专项不是全部环境门禁测试的执行声明。默认 Maven 回归中未设置专用环境变量或浏览器开关的用例仍按项目条件跳过。首次回归暴露的失败均保留在隔离工作目录日志中，修正后按受影响范围重新执行，并完成上述完整默认回归与打包。最终结果时间为 2026-10-12 07:32（America/New_York），日志保存在本次隔离工作目录的忽略路径 `artifacts/merge-verification/`。未据此声称完成真实设备联调或合并版本的浏览器业务验收。

## 代码变更说明

main 原有功能按合并历史保留。以下列出本次解决冲突和回归失败时额外调整的代码。

| 文件/类 | 方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| DeviceCommandController | `lingyunControl`、`countermeasure4ch` | 返回提交后的设备指令 | 使用提交人可访问的响应读取 |
| DeviceService | `submittedCommand` | 校验共享操作权限与当前提交人 | 前台成功提交后不再因后台监控读权限返回 403，后台 GET 权限保持 |
| `V202610120001__notification_departure_snapshot.sql` | 无具体方法，迁移文件编号 | 创建通知飞离快照列 | 仅改文件版本，内容和校验和不变，避开 main 光电迁移编号 |
| `reconcile_notification_departure_migration_20261012.sql` | 无具体方法，事务内校验和 UPDATE | 协调精确匹配的旧通知迁移历史 | 仅供获授权升级使用，异常历史拒绝，未对业务库执行 |
| NotificationMigrationMergePostgresTest | 迁移路径与拒绝协调用例 | 验证新安装及两条升级分支 | 在隔离 PG 中验证数据、校验和、安装事实保留及六类异常拒绝 |
| `maintenanceCommission.test.js` | `beforeEach`、`afterEach` | 安装与卸载组件夹具 | 清理 Element Plus 延迟解锁定时器，消除卸载后的未处理异常 |
| LocalDemoRolesSeederTest、LocalStage15DemoReviewerSeederTest、ProductionStage15SeedIsolationTest、Stage2CompatibilityMigrationTest、LocalStage2AccessSeederTest、BackendPermissionAssertions | 角色断言和后台权限断言 | 验证初始化权限边界 | 按前后台身份分离和固定 18 项后台权限检查，不为通过测试修改生产种子 |
| UpstreamAirspaceApiTest、AdvisoryVoiceRecordingApiTest、AutomationRuleApiTest、ExternalInterfaceApiTest、LocalPlanFilingApiTest、EvidenceChainApiTest | 测试身份设置及权限用例 | 验证动作与后台身份的共同约束 | 旧业务角色后台访问应拒绝，合法后台主体仍需对应业务动作授权 |
| DualChannelReceiptApiTest、DualChannelReceiptBrowserFixtureTest | 时钟模拟设置及复位 | 控制通知观察时间 | 复用继承的 AppClock spy，避免重复注册 |
| DisposalMqttFlowTest、LingyunControlMqttTest、MqttIngressTest、AutomationMqttFixture | 历史设备绑定夹具 | 验证已有设备协议兼容 | 历史类型通过仓储建立测试绑定，保留登记接口拒绝新建的断言 |
| DirectoryApiTest | 维护任务测试夹具 | 建立设备与任务范围关系 | 显式满足当前组织与区域范围契约 |
| EmergencyStopApiTest | 四通道前台提交参数化用例 | 验证提交、重复提交和拒绝 | 两种 mask 均返回 202，同键只入队一次，后台 GET 仍 403，无操作权限不入队 |
| LocalInterfaceSimulatorApiTest | 模拟器权限相关用例 | 验证前后台边界 | 保留较完整的本地权限覆盖，解决重复冲突 |
| ConfirmedSpaceRiskPostgresTest | 鸟群数量边界及高度事实断言 | 验证数量升级和高度基准 | 保留 main 的 19/10、20/25 与未知数量路径，也保留本地 AMSL 事实检查 |
| Stage9PostgresTest | C05 定时评估、鸟群历史、`spaceTarget`、`spaceMeasurement` | 构造真实匹配的空间与时间证据 | 使用活动规则、有效计划、AGL 和 MEAS，按计划定位风险；保持近远、去重和历史冻结断言 |
| HandoffFrozenEvidencePostgresTest | `cleanup` 与测试事务 | 隔离证据用例 | 事务回滚替代逐行删除只增历史，整个类结束才清理独立 schema |
| DeviceMaintenanceCasePostgresTest | 冷启动恢复用例的子进程启动 | 验证服务重启恢复 | 继承 Unix socket 临时目录配置，修复 Windows 中文路径下的测试启动失败 |
