# 阶段 8 验收记录（融合引擎，Demo Schema）

> 状态：已验收（2026-09-06 07:55）。没有运行证据的项不写"完成"。

## 8.0 基线
- `Stage8AccessControlServiceTest` 4/4（H2）；`AuditApiTest` 2/2；`Stage7AccessControlServiceTest` 4/4（公共文件改动后回归）。
- 本地开发库 `uav`：Flyway 乱序开启后 A 的 `202609050001` 与本阶段 `202609050050` 均已应用（`flyway_schema_history` success=t），`fusion_config` ACTIVE 一行。
- PG 隔离库 `stage456_verify_s8`（PostGIS 已装）已建。
- 前端本阶段不改（决策 8-11）。

## 8.1 / 8.2 / 8.3（执行者报告，领导复核 surefire）

| 任务 | 会话 | 用例 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| 8.1 摄取/回放/滤波/关联/ID | Session 1 | AlphaBetaFilter 5、AssociationCost 4、Associator 6、IdentityStateMachine 4、FusionPipelineReplay 7、FusionReplayReader 4、LocalStage8FusionReplaySeeder 3、Stage8AccessControlService 4 | 37 / 0 / 0 / 0（H2） | 引擎内自动 MERGE/SPLIT 未在 `processFrame` 触发（决策 8-16）；`association_pending` 只写 GATE_AMBIGUOUS |
| 8.2 融合/降级/读写接口 | Session 2 | WeightedFuser 5、DegradationEvaluator 2、AttributeSelector 3、FusionReadApi 7、FusionCommandApi 5、FusionConfigApi 3、TargetReadApi 14 | 39 / 0 / 0 / 0（H2） | 跨模式轨迹可见性回归已加（审查 P1-1 闭合）；`split` 只支持 `link_ids`（8-21） |
| 8.3 PG 专项/隔离/指标 | Session 3 | Stage8PostgresTest 7、ProductionStage8SeedIsolationTest 2、FusionMetricsApiTest 3 | 12 / 0 / 0 / 0 | Stage8PostgresTest 在真实 PostgreSQL 16.9 + PostGIS 3.5.2（库 `stage456_verify_s8`，随机 schema 已清理） |
| 审查 | Session 4 | 五轮，P0 0 / P1 4（全部闭合） | — | `.superpowers/sdd/review-log.md` |

领导集成改动（执行者报告之后）：`inbox_message.fusion_attempts` 与领取上限（8-17）、FUSED 层部分唯一索引、`FusedTrackRepository.lastPoint` 在 PG 用 `ST_AsText`（8-23）、`DefaultFusedLayerWriter` 终止帧保留位置（8-25）、`Stage8PostgresTest` 并发领取用例的竞态断言修正与领取上限断言。

## 8.9 集成验收（领导，2026-09-06）

### 自动化
| 项 | 命令 | 结果 |
| --- | --- | --- |
| H2 全量 | `./mvnw test`（经锁脚本） | 第一次 78 类 / 390 run / 0 fail / 0 err / 34 skip；集成修复后第二次 79 类 / 391 / 0 / 0 / 34（skip 均为条件式 PG 类） |
| 打包 | `./mvnw -DskipTests package` | 成功，jar 07:48 |
| PG 专项（库 `stage456_verify_s8`，PostgreSQL 16.9 + PostGIS 3.5.2） | `Stage8PostgresTest,Stage4PostgresTest,Stage5PostgresTest,Stage7PostgresTest` | 7/7、5/5、5/5、8/8（`Stage8PostgresTest` 并发领取用例首跑因竞态断言空集合报错，已改为并集断言并加领取上限断言后 7/7） |
| PG 回归（库 `stage2_target_verify_s45`） | `TargetReadPostgresApiTest,PostgresStage2CompatibilityTest,FlightReadPostgresApiTest,AirspaceReadPostgresApiTest` | 2/2、3/3、1/1、3/3 |
| 前端 | `npm run build`、`node tools/scan.cjs`、`node tools/falsify.cjs` | 构建通过；扫描"全部通过"；伪造检查"全部必抓注入均被捕获" |
| 差异 | `git diff --check` | 通过 |

