# 阶段 9 验收记录（飞行监管补齐与第二业务线）

> 状态：已验收（2026-09-07）。没有运行证据的项不写"完成"。

## 9.0 基线
- 合并 A 的证据底座（`bdf5b40`）后在 `7b83d7a` 提交：权限 060、导航/别名改造、`FlightsPage` 去掉 hash 互写、契约 v1.0；`Stage9AccessControlServiceTest` 3/3。

## 9.1 / 9.2 / 9.3 / 9.4（执行者报告，领导复核 surefire）

| 任务 | 会话 | 用例 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| 9.1 空域写模型/GeoJSON 导入/差异/空域页 | Session 1 | GeoJsonParser 11、AirspaceWriteApi 10、AirspaceImportApi 6、AirspaceDiffApi 3、LocalStage9AirspaceSeeder 3（+AirspaceReadApi 10、Stage9Access 3 回归） | 46 / 0 / 0 / 0（H2） | 并发建同编号 409（审查 P2-1）；几何种类由解析器把关（PostGIS 会把 POLYGON 自动升为 MULTIPOLYGON）；只读事务里的 FOR UPDATE 在 PG 报 25006 已改（用例 12 钉住） |
| 9.2 计划与实际对照/外部授权登记/飞行计划页区块 | Session 1 | FlightActualsApi 5、PlanAuthorizationApi 6、FlightReadApi 5 | 16 / 0 / 0 / 0（H2） | 决策 9-25/9-27/审查 P2 已落实；`FlightsPage` 只在详情栏原地接数据并追加"外部授权登记"区块，页签/列表/地图不动 |
| 9.3 空间风险读侧/C04-C05/机场/空间风险页 | Session 2 | C04DecisionTable 9、SpaceRiskEvaluationGuard 3、SpaceRiskReadApi 6、RuleEvaluationApi 3、AirportApi 3、RiskReadApi 12、LocalStage9SpaceRiskSeeder 4 | 40 / 0 / 0 / 0（H2） | 决策 9-16…9-24 全部落地；无裸阈值（全部经 `rule_param`）；观测查询 LEFT JOIN 计划（无计划只计数） |
| 9.4 PG 专项/生产隔离/指标口径 | Session 3 | Stage9PostgresTest 18、ProductionStage9SeedIsolationTest 2、`space-risk-metrics.md` | 20 / 0 / 0 / 0，PG 18/18 | PostgreSQL 16.9 + PostGIS 3.5.2，库 `stage456_verify_s9`；接替式触发器逐分支、导入几何按机制、并发建号/并发确认/并发登记、C04 端到端、种子在 PostGIS 上产出评估风险 |
| 审查 | Session 4 | 八轮，P0 1（迁移 062 缺列，当轮闭合）/ P1 4（全部闭合） | — | `.superpowers/sdd/review-log.md` |

领导集成改动：决策 9-28（合法性引擎只取含 C03 成员的规则集，`RuleEngineRuleSetSelectionTest` 2/2）、9-30（阶段 7 生产隔离断言允许迁移登记的 SPACE-RISK-DEMO）、`SystemManagementApiTest` 菜单目录加 airspace/risk、`Stage7PostgresTest` 同 9-30、9-31/9-32（空间风险页编号列与分页组件传参）。

## 9.5 集成验收（领导，2026-09-07）

### 自动化
| 项 | 命令 | 结果 |
| --- | --- | --- |
| H2 全量 | `./mvnw test`（锁脚本） | 100 类 / 506 run / 1 fail / 0 err / 54 skip → 唯一失败是 `SystemManagementApiTest` 菜单目录（阶段 9 新增两个菜单的预期漂移），改断言后 9/9；skip 均为条件式 PG 类 |
| PG 专项（`stage456_verify_s9`，PostgreSQL 16.9 + PostGIS 3.5.2） | `Stage9PostgresTest,Stage8PostgresTest,Stage7PostgresTest,Stage5PostgresTest,Stage4PostgresTest` | 20/20、7/7、8/8（原 1 失败为迁移 062 登记 SPACE-RISK-DEMO 的预期漂移，改断言后通过）、5/5、5/5 |
| PG 回归（`stage2_target_verify_s45`） | `TargetReadPostgresApiTest,PostgresStage2CompatibilityTest,FlightReadPostgresApiTest,AirspaceReadPostgresApiTest` | 2/2、3/3、1/1、3/3 |
| 打包 | `./mvnw -DskipTests package` | 成功 |
| 前端 | `npm run build`、`node tools/scan.cjs`、`node tools/falsify.cjs`、`check-ui-text.sh`（AirspacePage/SpaceRiskPage 零命中，FlightsPage 仅既有 2 处） | 全部通过 |
| 差异 | `git diff --check` | 通过 |

