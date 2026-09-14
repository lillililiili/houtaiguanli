# 阶段 4 验收记录：告警与飞行风险

- 日期：2026-09-05
- 基线：编制时 `main@1883a89`；执行中另一会话合并 `origin/main` 得到 `b9265a3`，阶段 4 在该基线上完成
- 交付提交：见 `git log`（`feat: stage 4 alarm verification and flight risk slice`）
- 领导会话：dongyiwurenji-fe；执行者：两个后台代理（4.1b/4.2b）+ 会话 dongyiwurenji-44（4.3 助手）；只读审查：会话 dongyiwurenji-2d（共 8 轮，日志 `.superpowers/sdd/review-log.md`）

## 1. 交付范围

| 区域 | 文件 |
| --- | --- |
| 权限与迁移 | `PermissionCode.java`（`alarm:verify`、`risk:read`、`risk:verify`）、`V202609050020__stage4_action_permissions.sql`、`V202609050021__uav_event_verification.sql`、`V202609050022__flight_risk_verification.sql`、`V202609050023__widen_audit_log_action_columns.sql` |
| 告警/无人机事件 | `modules/alarm/**`（`AlarmController` 原位替换、`UavEventController`、`AlarmReadService`、`UavEventVerificationService`、`UavEventState`、两个 Repository、`AlarmDtos`）、`integration/mock/LocalStage4AlarmSeeder.java` |
| 飞行风险 | `modules/risk/**`（`RiskController`、`RiskDtos`、`RiskIngestionService`、`RiskReadService`、`RiskVerificationService`、`RiskState`、`RiskRepository`）、`integration/mock/LocalStage4RiskSeeder.java` |
| 公共 | `GlobalExceptionHandler`（`/uav-events`、`/risks` 模块归类、审计文案）、`AuditLabels`（新模块/动作/路径中文）、`AccessControlMapper` 注释、`LocalStage2AccessSeeder`（遍历权限目录补授 admin1）、种子 `@Order` |
| 前端 | `services/apiClient.js`（`buildQuery`/`apiRequestTimed`/`isUncertainOutcome`）、`services/alarmApi.js`、`services/riskApi.js`、`ui/uavVerificationModal.js`（新）、`ui/riskVerificationModal.js`（改为 API 实现，导出名不变）、`pages/AlarmsPage.vue`、`pages/FlightsPage.vue`（风险页签）、`layout/HeaderBar.vue`（告警铃改读 API） |
| 测试 | `Stage4AccessControlServiceTest`、`AlarmReadApiTest`、`UavEventVerificationApiTest`、`LocalStage4AlarmSeederTest`、`RiskReadApiTest`、`RiskVerificationApiTest`、`LocalStage4RiskSeederTest`、`Stage4GlobalExceptionHandlerTest`、`Stage4PostgresTest`、`ProductionStage4SeedIsolationTest`、`LocalStage2AccessSeederTest`（改为按目录生成期望）、`PostgresStage2CompatibilityTest`（迁移计数改为按待执行数） |
| 文档 | `docs/backend-stage4/alarm-risk-api-contract.md`、本文 |

## 2. 自动化验证（实际运行）

| 命令 / 范围 | 结果 |
| --- | --- |
| `./mvnw -Dtest=Stage4AccessControlServiceTest,AlarmReadApiTest,UavEventVerificationApiTest,LocalStage4AlarmSeederTest,RiskReadApiTest,RiskVerificationApiTest,LocalStage4RiskSeederTest,LocalStage2AccessSeederTest,DeviceBusinessScopeTest,ProductionStage4SeedIsolationTest,AuthApiTest test`（H2） | 3+9+12+2+11+12+4+5+6+2+8 = 74，0 failures，0 errors，0 skipped |
| `./mvnw package`（H2 全量，无 PostgreSQL 环境变量） | 见第 6 节最终数字；条件式 PostgreSQL 用例按设计 skip |
| `Stage4PostgresTest`（PostgreSQL 16.9 / PostGIS 3.5.2，隔离库 `stage456_verify_s4a`，随机 `stage456_` schema） | 首次运行 5 run / 1 failure：`ck_stage4_risk_altitude_pair` 对 NULL 基准放行（`NULL IN (...)` 为 UNKNOWN）。迁移 022 加 `IS NOT NULL` 后重跑 **5/5 通过**（迁移、FK/唯一/CHECK、条件更新、两连接并发恰一条成功历史） |
| `TargetReadPostgresApiTest`（隔离库 `stage2_target_verify_s45`） | 2/2 通过：阶段 4 迁移未破坏阶段 2 目标读取 |
| `FlightReadPostgresApiTest`、`AirspaceReadPostgresApiTest` | 1/1、3/3 通过 |
| `PostgresStage2CompatibilityTest` | 原测试手抄迁移数量（自阶段 3 起已过期），改为按 Flyway 待执行数断言后通过（数字见第 6 节） |
| 前端 `npm run build` / `node tools/scan.cjs` / `node tools/falsify.cjs` | 通过（仅既有大 chunk 提示） |
| `git diff --check` | 通过 |

审计失败留痕缺陷：审查发现 `audit_log.action/object_id` 为 VARCHAR(64)，`POST /api/v1/uav-events/{36 位 UUID}/verifications` 的失败审计插入异常被处理器吞掉。迁移 023 加宽到 256，`UavEventVerificationApiTest` 改用 36 位 UUID 并断言失败审计恰一条。

