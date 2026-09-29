# 光电自动追踪一期独立总体审查

审查日期：2026-09-28。以任务起点 backend-review.diff / frontend-review.diff 限定归因，并复核当前实际文件；不使用仓库总 diff 归因历史改动。只读检查代码，未运行 Maven、浏览器或重复实施者测试；本报告不是实机验收证明。

## 发现

### P2：人工改判的违法／异常目标遗漏自动候选

- 位置：`server/src/main/java/com/uav/lowaltitude/modules/device/infrastructure/EoTrackingRepository.java:44`，对照同文件第 60 行。
- 复现场景：有效实时目标只有当前 `UNDETERMINED` 研判，没有告警及风险，人工将该研判改判为 `ILLEGAL` 或 `ABNORMAL`；设备、总开关及目标时效均满足。
- 原因：状态需求通过 `COALESCE(v.manual_status,e.legal_status)` 识别人工结论，但调度候选只用原始 `e.legal_status`。`LegalityReviewService.revise()` 第 108–119 行只保存复核及审计；转告警是独立 `escalate()` 操作，不能补偿这个遗漏。
- 影响：页面返回 `LEGALITY_OBSERVE` / `WAITING_DEVICE`，后台却不会为该目标申请设备，违反无关联告警时按当前研判自动观察的约定。
- 建议：候选使用同一有效结论或足够宽的候选集，再由 policy 最终过滤；增加无告警人工改判场景的 PostgreSQL SQL 覆盖。

### P2：暂停期间人工补跟踪后无法保持暂停并直接结束

- 位置：`server/src/main/java/com/uav/lowaltitude/modules/device/application/EoTrackingStatusService.java:111`。
- 复现场景：暂停目标，原任务已停止；保持 `auto_paused=true` 时人工 BEGIN，创建新的人工任务。
- 原因：`allowed_actions` 在暂停时只提供 `RESUME`，不提供 `PAUSE`；三处公共面板只消费该列表，没有独立 end 入口。
- 影响：用户无法直接停止本次人工任务，只能先恢复自动资格再暂停，破坏“暂停期间单次人工任务不解除暂停”的操作语义。
- 建议：暂停且存在 OPEN 人工任务时仍允许 PAUSE，接口使用新幂等键请求停止但保持暂停；补暂停 → BEGIN → PAUSE 的接口契约回归。

## 修复复核

以上两项已反馈实施者并修复，当前代码层面的 P2 均已消除：

- 候选 SQL 已 LEFT JOIN `legality_review` 并使用 `COALESCE(v.manual_status,e.legal_status)`；最终仍由 policy 校验最新研判及范围、模式、时效。人工改判目标已不会因原始结论而被候选层遗漏。新增 `EoManualTrackApiTest.manualIllegalReviewIsScheduledWithoutAlarmOrRisk` 已静态复核：无告警／风险且原始 UNDETERMINED 时不入候选，人工 ILLEGAL 后进入候选，scheduler 创建任务；PostgreSQL 测试类继承该场景，运行结果待实施者提供。
- `view()` 已在 paused 且任务 OPEN 时同时提供 PAUSE。`EoManualTrackApiTest.automaticAndManualRaceShareOneTaskAndPauseSurvivesManualBegin` 已追加状态接口返回 PAUSE、再次暂停返回 ENDING 且 `auto_paused=true` 的断言；`EoTrackingPostgresTest` 继承该场景。没有要求先恢复自动资格。

本轮没有运行测试；最终测试结果以实施者实际执行报告为准。当前未保留未修复的重要审查发现。

追加兼容修复已静态复核：`EoEdgeRepository.UNCERTAIN` 统一识别旧 FAILED 且 BEGIN 已发送超时或 END 已发送失败／超时、尚无成功结束回执的任务；目标 policy 阻断与设备空闲筛选均使用该条件，状态返回 LOST 且不允许 BEGIN/RETRY。显式暂停在持有游标锁、校验范围并锁定设备后，只在设备没有当前 OPEN/ENDING 任务时将旧任务纳入停止流程；设备已有当前任务则返回 409，未批量恢复历史任务、未追加批改历史迁移。`legacyFailedButSentTimeoutBlocksTargetAndDeviceUntilExplicitStop` 已覆盖 LOST、设备不可选、人工 BEGIN 拒绝和显式停止转 ENDING，PG 类继承。设备存在当前任务的 409 分支经代码复核，但本审查未独立执行该分支。当前未发现新增重要缺陷；最终运行验证仍由实施者提供。

## 其余审查结论与边界

- 任务分配和控制使用持久游标锁配合目标／设备锁；状态查询不创建任务；暂停记录在数据库中保存；mock/replay 与 live 匹配、目标位置和设备心跳门禁已落实到调度和下发检查。
- 当前 dispatch 已改为先独立事务提交 SENT 再外发；不会因外发后事务回滚重新把同一指令变回 QUEUED。开始结果未知及结束失败／超时保留任务占用；迟到报告按任务关联、创建／下发时间约束处理。未发现另一项足以确定的重要缺陷；该结论不替代并发与崩溃故障注入测试。
- 本次 frontend diff 只将三处入口接入共享光电面板，保留业务通知、合法性复核及反制办理边界；未发现页面主动触发自动任务或伪造成功的问题。
- 方案已准确写明仅支持 UAV/BIRD、UNDETERMINED 缺少明确视觉原因不自动触发、总开关须显式启用及实机视频未验收。`application.yml` 的有效默认设备心跳窗口是 3000 ms，policy 注入 fallback 是 30000 ms；正式运行说明若列出默认值应按 3000 ms，并区分 fallback，不把它写成硬件标定参数。
- 测试通过情况由实施者及主任务提供，本审查没有独立执行，不将其重复列为本审查通过证据。
