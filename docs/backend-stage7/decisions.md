# 阶段 7 决策记录（领导代用户决定）

依据：2026-09-05 用户经会话「阶段4、5完成时间预估」转达的指令——阶段 7–9 执行期内需要用户决定的事项由领导按推荐选项直接执行并逐项记录；只有破坏性/不可逆操作、触碰协作者 A 的文件或协议、计划明确禁止的事项才停下来问。本文件按阶段汇总，阶段验收提交后随验收记录发给用户。

| # | 决策 | 选择 | 放弃的选项 | 理由 |
| --- | --- | --- | --- | --- |
| 7-1 | 阶段 7 迁移编号 | `V202609050040–0049` | 平移到 050+ | 阶段 6 已划给 A，原为其预留的 040 段空置；阶段 8/9 顺延用 050/060 段 |
| 7-2 | 权限目录 `sort_order` | 阶段 7 用 940–944，阶段 8 用 950–952，阶段 9 用 960–964 | 三阶段都从 940 起（设计稿冲突） | 避免同号；目录按阶段可读 |
| 7-3 | `rule:read` 是否可见参数值 | 可见，并返回 DEMO/CONFIRMED 状态（修订阶段 3 契约"不返回规则参数"措辞） | 继续隐藏参数 | 页面必须能解释结论来自哪个阈值；参数不含凭据或内部网络信息 |
| 7-4 | 规则引擎的 `integration_source` 行 | 由迁移 040 插入三行（mock/replay/live），生产也存在 | 只在 local/test 种子插入 | `alarm.source_id` 非空外键；来源行是系统目录不是业务数据；生产无 ACTIVE 规则集时不会产生任何告警 |
| 7-5 | 无计划目标定性 | `ILLEGAL / NO_AUTHORIZATION`，参数 `C03.no_plan_status` 可切换 | `UNDETERMINED` | 用户已在计划阶段选定；保留参数便于业务方改口径 |
| 7-6 | `assessment_result.conclusion_code` 新增 `ABNORMAL` | 新增 | 异常落 UNDETERMINED | 用户已在计划阶段选定；`illegal_assessments` 口径不变，契约知会 A |
| 7-7 | E1/E2 协作边界 | E1 定义 `engine/RuleEngineHooks.java` 接口并提供无操作默认 Bean（默认 Bean 后被 7-24 删除），E2 实现（写 `legality_review`、触发 C06） | 领导预先写死接口 | 复核表与 C06 属 E2，引擎属 E1，接口由引擎侧定义最少改动；无操作默认保证 E1 可独立测试 |
| 7-8 | 调度实现 | 新 `RuleEngineWorker` 单行租约，不扩展 `OutboxWorker` | 扩展 Outbox 主题路由 | `OutboxWorker` 硬绑设备处理器且属领导/A 边界文件；独立 Worker 风险更小 |
| 7-9 | 协作方式变更 | 本会话失去跨会话消息工具后，执行者/助手/审查者改为本会话的后台代理 | 等待用户重新配置会话 | 文件归属、TDD、审查纪律不变；用户指令要求不等待 |

