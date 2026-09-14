# 阶段 10（B 线收尾与联调准备）验收记录

日期：2026-09-07。基线：`main@2ef0fc5`。计划 `docs/superpowers/plans/2026-09-07-collaborator-b-stage-10-closeout.md`，决策 `docs/backend-stage10/decisions.md`。

## 执行者报告
| 任务 | 会话 | 用例 | 报告 |
| --- | --- | --- | --- |
| 10.1 凌云 MQTT 回放导出 | Session 1 | `LingyunMqttReplayExporterTest` 6/6（180 行导出与 inbox `payload_hash` 逐行一致；决策 10-9 的仓库文件比对用例已验证会红），回归 9 类 44/44；生成物 `docs/直连接入计划/stage85-lingyun-demo.mqtt.ndjson`（180 行：radar 84 / tdoa 42 / eo-edge 42 / aoa 12）与《凌云回放说明》 | `task-10.1-report.md` |
| 10.2 `fusion_event` 摘要 + kind_code 归一 + 迁移 074 | 领导接管（后台代理） | 首轮 11 类 58/58；收尾（C02-8 归一、类别回退 10-11）6 类 42/42，`FusionEventPayloadTest` 4/4、`LocalStage7RuleEngineSeederTest` 6/6（含 074 在 H2 拒旧值） | `task-10.2-report.md` |
| 10.3 未映射路径 404 + 目录夹具 + PG 回归 | 领导接管（后台代理） | H2 `UnmappedPathApiTest` 4/4 + 五个目录断言类 + 回归 50/50；PG（16.9 + PostGIS 3.5.2）71/71：Stage85 15、Stage9 22（新增 074 拒旧值、旧值存在时 074 以 23514 失败且不写历史）、Stage8 7、Stage7 8、Stage5 5、Stage4 5、Stage2 兼容 3、FlightRead 1、AirspaceRead 3、TargetRead 2 | `task-10.3-report.md` |

## 10.4 领导验收
- 基线文档：`docs/后端开发基线.md` 新增 §3.1（阶段 7–9 与 8.5 交付事实）。
- H2 全量（`/private/tmp/dongying-mvn.sh test`，执行者全部落地后）：113 类 / 577 run / 0 fail / 0 err / 71 skip（skip 为 env 门禁的 PG 类）。
- PostgreSQL 16.9 + PostGIS 3.5.2（`stage456_verify_s85`）：`Stage9PostgresTest` 23/23（新增 21 拒旧值、22 旧值存在时 074 以 23514 明确失败且不写历史行、23 升级链 073.5→074 一次跑通并保留接替触发器）、`Stage85PostgresTest` 15/15、`Stage8PostgresTest` 7/7、`Stage7PostgresTest` 8/8、`Stage5PostgresTest` 5/5、`Stage4PostgresTest` 5/5；助手批次另含 Stage2 兼容 3/3、FlightRead 1/1、AirspaceRead 3/3、TargetRead 2/2（`stage2_target_verify_s85`）。
- API 路径：① 升级路径——用新 jar 以 local profile 启动已有验收库 `uav_stage85_verify`（已应用到 202609070010）：首次 074 以 "ck_stage9_airspace_kind_code … violated by some row" 失败（决策 10-5 印证；乱序迁移生效），加 073.5 后重启成功，`flyway_schema_history` 依次 073 → 073.5 → 074，`airspace_version.kind_code` 只剩五值；② 全新库 `uav_stage10_verify` 回放 v2 数据集 180 条 inbox 全部 DONE，`fusion_event` STATUS_STABLE 10 条，payload 含 `target_no`、`class_code`（8/10，其余无类别）、`latest_state`（位置、速度、航向、`altitude_datum=UNCONFIRMED`、`observed_at`，tdoa-pilot 目标含 `pilot_location`）、`alarm_active`（10/10）；③ 已登录 `GET /api/v1/no-such-path` 与 `/whatever` 返回 404 `NOT_FOUND` 包络且不回显路径，未登录仍 401。
- 审查（Session 4，6 轮）：P0 0、P1 0、P2 4（074 旧值前提 → 10-5；导出 `source` 键 → 10-8；`out-of-order` 生产落点 → 10-10 修订；Stage4/5 产物 → 本轮实跑）全部闭合。

## 未接入 / 未验证
- 联调（P6）等厂家资料；高度基准未确认。

## 补记（Session 3，提交后）
- 全套 PG 回归 73/73（十个套件，PostgreSQL 16.9 + PostGIS 3.5.2），`Stage9PostgresTest` 增第 24 例（已迁过 202609070001 的库遇 073.5/074：要么补上、要么响亮失败，不得静默跳过），随阶段 11 提交入库。
- 074 乱序在非 local 库的表现是 Flyway 校验阶段直接失败（不是静默跳过）→ 决策 10-17。
- 后续项：已映射路径用错 HTTP 方法（405）仍落到兜底 500，与 404 同类，待开单。