## 3. 浏览器验收

环境：`npx vite --port 5174`（代理到 `127.0.0.1:8081`）；后端为 `./mvnw package` 产出的 jar，`--spring.profiles.active=local --spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/uav_stage45_verify`（隔离库，Flyway 应用到 031；开发种子双门禁开启）。账号 `admin1`（`ROLE-ADMIN`，`scope_mode=ALL`，开发种子补齐全部动作权限）。会话通过 API 登录后注入 `sessionStorage`，未在页面表单输入口令。视口 1280×720、1366×768、1440×900；`#/alarms`、`#/risk`、`#/workbench`、`#/punish` 均无 document 横向滚动。

| 步骤 | 结果 |
| --- | --- |
| 打开 `#/alarms` | 6 条种子告警、KPI（今日 6 / 待核实 4 / 反制中 尚未接入 / 干扰中 尚未接入 / 待处置 0 / 误报 0）、列排序按钮禁用并说明服务端固定排序；地图对无可信坐标目标显示“不以 (0,0) 补位，无法定位” |
| 选 `seed-stage4-alarm-same-target-a` → 人工核实 → 属实，说明含 `<b>xss-check</b>` | 200，状态“已核实，待处置”v1，历史文本按纯文本显示 `<b>xss-check</b>`；KPI 待核实 3 / 待处置 1；反制/干扰/通知按钮保持禁用并写明未接入 |
| F5 硬刷新 | 状态与历史仍在（服务端事实） |
| 选 `seed-stage4-alarm-evidence` 打开核实表单 → 用 API 在外部先核实一次（v0→v1）→ 提交旧表单 | 服务端 409，页面回读并关闭表单，提示“提交结果未确认，已刷新服务端状态”，历史显示 v1；未换键重试，未弹成功 |
| 打开 `#/risk` | 恢复 6 个 KPI、筛选工具条、三栏布局、事件/通报页签；地图绘制所选风险航线版本中心线；未支持筛选禁用并说明 |
| 选 `seed-stage4-risk-pending` → 人工核验 → 核验通过 | 状态“待通知”v1，核验按钮禁用并说明；历史 1 条 |
| 停止后端后 F5 `#/alarms` | 全部数据块显示“读取失败：服务返回异常（HTTP 500）”+重试，铃铛清空，无 Mock 回退 |

## 4. 只读审查结论

8 轮审查，阶段 4 相关 P1：审计列宽（已修）、种子测试期望过期（已修）、临时红灯误报（已复核）。最终 P0/P1 为零；P2 已处置或记录在 `review-log.md`。

## 5. 未验证 / 未接入

- 未创建第二个账号做“切换账号不看到前账号缓存”的浏览器验证（不在页面创建账号或输入口令）；范围隔离由 `AlarmReadApiTest`/`RiskReadApiTest`/`Stage4PostgresTest` 的跨元组用例覆盖。
- 未做移动端视口；本期目标视口为 1280–1440 宽。
- 联动反制、信号干扰、实时视频、轨迹回放、通知处罚：未接入，按钮禁用并注明原因。
- 生产告警/风险权威来源与生成规则未确认：生产读取返回真实空集合。
- `/auth/me` 的 `permission_codes` 仍只含模块级 `<模块>.read/.op/.auth`，页面不预判动作权限，以服务端 403 为准；是否暴露动作码留待阶段 6 全角色验收决定。

## 6. 最终全量数字

| 运行 | 结果 |
| --- | --- |
| `./mvnw package`（H2，07:20，无 PostgreSQL 环境变量） | 46 个测试类，225 run，0 failures，0 errors，19 skipped（全部为条件式 PostgreSQL 用例：PostgresStage2CompatibilityTest 3、TargetReadPostgresApiTest 2、FlightReadPostgresApiTest 1、AirspaceReadPostgresApiTest 3、Stage4PostgresTest 5、Stage5PostgresTest 5）；BUILD SUCCESS，产出 `target/low-altitude-server-0.1.0-SNAPSHOT.jar` |
| PostgreSQL 专项（PostgreSQL 16.9 / PostGIS 3.5.2，`stage456_verify_s4a`） | Stage4PostgresTest 5/5、Stage5PostgresTest 5/5、PostgresStage2CompatibilityTest 3/3、FlightReadPostgresApiTest 1/1、AirspaceReadPostgresApiTest 3/3 |
| PostgreSQL 专项（`stage2_target_verify_s45`） | TargetReadPostgresApiTest 2/2 |
| 隔离库残留检查 | 两个隔离库均无遗留 `stage456_` schema |
| 前端 | `npm run build` 通过；`node tools/scan.cjs` 全部通过；`node tools/falsify.cjs` 全部必抓注入均被捕获 |

提交方式说明：阶段 4 与阶段 5 共享 `PermissionCode`、`GlobalExceptionHandler`、`AuditLabels`、`apiClient.js`，且 `FlightsPage.vue` 的通知按钮已接 `handoffApi.js`，无法在不伪造中间版本的前提下拆成两个各自可构建的提交，因此领导以一个提交交付阶段 4+5；本文与阶段 5 验收记录分别记录各自证据。
