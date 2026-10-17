# 无人机现场结束后的通知决定验收

## 已确认行为

沿用业务前台“移送与处罚”页面。已核实属实的事件取得明确飞离、有效不反制决定或实际停机完成依据后，后台建立同事件唯一的待办，操作人员选择“通知上级”或填写原因“不通知”。不增加接手、审批、办结或上级后续处罚步骤。

自动任务只建待办，不调用通知通道。设备仍在执行或急停尚未核查时不能提前结束；位置未知不能作为飞离。缺少接收方、配置冲突、材料不完整时待办仍可查看，页面说明不能发送的原因；有权人员仍可记录不通知决定。

新流程按部署时点切换：旧事件在部署后新取得结束依据也参与；部署前已经结束的历史不批量补单。原移送材料、回执、处罚结果和来源标识保留。发送结果不明只能先核对原记录，不能改成不通知或另发新请求。

## 验证记录

2026-10-16 本轮结果：

- Java 17.0.20.1：12 个受影响测试类共 **214 项通过，失败/错误/跳过均为 0**；其中隔离 PostgreSQL/PostGIS 79 项，完整执行 198 项迁移。日志 `server/target/handoff-followup-java17-final.log`。覆盖自动待办、人工决定、配置/材料受阻、未知结果、权限范围、并发幂等、新旧边界、停止回执和实时刷新。
- Java 17 打包成功，日志 `server/target/handoff-followup-java17-package.log`。
- 前台 6 个相关测试文件 **33 项通过，失败/跳过为 0**：handoffStatus、handoffAdvisory、counterNextStep、notificationConnectivity、advisoryRecovery、alarmCounterProgress。后两组包含工作区其他处置资格修正，保留并一并验证。
- 最新前台构建成功，入口 `dist/assets/index-DCTYflLW.js`；构建仅有既有大文件提示。浏览器另核对新增“进入方式”文案。
- Edge 实际操作隔离验收：正常登录、3 条事件自动建立待办；真实来源被禁止使用模拟通道；不通知空原因阻断、填写原因后保存；点击一次通知后显示模拟已送达/等待签收；刷新及退出重新登录后仍保留结果；待决定筛选只剩 1 条，列表与统计同源。
- 浏览器夹具最终 **1 项通过，失败/错误/跳过为 0**，断言通知通道仅调用 **1 次**、不通知和受阻事项没有发送、同事件只有一条待办、没有设备指令、冻结材料不变。日志 `artifacts/local-runtime/handoff-decision-browser/server3.out.log`，结果 `server/target/handoff-decision-browser/manifest.json`（phase=COMPLETE）。临时预览与测试服务已结束，临时凭据文件已清理。
- 本机后端与业务前台恢复运行；管理端、模拟器保留原进程。强制启动检查 `node scripts/check-local-startup.mjs --business-dir E:\rwurenji\_rong\dongying-vue` **exit 0、7/7 PASS**，报告时间 **2026-10-16T10:52:19Z**。本地已登录业务账号实际打开新移送页面，旧历史可读；未另做该本地账号的密码登录。
- 两仓库 `git diff --check` 通过。未提交、未推送。

迁移期间开发热重载曾提前应用初始 001，后续修改触发校验失败。本轮恢复其原校验和 **-1301427798**，约束修正另增 002；未运行 repair、未改 Flyway 历史表。运行库只读核对 001/002/9001 全部成功。修正迁移前已停止归属明确的开发进程；验证完成后通过项目原启动入口重新运行。

启动检查第一次受 Vite 热更新资源的时间戳 URL 影响，业务前台重启后已通过；启动入口一次在地图代理尚未就绪时提前检查管理端，服务就绪后重新执行完整检查通过。失败检查未计为通过。

截图保存在 `C:\Users\Admin（无密码）\.codex\visualizations\2026\10\16\handoff-notification-verification`：`01-pending-and-real-channel-blocked.png`、`02-decline-persisted.png`、`03-notify-delivered-awaiting-ack.png`、`04-only-undecided-remains.png`。

浏览器验收使用独立 PostgreSQL schema 和合成数据，`app.dev-seed.enabled=false`，正常登录临时验收账号。人工通知调用隔离模拟通道，不表示真实上级平台已经联通。

## 代码变更说明

后端路径相对于 `E:\houtaiguanlii`，前台路径相对于 `E:\rwurenji\_rong\dongying-vue`。

