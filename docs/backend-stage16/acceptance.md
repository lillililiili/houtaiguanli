# 阶段 16 验收记录：代码收尾（自动合并/分裂、空域孔洞、动作码下发、Worker 恢复证据）

基线 `012e4af`。计划 `docs/superpowers/plans/2026-09-09-collaborator-b-stage-16-code-wrapup.md`，决策 16-1…16-8 见 `decisions.md`。红线：另一个会话（改 bug）的未提交文件全程未触碰（审查者以 49 个文件的 SHA-256 基线逐轮比对）。

## 执行者报告
- 16.1（E1）自动合并/分裂：H2 全量 911 例 0 红；三处按实测偏离简报（ENU 换算、SPLIT 追加不改写、同点双回波场景），见 16-1 修订；回放数据集新增 `converge-merge`/`near-echo`。
- 16.2（E2）空域孔洞：`situationData.test.cjs` 70 条、scan/build/check-ui-text 零命中；渲染层像素实测（带孔 vs 实心差 738 像素，包围盒与孔洞四角吻合）；截图 `.superpowers/sdd/shots-16.2/{situation-hole,bigscreen-airspaces}.png`（经 5174 代理打到了另一会话的 8082，临时空域 `空域-162H` 留在 `uav_stage15_fresh`，已知会）。
- 16.3（E1）`/auth/me` 动作码：912 例 0 红；`docs/系统管理接口.md` 那句"返回形式为 `{code}.read|op|auth`"是真错误，已拆成模块码/动作码两说；`LocalStage2AccessSeederTest` 的"不含冒号"断言换成"两种码不串味"。
- 16.4（助手）：`FusionInboxRecoveryTest` 3/3 + `OutboxWorkerRecoveryTest` 4/4（连跑三遍）；`Stage16PostgresTest` 6/6（真 PG，两遍一致）；五处变异各自变红。
- 16.5（E1）`GET /targets` 默认排除 `track_status=MERGE`，`include_merged=true` 恢复：相关四套件 45/45。
- 16.6（E1）新增场景挪到 `stage16-fusion-merge-demo`，`stage85-lingyun-demo` 恢复 180 行原样（A 的联调件不变）；`alreadyLoadedV2()` 改按自身来源计数（审查 P2-1）；921 例 0 红。
- 16.7（E1）合并候选两侧必须本帧被观测命中：922 例 0 红 / 108 skip；`FusionPipelineMergeCandidatesTest` 先红后绿。

## 领导验收（干净树打包：HEAD + 仅阶段 16 产品文件，不含另一会话的在途文件）
| 路径 | 库 | 结果 |
| --- | --- | --- |
| 全新安装 | `uav_stage16_fresh`（PostGIS） | 70 迁移；stage85 126 行 + stage16 24 行全部 DONE；SYSTEM 血缘 MERGE×1 + SPLIT×1；`association_pending` MANY_TO_ONE→MERGED、ONE_TO_MANY→SPLIT、ONE_TO_MANY→EXPIRED 各 1；链检测 0；`GET /targets` 30 条、`include_merged=true` 31 条；被并者详情仍 200；`target.version` 均 0；reviewer1 `/auth/me` 含 16 个动作码、无 `fusion:manage` |
| 升级路径 | `uav_stage16_upgrade`（先用 16.1 之前的 round12 包灌过：180 行旧回放、27 目标、长期 STABLE 种子目标若干） | 新包正常启动；stage85 行未动、stage16 24 行灌入；SYSTEM MERGE 恰 1 次（converge 目标吸收失去链接的重复目标）；链检测 0；列表 30/31。**触发条件在场**：`目标-20250905-008`（阶段 8 种子，links=seed-stage8-source-eo/T-EO-TRACK + tdoa/D-PILOT，与 stage10 上被并成链的成员同一来源）STABLE、同域、距 converge 合并点 30 m、本轮不可能被观测（stage16 只有雷达一个来源），16.7 后未被并、无 pending 涉及它 |
| 升级路径（16.7 之前，同样的干净包） | `uav_stage10_verify` | 能启动、数据集灌入、列表过滤正确，**但**自动合并把 converge 目标经无来源链接的种子目标吸收成链（MERGE×2、链检测 1）→ 16-8；该库上的旧链按只增血缘永久保留，回归以 06:50:18 UTC 为时间边界 |
| 整树打包（含另一会话在途文件） | 全新库 | 启动即退：其 `LocalDemoVolumeAlarmSeeder(@Order 67)` 给阶段 7 `merge` 目标插告警，`RuleReplayRunner(@Order 70)` 断言失败——已报该会话并由其改为 @Order 72；另在已灌旧回放的库上 `SOURCE_MESSAGE_CONFLICT`（16.1 改了同一 dataset_id 的内容）→ 16-7 |

