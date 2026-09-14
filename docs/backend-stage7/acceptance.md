# 阶段 7 验收记录：规则引擎与合法性判定

- 日期：2026-09-06
- 基线：`main@cb84150`（阶段 4/5 交付 `f1ae075` 之后，另一会话提交的业务编号/中文名称显示）
- 交付提交：见 `git log`（`feat: add stage 7 rule engine and legality evaluation slice`）
- 分工：领导 dongyiwurenji-da（原 -fe）；执行者 1/2、助手、审查者为本会话后台代理（跨会话消息工具在会话重建后不可用）；Session 1 会话贡献真实 PostgreSQL 回填修复；审查 3 轮，日志 `.superpowers/sdd/review-log.md`
- 决策清单：`docs/backend-stage7/decisions.md`（7-1 … 7-25）

## 1. 交付范围

| 区域 | 文件 |
| --- | --- |
| 权限与迁移 | `PermissionCode`（`rule:read/manage`、`assessment:evaluate/revise/escalate`）、`V202609050040`（目录、规则引擎三来源行、`assessment_result` 新列 + `ABNORMAL`）、`V202609050041`（规则集/版本/成员/参数/激活历史/运行/研判/租约）、`V202609050042`（复核、复核历史、告警合并组/成员、视图 `v_rule_effect_fact`）、`db/postgresql/R__stage7_rule_engine_constraints.sql`（研判/运行只增与一次性回填、已发布参数不可改、回放运行部分唯一、复核历史只增、OPEN 合并组部分唯一） |
| 引擎 | `modules/assessment/engine/**`：`RuleContracts`、`RuleCodes`、`RuleParamLoader`、`checks/*`（C01 `PlanMatchCheck`、C02-1…C02-8）、`C03Decision`、`PostgisSpatialFactAdapter`、`LegalityEvaluationService`、`RuleRunService`、`RuleEngineWorker`、`RuleEngineRepository`、`RuleEngineHooks(+Impl)`、`PlanMatcher` |
| 告警生成 | `modules/alarm/application/{AlarmIngestionService,TrustedAlarmFact,AlarmMergePolicy}`、`infrastructure/AlarmMergeRepository` |
| 接口 | `RuleSetController`、`RuleRunController`、`LegalityEvaluationController`、`RuleEffectController` 与对应 DTO/Service/Repository（`RuleSetManagementService`、`LegalityReviewService`、`AlarmEscalationService`、`LegalityEvaluationReadService`、`RuleEffectReadService`） |
| 种子 | `LocalStage7RuleEngineSeeder @Order(65)`（规则集 v1 ACTIVE / v2、P1/H1/T1 空域、十场景）、`RuleReplayRunner @Order(70)` |
| 公共 | `GlobalExceptionHandler.module()`（assessment/rules/flights/airspace）、`AuditLabels`（`Map.ofEntries`、新动作/路径）、`application.yml` `app.rule-engine.*`（默认关闭） |
| 前端 | `services/legalityApi.js`、`ui/legalityReviewModal.js`（复核/重算/转告警/手动评估表单）、`pages/LegalityPage.vue`（DOM 保留；队列读引擎研判、命中明细、复核历史、告警链接、规则版本弹窗、KPI）、`ui/labels.js`（气球/风筝/孔明灯移入推断 subtype） |
| 测试 | `Stage7AccessControlServiceTest`(4)、`C03DecisionTest`(8)、`C02ChecksTest`(10)、`RuleSetManagementApiTest`(7)、`LegalityEvaluationServiceTest`(7)、`PlanMatchCheckTest`(7)、`AlarmIngestionServiceTest`(4)、`AlarmMergePolicyTest`(7)、`LegalityReviewApiTest`(10)、`RuleEffectApiTest`(2)、`LocalStage7RuleEngineSeederTest`(5)、`RuleReplayRegressionTest`(3)、`Stage7PostgresTest`(8)、`ProductionStage7SeedIsolationTest`(2)；`AirspaceReadApiTest` 断言改按归属隔离 |
| 文档 | `docs/backend-stage7/rule-engine-api-contract.md`、`decisions.md`、本文 |

## 2. 自动化验证（实际运行）