### 真实 PostgreSQL 上的 API 与浏览器路径（全新库 `uav_stage9_verify`，jar 8081，Vite 5174，`admin1` API 登录注入会话）
1. 启动：迁移 0001–064 全部应用；种子在 PostGIS 上跑出 C04 评估：`rule_evaluation_run` SUCCESS（targets_seen 3、risks_created 4），走廊内 1 条 HIGH（INSIDE/CLIMB/AMSL）、3 条 MEDIUM（高度基准不同或邻近），机场 1 座。
2. 空域：`POST /airspaces/import-batches` 粘贴 GeoJSON（MultiPolygon + Polygon + LineString）→ 2 项 accepted、LineString 项 `GEOMETRY_NOT_SUPPORTED`+`VALID_FROM_MISSING` → `confirm` 201（新空域 1、新版本 2）→ 再次 confirm 409 → `KY-9-002` 第 1 版 `valid_to` 被关闭为第 2 版 `valid_from`，其余列不变 → `diff` 给出字段差与几何差（面积变化）→ `assessment_result` 13 行未变。页面：列表/详情/版本时间线（第 2 版生效中、第 1 版已被接替）/差异/相关航线只读/地图范围；1280/1366/1440 无横向滚动。
3. 空间风险：`GET /space-risks/summary` 按细类/等级/状态/高度带聚合；`GET /risks/{id}/space-fact` 含规则版本、走廊关系、高度带、`unknown_reasons`、坐标快照；页面 6 个 KPI、三栏、事件/通报页签、细类/等级/状态筛选、详情 `SPACE-RISK-DEMO 第 1 版 · 演示参数`、"尚缺事实"、驱鸟处置标"尚未接入"；深链 `#/risk?risk=<id>` 选中该风险；验收发现并修复分页组件传参错误（9-32）与编号列显示技术键（9-31）。
4. 飞行计划：`GET /flight-plans/{id}/actuals` 五段各带 availability；`POST …/authorizations` 201、同文号再登记 409；页面详情"外部授权登记"区块显示登记记录，"计划与实际对照/合法性"无研判时如实显示"尚无引擎研判"，events 页签仍在，无横向滚动。
5. 权限：新建只有 `flights READ` 的角色与用户（首次登录改密后），侧栏只有"我的工作台/飞行计划"，`#/airspace`、`#/risk` 直达"无法访问…需要 airspace/risk 查看权限"，对应 API 403。
6. 未映射路径返回 500 而非 404（平台既有行为，`GlobalExceptionHandler` 兜底且未配 `throw-exception-if-no-handler-found`），不在阶段 9 范围，留待后续单独处理。

## 未接入 / 未验证
- C04 的数量/趋势输入无数据源（9-17）；定时任务单实例保护仅进程内（9-20）；C05 机场只有骨架与只读数据，无通报通道；`kind_code` 字典保留两个历史写法待阶段 7 种子归一后收紧（9-22）。
- 阶段 8 遗留：引擎自动合并/分裂未在管线触发；凌云协议映射（阶段 8.5）未开始。
- 未映射路径 500 而非 404（平台级）。
- 执行会话未落盘截图（9-29）；浏览器路径由领导在本节记录。

## 提交后补充（2026-09-07，阶段 9 收尾）
- 助手补齐 `ProductionStage9SeedIsolationTest`：迁移 061/063/064 的九张业务数据表在 `production` / `production,local` 下逐表断言为空，并按 id 前缀点名核对阶段 9/阶段 3 种子行；目录表（`space_object_subtype`、`SPACE-RISK-DEMO` 结构与参数、`rule-engine-space-risk-*` 来源行）按决策 9-16 允许存在但不激活。2/2（隔离 H2）。`Stage9PostgresTest` 终态 20/20（并发登记同 `document_no` 一成一 409、决策 9-28 引擎只取含 C03 的规则集）已在 236af2c 内。
- 知会协作者 A（审查 P2-1）：`dongying-vue/src/ui/labels.js` 的 `LEGALITY_LABEL.UNDETERMINED` 由"待确认"改为"不可判定"，大屏 `bigscreen/BigScreenApp.vue` 消费同一字典，文案随之变化；`RuleEngineRepository.ruleSetsWithVersions()` 只取成员含 C03 的规则集（9-28）。