## 未接入 / 已知限制
- 陈旧 STABLE 种子目标不会被 `onFrame` 老化（帧时刻不晚于其参考时刻时 `gap ≤ 0`），只报不改：牵涉乱序回放语义，不是一行能修。
- 大屏 `HEIGHT_LIMIT`/`SUITABLE` 两个本地码不在库字典内，不补映射。
- `TERMINATED` 目标仍在默认列表（本轮只处理 MERGE）。

## 助手最终回归（16.7 之后，8081 于 06:50:18 UTC 重启）
- 真 PG（各自 `stage456_verify_*` 隔离库，无残留 schema）：Stage16PostgresTest 6/6、Stage8PostgresTest 7/7、Stage85PostgresTest 15/15、Stage15PostgresTest 9/9；ProductionStage15SeedIsolationTest + FusionInboxRecoveryTest + OutboxWorkerRecoveryTest 10/10。E2E 40/40 两遍（`disposal-actions.spec.js` 改为向服务端询问哪条交接带处置授权再点该行：另一会话新灌的 `seed-vol-pcase-*` 交接排到了阶段 13 那条前面，原夹具"默认选中行就有授权"的假设失效，第一遍 39/40 被条数差抓住）。
- 16-8 链检测：`uav_stage10_verify` 重启后无帧可处理（回放行全 DONE），该库上的 0 不构成证据；`uav_stage16_upgrade` 真跑了 24 帧，MERGE 恰 1、链 0，且触发条件在场（见上表 `目标-20250905-008`：陈旧 STABLE、同域、距 converge 目标 30 m、本轮不可观测）。助手按"两个陈旧目标互距"量得 1728 m 认为无触发条件，领导按"陈旧目标 ↔ 活目标"口径判定在场；正例（positive control）只有 stage10 上 16.7 之前的实跑记录（MERGE×2、链 1），没有用带缺陷的包在同一夹具上复跑（该包已被覆盖，round12 包没有自动合并不能作正例）。如实记录：库层负例带触发条件、正例为历史记录，非同一夹具上的成对实验；**成对实验在单元层是有的**——`FusionPipelineMergeCandidatesTest.aStableTargetWithNoObservationThisFrameIsNeverAMergeCandidate` 先红后绿，变量只有 `entry.getValue().isEmpty()` 那一处（审查第 8 轮指出）。门限按 `2.0 × max(σ)`、008 的 EO/TDOA 精度 25/60 m 算为 50–120 m，30 m 远在门内，不是边界情形。

## CI
- 34323041306（`802efd2`）：backend 918/0/0/0 跳 0，frontend 通过，e2e 39/40（`disposal-actions` 在干净库上无可动作行，16.8 处理）。
- 16.8（助手）：夹具自给后，8083 全新库（与 CI 同态）40/40 两遍、8081 40/40 三遍；先在干净库复现了 CI 那句 `没有任何一行是可动作的`，再转绿。
- 34327892190（`f8b83af`，含 `05ea291` 与 A 的工作台设备事件提交）：**三个作业全部 success**——backend 923/0/0/0 跳 0，frontend 通过，e2e 40/40。阶段 15 起的 CI 门禁至此首次全绿。
