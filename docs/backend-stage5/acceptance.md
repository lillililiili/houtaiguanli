# 阶段 5 验收记录：工作台与业务交接

- 日期：2026-09-05
- 基线：`main@b9265a3` + 阶段 4 交付
- 交付提交：见 `git log`（`feat: stage 5 workbench aggregation and risk handoff`）
- 领导会话：dongyiwurenji-fe（Task 5.0/5.3）；执行者 1：会话 dongyiwurenji-6e（5.1）；执行者 2：会话 dongyiwurenji-de（5.2）；助手：会话 dongyiwurenji-44（Stage5PostgresTest、ProductionStage5SeedIsolationTest）；只读审查：会话 dongyiwurenji-2d

## 1. 交付范围

| 区域 | 文件 |
| --- | --- |
| 权限与迁移 | `PermissionCode.java`（`workbench:read`、`handoff:read`、`handoff:create`）、`V202609050030__stage5_permissions_and_device_scope.sql`、`V202609050031__handoff_submission.sql` |
| 设备业务范围 | `modules/device/infrastructure/DeviceBusinessScopeRepository.java`、`integration/mock/LocalStage5DeviceScopeSeeder.java` |
| 工作台 | `modules/workbench/**`（`WorkbenchController`、`WorkbenchReadService`、`WorkbenchReadRepository`） |
| 交接 | `modules/handoff/**`（`HandoffController`、`HandoffDtos`、`HandoffSubmissionService`、`HandoffReadService`、规则与 Repository）、`integration/mock/LocalStage5HandoffSeeder.java` |
| 公共 | `GlobalExceptionHandler`（`/handoff`、`/workbench` 模块归类）、`AuditLabels` |
| 前端 | `services/workbenchApi.js`、`services/handoffApi.js`、`services/workbenchEvents.js`（后端摘要/导航适配，无内存状态）、`pages/WorkbenchPage.vue`、`pages/PunishPage.vue`（交接清单/材料/投递，处罚区禁用）、`pages/FlightsPage.vue`（通知上级与通报页签） |
| 测试 | `DeviceBusinessScopeTest`（6）、`WorkbenchReadApiTest`（10）、`HandoffApiTest`（16）、`LocalStage5HandoffSeederTest`（4）、`Stage5PostgresTest`（5）、`ProductionStage5SeedIsolationTest`（2） |
| 文档 | `docs/backend-stage5/workbench-handoff-api-contract.md`、本文 |

## 2. 自动化验证（实际运行）

| 范围 | 结果 |
| --- | --- |
| H2 定向：DeviceBusinessScopeTest / WorkbenchReadApiTest / HandoffApiTest / LocalStage5HandoffSeederTest | 6 / 10 / 16 / 4，均 0 failures，0 errors |
| `Stage5PostgresTest`（PostgreSQL 16.9 / PostGIS 3.5.2，隔离库 `stage456_verify_s4a`） | 5/5：迁移 030/031 应用；三外键、逻辑唯一、CHECK 由数据库拒绝；快照存为 jsonb 对象且 `schema_version=1`；同风险同接收方两连接并发恰一份交接、输家 409 且幂等占位回滚；不同接收方各一份；风险状态/版本不变 |
| `ProductionStage5SeedIsolationTest` | 2/2：`production`、`production,local` 下设备映射与交接种子均不注册、无 seed 行 |
| 全量 `./mvnw package`（H2） | 见阶段 4 验收第 6 节同一次运行 |
| 前端 build / scan / falsify、`git diff --check` | 通过 |

## 3. 浏览器与 API 验收（5.3 路径）

环境同阶段 4 验收（Vite 5174 → jar 8081，隔离库 `uav_stage45_verify`，账号 `admin1`）。

| 步骤 | 结果 |
| --- | --- |
| 风险 `seed-stage4-risk-pending` 核验通过后点“通知上级” | 弹出接收方选择（2 个 local/test 接收方）；提交后 201，提示“已提交，尚未发送”，投递状态“待投递 · 通知渠道未接通”，链接 `#/punish?handoff=<id>` |
| 打开链接进入 `#/punish` | 交接清单 3 条（1 条新提交 + 2 条种子），详情含材料快照（风险字段、关联引用、核实历史 1 条）、投递记录 1 条待投递；处罚案件/罚款/文书/证据/反制授权区域全部“本期未建设”并禁用 |
| F5 硬刷新 `#/punish` | 记录仍在 |
| API `GET /risks/seed-stage4-risk-pending` | 仍为 `PENDING_NOTIFICATION` v1，`allowed_actions=[]` |
| API 再次提交同事项同接收方（新幂等键） | 409 `HANDOFF_ALREADY_EXISTS`，交接总数仍为 1 |
| API `UAV_PUNISHMENT` 交接 | 409 `HANDOFF_PREREQUISITE_UNAVAILABLE` |
| API body 夹带 `delivery_status` | 400 `UNKNOWN_FIELD` |
| `#/workbench` | 11 条事项由后端统一排序；已核实事件显示“已核实，待处置”且“联动反制”按钮禁用并说明未接入；风险显示“待通知”与源页一致；设备块显示“设备归属映射未配置”（local 无运维设备），不填 0；详情“交接记录 0 条”与实际一致 |
| 视口 1366×768、1440×900 | `#/workbench`、`#/alarms`、`#/risk`、`#/punish` 无 document 横向滚动 |

双窗口重复提交：浏览器侧以“同事项再次 POST（不同幂等键）→ 409 仅一份”验证，真实两连接并发由 `Stage5PostgresTest` 覆盖。

## 4. 只读审查结论

阶段 5 相关 P1：工作台夹具删种子接收方（已改为停用）、PunishPage 权限预判锁死（已删预判以 403 为准）、工作台通知按钮词典误判（已改）；契约裁定：接收方目录 `handoff:read` 或 `handoff:create` 任一可读；材料读取在缺 `risk:read` 或源风险不可见时省略并给 `availability.material`。最终 P0/P1 为零。

## 5. 未验证 / 未接入

- 真实通知渠道、送达与回执：未接入，只生成 `PENDING_DELIVERY`；不存在送达写接口。
- 无人机处罚交接：缺反制/干扰完成事实，一律 409 阻断。
- 设备异常恢复/关闭：无后端命令，工作台只读并禁用。
- 真实运维设备归属：本机 local 库无运维设备，工作台设备块为 `UNCONFIGURED`；test profile 下映射与范围由 `DeviceBusinessScopeTest` 证明。
- 未做第二账号切换与移动端视口（同阶段 4 说明）。
- 工作台 15 秒刷新每次 5 个请求，阶段 6 统计快照落地后再合并（审查 P2）。


> 2026-09-06 补注（决策 8-13）：处罚交接页"接收方"列改为只显示名称，`recipient_id` 移入悬停提示；名称缺失显示"—"。版本号显示为"第 N 次核验"。其余页面结构与文案不变。