（阶段 7 后续决策在执行中追加。）
| 7-10 | 基线变更 | 另一会话在阶段 7 进行中提交了 `cb84150`（业务编号/中文名称显示，改动 DTO/仓库/页面/种子），阶段 7 直接基于该提交继续 | 要求对方回退或等待 | 该提交未触碰阶段 7 文件，`git status` 核对后无冲突；阶段 7 验收时一并回归 |
| 7-11 | 代理停摆处理 | 两个执行者代理因前台 Maven 长时间无输出被看门狗终止，改为新代理接续既有文件，并要求 Maven/npm 全部后台运行等待通知 | 从头重做 | 已写的 RED 测试、迁移 041、C01/C06 草稿有效，接续成本最低 |
| 7-12 | `rule_param.rule_param_id` 长度 | 放宽到 VARCHAR(128)（迁移 041 尚未提交，原位修改）；种子后来改用 36 位稳定 UUID，列宽保留以容纳未来可读 ID | 要求种子缩短 ID | 041 未应用到任何库；列宽放宽无代价 |
| 7-13 | `rule_evaluation` 只增触发器语义 | `assessment_id / alarm_id / alarm_outcome` 三列各允许从 NULL 一次性回填，其余列不可改 | 严格只增 | `assessment_result.evaluation_id` 与 `rule_evaluation.assessment_id` 互为外键，投影行只能在研判行之后插入再回填 |
| 7-14 | 计划主体（`SubjectKind.PLAN`）的观测来源 | 同元组、同 `uav_sn` 且有最新状态的目标；无目标即 NO_STATE → NOT_APPLICABLE | 计划主体只做静态冲突 | 契约未细化；保持"没有观测就不判合法性"的原则 |
| 7-15 | C01 候选计划是否按 `status_code` 过滤 | 不过滤 | 只取 APPROVED/EXECUTING | 计划状态词表尚未冻结（种子 PENDING、测试 APPROVED）；待业务确认后加参数 |
| 7-16 | 空域事实的范围 | 取全部有完整归属且生效的空域版本，不按目标元组过滤 | 只取同元组空域 | 禁飞区是地理事实，跨区域同样约束目标 |
| 7-17 | Worker 无待评估主体时是否留 `rule_run` | 不留 | 每 tick 一行 | 避免每 5 秒一行空运行淹没运行记录 |
| 7-18 | C01 NONE 且同时有空域类 FAIL | 仍升为 ILLEGAL，不被 `no_plan_status` 参数压低 | 参数优先 | 进入禁飞区不能因参数改口径而降级 |
| 7-19 | 审查 P1-1：偏航场景不可达 | DEMO 目录 `C01.corridor_tolerance_m` 从 20 提到 100（契约已改），使"已匹配计划但偏航"可达 | 改契约让身份 MATCH 时走廊 MISMATCH 不降为 NONE | 参数改动最小且语义清晰：C01 走廊只判"是否属于这条航线"，C02-3 判"偏离多少" |
| 7-20 | 审查 P1-2 | 领导直接在 `R__stage7` 追加 `legality_review_history` 只增触发器 | 等 E2 提供片段 | E2 仍在实现，文件由 E1 交付后空闲 |
| 7-21 | 审查 P2（E1 范围）| 领导直接修：待评估主体改比 `observed_at`；崩溃遗留 RUNNING 运行按 4×租约回收；`finish` 改显式事务模板；`no_plan_status=LEGAL` 时仍走步骤 4–6 | 交回执行者 | 改动小且 E1 已交付，避免再起代理 |
| 7-22 | 另一会话提交的 `labels.js` 触发 `scan.cjs` 判红（气球/风筝/孔明灯作为 target_type） | 领导把三者移到 `INFERRED_SUBTYPE_LABEL` 并并入 `SUBTYPE_LABEL`，`OBJECT_TYPE_LABEL` 只留 UAV/BIRD/UNKNOWN | 保持原样并让阶段 7 验收带红 | 前端扫描是每阶段门槛；改动只涉及已提交文件且语义符合扫描规则 |
| 7-23 | 审查 P2（E2 范围）处置 | 由修复代理在 E2 文件内修：NOT_APPLICABLE 不建复核行；`rule-effects` 增 `mode` 参数默认 ACTIVE；合并窗口统一以 `evaluated_at` 为基准、`received_at` 取时钟；契约同步 | 延后到阶段 7.7 | 都是验收前必须闭合的口径问题 |
| 7-24 | `RuleEngineHooksDefaults` 无操作 Bean | 删除；钩子实现 `RuleEngineHooksImpl` 为必需 Bean | 保留 `@ConditionalOnMissingBean` 默认 | 两者并存对扫描顺序敏感；E2 已落地，默认实现只会掩盖装配错误 |
| 7-25 | 真实 PG 回填 SQL 混型（`COALESCE(jsonb, CAST(? AS JSON))`） | 采纳 Session 1 会话的逐列"仅当为 NULL 时写入"改法（与 R__stage7 触发器一一对应），验收要求凡 jsonb 参与 COALESCE/CASE 的 SQL 必须有 PG 用例 | 参数转 JSONB 保留 COALESCE | 逐列条件更新与触发器语义一致，且不依赖方言分支 |
| 7-26 | 阶段 3 `AirspaceReadApiTest` 因阶段 7 种子空域（ALL 账号可见）计数漂移 | 该测试的列表断言改按自己造的 `owner_org_id/district_id` 过滤（最小改动） | 让阶段 7 种子空域失效或改到测试 profile 之外 | 引擎需要生效空域做回放；测试只应断言自己的数据。后续阶段无范围过滤的列表断言同样要隔离 |
| 7-27 | 合法性页 KPI 时间窗与时区 | `timezone` 固定 `Asia/Shanghai`，`from/to` 按北京时间 0 点计算，标签"正式模式 · 北京时间当日（影子运行不计）" | 跟随浏览器时区 | 与阶段 6 统计口径（北京时间当日、ACTIVE）一致，避免部署地不同导致口径漂移 |
| 7-28 | 重新研判的说明 | 改为 `openFormModal` 由操作者填写 1–1000 字并进复核历史 | 页面写死文案 | 契约要求人工依据可追溯 |
| 7-29 | 合法性页加载时队列请求重复 5 次 | 记为 P2，本阶段不改（功能无害，`listToken` 丢弃旧响应）；阶段 8 前测量根因 | 立即排查 | 提交门槛不含性能项；避免在验收末尾改页面逻辑 |