| 改动文件/类 | 改动方法/函数 | 方法作用及本次代码作用 |
| --- | --- | --- |
| `server/.../handoff/application/HandoffFollowupService.java` | `candidates`、`create` | 检查现场结束事实和业务占用，锁定事件、冻结材料或受阻原因，幂等建立内部待办与审计，不发送通知。 |
| `server/.../handoff/infrastructure/HandoffFollowupRepository.java` | `cutoff`、`eligible`、`eventCreatedAfterCutoff`、`candidates`、`insert`、`decision`、`decide`、`actorName` | 保存部署边界和通知决定，按版本原子更新；只返回允许展示的结束摘要。 |
| `server/.../handoff/application/HandoffNotificationService.java` | `status`、`prepare`、`stateBlocker`、`notifyTarget`、`channelBlocker`、`declineBlocker`、`decline`、`notify` | 分别校验通知/不通知权限，首次人工发送冻结接收方，决定与发送占位同事务；实际通道在事务外调用，未知结果不盲目重发。 |
| `server/.../handoff/api/HandoffController.java`、`HandoffDtos.java` | 通知决定接口、查询及 DTO | 增加不通知写入接口、决定筛选和详情字段，保持 snake_case 契约。 |
| `server/.../handoff/application/HandoffReadService.java`、`infrastructure/HandoffRepository.java` | 列表、详情、统计、`assignFollowupRecipient`、查询条件 | 统一待决定/不通知统计、筛选及报表口径；未选接收方的内部待办不被连接查询遗漏。 |
| `server/.../handoff/application/HandoffSubmissionService.java` | `createFromEvent`、`automaticAfterDisposal` | 新事件只通过自动待办进入人工决定；原自动发送逻辑改为内部待办创建，保留历史人工路径。 |
| `server/.../disposal/application/PunishmentHandoffJob.java` | `sweep` | 定期补偿尚未建立的待办，逐事件隔离失败。 |
| `server/.../disposal/infrastructure/DisposalRepository.java`、`application/DisposalCompletionAdapter.java`、`handoff/domain/DisposalCompletionPort.java` | `punishmentCompletion`、完成事实记录 | 传递实际完成时间及授权编号，选取最新有效完成事实，维持停机证据门槛。 |
| `server/.../alarm/api/UavAdvisoryDtos.java`、`application/UavAdvisoryService.java` | `AutoHandoff`、`autoHandoff`、处置状态投影 | 告警显示实际关联待办和通知决定；“现场结束”不冒称整个后续流程已办结。 |
| `server/.../reporting/application/ReportLabels.java` | 状态标签映射 | 报表正确显示待决定、不通知。 |
| `server/src/main/resources/db/migration/V202610160001__uav_followup_notification_decision.sql` | 无具体方法 | 增加决定表及部署边界；允许内部待办暂不指定接收方和人工发起人。已应用内容保持原校验和。 |
| `server/src/main/resources/db/migration/V202610160002__handoff_internal_recipient_null_guard.sql` | 无具体方法 | 追加约束修正，避免 SQL NULL 语义放过非内部待办的空接收方。 |
| `server/src/main/resources/db/postgresql/V202610169001__handoff_decision_realtime.sql` | 无具体方法 | 决定变化触发既有告警/移送实时刷新信号。 |
| `src/pages/PunishPage.vue` | `listQuery`、`loadKpis`、`updateNotificationStatus`、模板 | 沿用原页面布局，加入决定筛选、统计及详情状态；历史处罚结果只读。 |
| `src/pages/punish/PunishmentNotification.vue` | `load`、`notifyDepartment`、`sendNotification`、`declineNotification`、`submitDecline`、`retryOriginalOperation` | 一键通知或填写一个原因不通知；保存原请求用于断线核对，展示真实决定人、时间、送达和签收。 |
| `src/pages/punish/handoffStatus.js` | `deliveryView`、`statusQuery`、`notificationStatisticsFilters`、`notificationActions`、`declineReasonError`、`pendingOperationSettled` | 统一状态、筛选、动作资格及请求恢复，不将未知结果显示为成功。 |
| `src/services/handoffApi.js` | `getHandoffStatistics`、`declineHandoffNotification` | 同源统计与不通知接口适配。 |
| `src/components/disposal/UavAdvisoryPanel.vue`、`autoHandoffView.js` | 事项入口、`notificationProgressView`、`autoHandoffView` | 删除人工选接收方步骤；只对实际存在的待办显示跳转入口。 |
| `src/pages/AlarmsPage.vue`、`src/pages/alarms/CounterLaunch.vue`、`src/components/disposal/counterNextStep.js` | `displayState`、不反制成功反馈、`counterHiddenReason` | 现场结束与后续通知状态分开表达。 |
| `server/.../handoff/api/HandoffFollowupDecisionApiTest.java`、`HandoffFollowupDecisionPostgresTest.java` | 场景测试 | 覆盖自动待办、人工两分支、配置缺失、权限范围、并发幂等、旧事件新完成及 PostgreSQL 迁移/刷新。 |
| `server/.../handoff/api/HandoffDecisionBrowserFixtureTest.java` | `servePendingNotificationDecisions`、`pending` | 独立数据库正常登录与浏览器实操夹具，结束时断言仅通知分支调用一次通道、其余不发送。 |
| `server/.../handoff/api/HandoffAutomaticMatrixApiTest.java`、`CountermeasureHandoffPostgresTest.java`、`HandoffApiTest.java`、`server/.../disposal/api/DisposalJammingFailurePostgresTest.java`、`server/.../alarm/application/UavDepartureObservationTest.java` | 既有回归测试 | 更新新流程预期，保留无效停机回执、历史流程和飞离边界检查。 |
| `tools/handoffStatus.test.mjs`、`tools/handoffAdvisory.test.cjs`、`tools/counterNextStep.test.mjs` | 前端状态回归 | 验证新状态、统计、历史兼容、原因校验和原请求恢复。 |

两仓库 `AGENTS.md` 记录本次用户确认。`docs/uav-followup-notification-workflow.md` 记录新契约，并关联 `docs/后端开发基线.md` 与 `docs/backend-stage5/workbench-handoff-api-contract.md`；以上文档无具体方法。实施计划见 `docs/superpowers/plans/2026-10-16-handoff-notification-decision.md`。同时存在的模拟器改动、处置资格失效传播改动均保留，不归入本次通知决定实现。