| 运行 | 结果 |
| --- | --- |
| `./mvnw package`（H2 全量，03:37，无 PostgreSQL 环境变量） | 60 个测试类，309 run，0 failures，0 errors，27 skipped（条件式 PostgreSQL：阶段 2/3 各库 9、Stage4 5、Stage5 5、Stage7 8）；BUILD SUCCESS |
| PostgreSQL 专项（PostgreSQL 16.9 / PostGIS 3.5.2，隔离库 `stage456_verify_s7`） | `Stage7PostgresTest` 8/8（迁移 040–042 + R__stage7；只增触发器与一次性回填；已发布参数不可改；回放运行部分唯一；十场景结论与原因码；C06 同目标两次 ILLEGAL 一条告警 + MERGED；两连接并发复核一成一 409）；`ProductionStage7SeedIsolationTest` 2/2；`RuleReplayRegressionTest` 3/3；隔离库无遗留 `stage456_` schema |
| 真实 PG 暴露并修复的缺陷 | 回填 `alarm_outcome` 的 `COALESCE(jsonb, CAST(? AS JSON))` 混型导致 ACTIVE 研判在 PG 全部回滚；改为逐列"仅当为 NULL 时写入"（决策 7-25） |
| 前端 | `npm run build` 通过；`node tools/scan.cjs` 全部通过；`node tools/falsify.cjs` 全部必抓注入均被捕获；`git diff --check` 通过 |

## 3. 浏览器验收

环境：`npx vite --port 5174` 代理到 8081；后端为 `package` 产出的 jar（`--spring.profiles.active=local --spring.datasource.url=…/uav_stage45_verify --app.rule-engine.enabled=true --app.rule-engine.allow-demo-active=true --app.rule-engine.replay.run-on-start=true`）；Flyway 应用到 042 + R__stage7；开机回放生成 11 条研判。账号 `admin1`（ALL），会话经 API 登录注入，未在页面输口令。视口 1280×720、1366×768、1440×900，`#/legality` 无 document 横向滚动。

| 步骤 | 结果 |
| --- | --- |
| API：十场景结论 | legal→LEGAL/FULL；deviation→ABNORMAL；airspace-limit→ILLEGAL；plan-altitude→ABNORMAL；boundary→UNDETERMINED；no-plan→ILLEGAL/NONE；degraded、datum-mismatch→UNDETERMINED；merge→ILLEGAL 且告警"并入既有告警"；cross-scope 对 ALL 账号可见（跨元组隔离由测试覆盖）；`summary`：evaluations 11、alarm_worthy 7、created 6、merged 1，分母 0 的比率返回 `NO_DENOMINATOR` |
| 打开 `#/legality` | KPI 来自 `rule-effects/summary`；四个页签按 `legal_status`；命中明细表逐条显示规则/结果/参数状态（DEMO 徽标）/原因码；证据引用列出 target/track/plan/route_version/airspace_version；地图区明示"可信输入几何尚未接入" |
| 人工复核（CONFIRM，说明含 `<b>x</b>`） | 200，`已确认 v1`，复核历史 1 条，文本按纯文本显示；F5 后仍在 |
| 重新研判（说明必填） | 新研判 `62e5bc8b…` 取代旧研判（旧行 review→SUPERSEDED，新行带"← 被取代的旧研判"链接）；`assessment_result` 新增投影行 |
| API：激活 v2 → 回滚 v1 → 设影子 v2 | 三次 200，激活历史 ACTIVATE/ROLLBACK/SHADOW_SET，头行版本 1/2/3；DEMO 门由 `allow-demo-active=true` 放行 |
| API：影子研判 deviation | 影子行落库（v2），默认 ACTIVE 队列不含、无告警、无复核行；页面显示"阴影运行中"提示 |
| 转告警（datum-mismatch，说明必填） | 201，告警 `b3226510…` + 事件 `cce36e84…`（PENDING_VERIFICATION，`allowed_actions=[VERIFY]`）；告警页出现 `RULE_LEGALITY · 平台规则引擎（模拟来源）` 待核实；复核历史 ESCALATE |
| 转入处置 | 保持禁用"尚未接入" |

## 4. 审查结论

3 轮审查：第 1 轮 P1 2（偏航场景不可达、复核历史只增触发器缺失）与 P2 若干，全部闭合；第 2 轮 P0/P1 零，P2（注释、重算说明表单）已修；第 3 轮为集成后只读复核（见 review-log）。

## 5. 未验证 / 未接入

- 飞手位置/超视距、身份线索、起降点、飞手/单位：恒"线索缺失/未接入"。
- 规则参数全部 DEMO（`param_status=DEMO`），生产默认无 ACTIVE 规则集且 `allow-demo-active=false`。
- 空间证据几何未在页面绘制（研判读接口不返回几何）。
- 未做第二账号切换（不在页面创建账号或输口令）；跨元组由 API 测试覆盖。
- 合法性页加载时同一队列请求重复触发（审查第 3 轮评估），如为 P2 记入后续。
