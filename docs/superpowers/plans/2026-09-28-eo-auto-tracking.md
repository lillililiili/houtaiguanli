# 光电自动追踪一期实施计划

> 执行方式：使用 subagent-driven-development，后端、前端按固定接口实现，由主任务统一审查、联调和交付。用户已授权实施，无需再次确认方案。

**Goal:** 告警、合法性研判、风险围绕同一目标共享光电任务，系统按观察需要调度，人工补跟踪、暂停及恢复。

**Architecture:** 复用现有 EO 指令、任务、回执、目标权限和视频链路。后端统一计算业务观察需求及动作资格；三个页面复用同一光电面板。业务结论、跟踪事实和视频可用性独立。

**Tech Stack:** Java 17 / Spring Boot / JdbcTemplate / PostgreSQL & PostGIS；Vue 3，保持现有依赖。

## 全局约束

- 保留两仓库所有既有未提交修改，不提交或回退它们。
- 保持 /api/v1、Bearer、snake_case、{ok,data,error}、字符串 ID；授权与时效在后端校验。
- 不修改已应用迁移，新增版本在隔离 PostgreSQL 验证；不向业务库手工插入数据。
- 不改反制、通知、合法性结论及人工复核规则；不恢复人工现场观察记录。
- 自动追踪不由页面打开或刷新触发，关闭页面后后台继续工作。
- 一期不做云台人工控制、设备接力、自动抢占、未知类别协议猜测、新增管理策略页面。
- 实机开关保留显式启用；统一错误配置键，并显示当前自动能力是否启用。
- 计划和确认文档不宣称未验证的硬件能力或真实视频可用。

## 任务 1：后端共享调度、状态与人工控制

文件范围：server/modules/device 的 EO 服务、仓库、控制器；application 配置；新增迁移和正式测试。

- [x] 建立统一需求判定：待核实告警、已确认且仍有有效目标的观察需要；当前违法/异常研判及明确可由光电补充的视觉依据；非气象风险按目标、风险原因/等级判定。计划/身份/参数/高度基准缺失不单独触发。最新研判已合法、风险排除、告警误报不贡献对应需求。
- [x] 共享任务、自动模式隔离、当前位置时效、目标/设备占用锁、设备心跳和工作状态检查；不凭指令排队显示成功。
- [x] 增加目标暂停记录及 pause/resume 幂等接口；停止现有任务后持久暂停，人工 begin 不解除暂停；恢复不绕过总开关。
- [x] 增加只读统一状态接口；返回后台实际需求、状态和 allowed_actions；原接口兼容。
- [x] 加固过期/失败/结束超时/迟到回执；执行结果未知时不盲目重发，不提前释放设备；重启后由持久记录恢复。
- [x] 正式测试覆盖触发与排除、跨入口共享、并发/重复、暂停恢复、时效、模式隔离、权限、迟到及超时；在隔离 PG 验证迁移和实际 SQL。

接口约定：

```text
GET /targets/{id}/eo-tracking-status
{ target_id, status, message, auto_enabled, auto_paused,
  demand_reasons: [{code,label}],
  task?: {task_id,status,origin,device_id,device_name,command_status},
  allowed_actions: [BEGIN|PAUSE|RESUME|RETRY] }
status: IDLE / WAITING_DEVICE / STARTING / TRACKING / LOST / ENDING /
        END_UNCONFIRMED / FAILED / PAUSED / BLOCKED / DISABLED / ENDED
origin: AUTO / MANUAL
POST /targets/{id}/eo-tracking-pause   (Idempotency-Key)
POST /targets/{id}/eo-tracking-resume  (Idempotency-Key)
POST /targets/{id}/eo-tracking-tasks  (既有人工发起接口)
```

状态接口及 pause/resume 的返回 data 均遵循上述状态对象；可选字段缺失不推导成功，动作由 allowed_actions 决定。未知结果不提供盲目 RETRY。

## 任务 2：三个页面公共光电面板

文件范围：E:/rwurenji/_rong/dongying-vue/src/components/video、services/deviceApi.js、pages/AlarmsPage.vue、pages/LegalityPage.vue、pages/flights/components/RiskOpticalPanel.vue。

- [x] 新建共享跟踪与视频面板，消费统一状态接口。查询只读；不在挂载/轮询时发起跟踪。
- [x] 按 allowed_actions 展示人工补跟踪、重试、暂停并结束、恢复自动追踪；请求失败回读，不显示假成功。
- [x] 视频默认展开并可收起，跟踪状态与视频状态分别展示，去重展示和操作。
- [x] 告警移除旧重复人工跟踪区；合法性视频区接入面板；风险旧面板复用公共组件，保持气象隐藏及无目标阻断。
- [x] 身份、目标切换及卸载清理轮询和旧响应；无权限/读取失败时不展示可操作按钮。
- [x] npm run build、node tools/scan.cjs、相关 JS 语法检查；浏览器验证三处入口。

## 任务 3：文档、审查与交付

- [x] 在 C:/Users/Admin（无密码）/Desktop/确认文档 保存中文方案 Markdown，列明页面规则、共享/暂停语义、范围与验收结果。
- [x] 同步最近的后端接口契约、运行说明及前台文档。
- [x] 独立审查本次改动，解决重要问题，记录实际验证结果。
- [x] 适当重启被修改的本地服务，检查端口和目标页面/API，不擅自开启真实设备自动动作。
- [x] git diff --check；最终说明代码清单、文档位置、通过及未验证项。

## 执行进度

- 前端实现及独立静态审查完成；构建、扫描、语法和公共组件挂载交互测试通过，真实三页联调待新后端包。
- 后端实现中；已通过新增状态/暂停和人工跟踪基础用例，继续验证自动候选、公平调度、回执不确定性及 PG。
- 确认文档 Markdown 与 Word 初稿已保存桌面；已通过本机 WPS 导出四页并检查，最终版待实际验证结果回填。
- 浏览器使用独立 5178/18081 环境与 stage456_verify_eoui0928 数据库，借助现有开发 Seeder 生成测试数据，不修改当前业务库记录。

## 最终交付记录

- 2026-09-28 完成：后端专项测试89次执行全部通过、package成功；前端构建/173文件扫描/语法/组件挂载测试通过。
- 独立审查两项P2均修复并复核：人工改判候选、暂停后人工跟踪的停止入口；另补历史未知占用保守兼容。无未关闭重要发现。
- 最终包 67,228,007 字节已更新本地8081服务，2026-09-28 23:29健康接口200；业务前端5173页面200。沿用原QA启动参数和既有关闭的EO自动总开关。
- 隔离5178/18081真实浏览器：告警暂停、合法性同目标同步与恢复、视频收起不结束；风险暂停并重启API后状态仍PAUSED，恢复后按过期位置BLOCKED。浏览器控制台无错误。临时浏览器、Vite及API已关闭，业务服务保留运行。
- 桌面确认文档含Word、Markdown和风险页截图。Word通过WPS导出并逐页检查，最终四页，无裁切、空白尾页或表格溢出。
- 当前协议只支持UAV/BIRD，UNDETERMINED无明确视觉补证原因不自动触发；真实硬件与现场视频未验收。未提交Git，保留两仓库已有修改。
