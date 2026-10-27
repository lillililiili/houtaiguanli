# 预警与实测自动核实衔接

用户于 2026-10-25 确认本次调整。只更新本地 `local,qa`，保持 `app.dev-seed.enabled=false`，不启用或切换已有规则，不部署服务器。

## 业务行为

- 预警仍人工核实；实际进入同一禁飞区后，当前入区依据有效且已启用核实条件全部满足，尚未核实的同一风险可自动核实。
- 已人工或自动核实、已判误报的同周期风险不重复核实。预测、实测候选每轮统一评估当前办理事件，避免旧预测的暂停结果覆盖实测判断。
- 办理记录、统计和通知沿用原 `risk_case_id`；多区域和新周期独立判断，短信与电话保留原任务占用和回执。
- 自动核实保存 `AUTO`、规则运行、实测研判及空域引用；预测衍生风险只形成处置资格，不回写原事件处罚事实。人工反制、原授权期限和停止保护保持。
- 模拟器流程1–3以连续航迹为默认，首次已在区内放入补充场景；流程2继续按实际模拟短信回执转向。旧流程9文件及生成结果保持兼容。

## 自动验证

后端执行：

```powershell
.\mvnw.cmd -Dtest=UavRiskCasePostgresTest,PredictionEventGateTest,AutomationRuntimeWorkerTest,AlarmRuleVerificationTest,AutomationRuntimeEligibilityTest,AlarmRuleCounterTest test
.\mvnw.cmd -DskipTests package
```

最终109项通过，失败、错误、跳过均为0；37项使用专用 `stage456_verify_entered_20261025_766ef1c7` PostgreSQL 16.9/PostGIS 的随机隔离 schema，验证真实/模拟、迁移升级、当前阶段、旧人工结论、规则关闭/变化、依据失效、多区域、新周期、并发及渠道占用防重，包含同一观测重评和有效空域版本切换两例。规则输入使用受控测试事实，不能替代模拟器实际来源联调。首轮新增夹具重复插入规则唯一键导致10项错误，修正隔离夹具后完整回归通过。无历史迁移脚本改动。

业务前台执行：

```powershell
node --test tools/riskCaseView.test.mjs tools/predictionActions.test.mjs tools/advisoryRecovery.test.cjs tools/situationTargetCard.test.mjs tools/alarmSelectionRefresh.test.cjs tools/alarmEvidenceRefresh.test.cjs tools/uavVerificationBasis.test.cjs
```

42 项通过；随后补齐核实弹窗的阶段标签，再运行 `uavVerificationBasis`、`riskCaseView` 共19项通过（含新增1项，其余重复）。最终构建、语法、差异检查通过；源码扫描仅有原有两处兜底待办，构建保留原有大包提示。

模拟器：37项 JavaScript、7项统一风险场景、22项流程接口、12项旧K03兼容、10项旧七流程生成检查通过，共88项；不注入核实、通知或反制成功结论。

## 本地操作

1. 后台确认自动核实规则已按需要启用；本次实现不会自动修改它们。
2. 模拟器选择流程1完整流程，载入后开始模拟。
3. 区外出现预警时应显示“待人工核实”。验证自动衔接时先不提交核实。
4. 实测进入后检查同一条风险变为“已实际入区”；规则与依据满足后显示“风险已核实”，核实记录注明“系统自动核实”。未满足时继续显示待核实及阻断原因。
5. 检查办理列表只有一条该区域风险，短信、电话各按同风险一次逻辑任务执行。重复刷新不会重新核实或通知。
6. 再运行一轮，在预警时人工核实；进入后核实记录应延续，不新增一份系统核实。反制仍由有权限人员人工发起。

## 浏览器首轮验证

使用原登录业务前台和模拟器运行流程1完整流程，未在预警阶段提交人工核实。页面北京时间2026-10-26（任务日期2026-10-25），目标 `20261026-002` 于11:26:10出现预计入区预警、待人工核实；11:26:33实测入区，11:26:38显示系统自动核实。融合感知始终一项无人机风险，告警页同一条记录显示“可人工发起反制”，光电正在跟踪。

风险 `9a37efec-b901-444f-a0fb-caf6a73012da` 只读核对：同一风险关联一份预测及一份实测依据，核实账本只有一条 `AUTO / OBSERVED / CONFIRMED`；短信、电话渠道各一次占用，实际通知记录各一条，回执分别为 `SIMULATED_DELIVERED`、`SIMULATED_PLAYED`。电话等待超过60秒后按现用策略触发，本轮没有发起反制。

更新后 `check-local-startup.mjs` 的7项检查通过，报告在 `artifacts/local-runtime/verification-after-update.json`。验收截图在 `artifacts/entered-verification/automatic-verified.png`。以上为本地模拟验收，不代表真实硬件或实际电话验收。

最终复核补充了同一观测重评时自动核实冻结依据与当前实测守卫共用查询；补充后109项后端回归、最终打包及7项本地启动检查全部通过。模拟器只更新静态资源版本并复用进程，登录保持；页面已确认默认显示完整连续流程，“首次发现已在区内”在折叠补充场景中。截图：`artifacts/entered-verification/unified-simulator-flow.png`。

最终版本又运行两轮完整流程，目标 `20261026-003`、`20261026-004` 均自动衔接到已实际入区并自动核实，每轮只有一份有效风险、一条 `AUTO / OBSERVED` 核实；短信各一条模拟送达。前一轮在电话触发前停止，后一轮电话一条模拟播放完成。两轮预警期间尝试打开人工核实窗口时遇到阶段/当前依据刷新，没有成功提交，不能记为人工路径浏览器通过；该路径的核实防重由数据库回归覆盖。已停止本轮模拟上报及收发，服务与登录保留。

## 人工入口补充修复与边界

定位到融合感知按钮只发出无参数核实事件，页面再从瞬时有效风险列表重找目标事件；预测依据刷新令列表移除时可能丢失点击对象。现由按钮、卡片逐层传递原风险，使用其稳定 `risk_case_id` 读取最新详情；不再依赖瞬时筛选列表，仍检查最新办理事件、核实状态和动作资格。已经自动核实不重复，未知或过期仍不允许确认属实。

补充执行 `situationPredictionPopup`、`situationTargetCard`、`uavVerificationBasis`、`riskCaseView`、`advisoryRecovery`、`situationSetup` 六份测试共46项，全部通过；最终前台构建成功，差异检查通过。后续目标 `20261026-005` 页面再次显示已实际入区／风险已核实，但浏览器控制连接中断，未成功完成修复后人工提前核实的浏览器提交，明确保留该验收边界。

已通过模拟器正常停止接口停止该轮上报和收发；最终前台构建后再次执行7项启动检查全部通过。服务仍运行，`local,qa`、`dev-seed=false` 保持；未部署服务器、未变更自动规则启用状态。