### 真实 PostgreSQL 上的 API 路径（全新库 `uav_stage8_verify`，jar 8081，local profile，`app.fusion.enabled=true`；`admin1` API 登录）
1. 启动：迁移 0001–054 顺序应用；回放数据集 `stage8-fusion-demo` 132 帧全部 `DONE`；7 个回放目标（`目标-20250905-001…007`），FUSED 层 7 条、RAW 层 28 条，PRED 点 6 个，血缘 109 行，融合事件 7 行。
2. `GET /targets?source_mode=replay`：7 个回放目标各出现一次；**最新状态全部带位置**（修复 8-25 前 6 个无位置）；`fusion_confidence` 0.7 / 0.8。
3. `GET /targets/{001}`：`track_status TERMINATED`（数据集已结束）、`degradation NONE/deficit 0.3/determined`、`lineage_summary 58 次操作`、三条 link 带 `source_type/schema_status`（RADAR CONFIRMED，EO/TDOA DEMO）、`version`。
4. `GET /targets/{001}/tracks`：8 条（7 RAW + 1 FUSED，`link_id` 为空）；`?layer=FUSED` 只剩 1 条。`GET /tracks/{fused}/points` 默认 66 个全是 MEAS；`?kind=PRED` 1 个，位置等于最后可信点。
5. `GET /targets/{id}/lineage`（SWITCH/CREATE/STATUS，SYSTEM）、`/observations`（66 条，三源）、`GET /fusion/status`（三源 `online=false`、`data_interrupted=true`：数据集时间为 2025-09-05，等价"停源"态）、`GET /fusion/config`（demo-v1 ACTIVE DEMO，六个参数组）、`GET /fusion/metrics/daily`（2025-09-05：tracked 7、id_switch 81、interrupt_rate 0.0385，真值口径 `NO_GROUND_TRUTH`）。
6. 写接口：修订类别 201（version 0→1）；同版本再改 409 `VERSION_CONFLICT`；`CAT` 400 `INVALID_CLASS_CODE`；**两连接并发合并同一 survivor：一个 201、一个 409**；被并目标仍 200，`track_status MERGE`，`lineage_summary.current_target_id` 指向 survivor；按 link 分裂 201 产生新目标；激活已生效版本 409 `CONFIG_ALREADY_ACTIVE`。审计：成功 `targets_merged/target_split` SUCCESS，失败 FAILURE 均落 fusion 模块。
7. 浏览器（Vite 5174 → 8081）：`#/situation`（A 的页面，仍读 Mock）无脚本错误；`#/legality` 空态文案正确（新库无规则运行）；`#/workbench` 列表 11 项正常。控制台仅 Vite HMR WebSocket 与登录前 401，无页面错误。

### 提交排除清单
`dongying-vue/src/assets/css/reset.css`（其他会话未认领改动，决策 8-24）、`.superpowers/`、`.planning/`、`.worktrees/`、`.claude/`、根目录 docx、`设备资料/`、`docs/superpowers/`、`docs/系统开发进度说明-2026-09-05.md`。另一会话已提交的 `b2d1359`（overlay.css `.tabs .tab` 全局重置）影响 A 的页签样式，此处知会。

## 未接入 / 未验证
- 实测雷达 ops→阶段 2 提升（决策 8-1）；EO/TDOA/5G-A/融合箱真实字段（DEMO，待确认清单见对齐文档）；融合感知页接线（A，页面仍读 Mock）。
- 引擎自动合并/分裂未在管线内触发（8-16，人工接口可用）；`association_pending` 只写 GATE_AMBIGUOUS；`split` 只支持 `link_ids`（8-21）。
- 回放数据集固定在 2025-09-05，`fusion/status` 只能演示"全部离线/中断"态，在线态未在浏览器验证。
- FE-1（Session 5）前端可读性任务报告未收到，不在本次提交内。

## 跟进（审查第 6–13 轮遗留 P2，2026-09-06）
- 决策 8-27/8-28/8-29：`ProductionReportingSeedIsolationTest` 2/2；`DefaultFusedLayerWriter` 的 `endTrack` 移到迟到判定之后（`DefaultFusedLayerWriterTerminalFrameTest` 1/1、`FusionPipelineReplayTest` 7/7、`FusionReadApiTest` 7/7、`FusionCommandApiTest` 5/5）；`TargetReadApiTest` 14→15（`version` 契约断言）。
- 带 PG 环境变量复跑：`Stage8PostgresTest` 7/7（`stage456_verify_s8`，PostgreSQL 16.9 + PostGIS 3.5.2，随机 `stage456_` schema 已清理）、`TargetReadPostgresApiTest` 2/2（`stage2_target_verify_s45`）。
