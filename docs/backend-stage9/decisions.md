# 阶段 9 决策记录（领导代用户决定）

依据同阶段 7/8：需要用户决定的事项按推荐选项直接执行并逐项记录，阶段提交后发清单。

| # | 决策 | 选择 | 放弃的选项 | 理由 |
| --- | --- | --- | --- | --- |
| 9-1 | 空域版本变更方式 | 接替式新版本：新版本插入时把上一开放版本 `valid_to` 关闭为新版 `valid_from`；PG 触发器只放开 `valid_to` 从 NULL→非 NULL，其余列不变，DELETE 仍禁止 | 原地修改 / 完全不可变 | 用户已在计划阶段选定；历史研判引用的版本几何/高度不漂移 |
| 9-2 | 人工与导入空域的来源 | `source_id=NULL, source_mode='live'`；操作者痕迹落 `airspace_version_origin(MANUAL|GEOJSON_IMPORT|SEED)` | 造一条"人工来源"行 | 人工数据不是外部来源；生产不造模拟来源 |
| 9-3 | `kind_code` 字典 | CHECK `PROHIBITED|RESTRICTED|ALTITUDE_LIMIT|PERMITTED|TEMPORARY_CONTROL`；图层映射 nofly/limit/suit 由前端字典完成 | 自由文本 | 阶段 3 只要求非空，页面需要稳定图层映射 |
| 9-4 | 航线编辑 | 不在本期；空域页右侧只读展示航线 | 一并做航线版本编辑 | 被计划引用的 `route_version` 不可变，改动面涉及阶段 3 触发器 |
| 9-5 | C04 产出落点 | `flight_risk(risk_type='SPACE_OBJECT')` 经 `RiskIngestionService.ingest` 幂等入库，细节落 `space_risk_fact(risk_id PK)`；无活动计划不生成风险，只计 `targets_seen` | 独立风险表 | 复用阶段 4 核验/交接闭环；`flight_risk.plan_id` 非空 |
| 9-6 | 异物细类字典 | `space_object_subtype(BIRD_FLOCK|BALLOON|KITE|SKY_LANTERN|OTHER_OBJECT, aliases JSON)`，按 `target.subtype` 精确/别名匹配，匹配不到不进 C04 | 未匹配归 OTHER | 不伪造分类；气球/风筝/孔明灯只能是 subtype（A4） |
| 9-7 | C04/C05 参数机制 | 迁移 062 建规则集 `SPACE-RISK-DEMO` v1（PUBLISHED，DEMO）与 C04/C05 `rule_version`+`rule_param`；评估器经阶段 7 `RuleParamsImpl` 读参数；不接 `RuleEngineWorker` | 新参数表 / `source_snapshot` | 阶段 7 已落地，参数机制统一 |
| 9-8 | 规则引擎来源行 | 复用阶段 7 已有 `rule-engine-legality-live/mock`？否——C04 风险用新来源 `rule-engine-space-risk-{live,mock}`（迁移插入） | 复用合法性来源 | 风险与合法性告警来源分开，统计口径可分 |
| 9-9 | C05 通报对象 | 新表 `airport_notification_target`（逻辑名，不存号码/凭据），不复用 `handoff_recipient` | 扩 `handoff_recipient` CHECK | 交接接收方 CHECK 只允许两种类型且属阶段 5 契约 |
| 9-10 | `#/risk` 与飞行计划页 | `#/risk` 成为独立 `SpaceRiskPage`（按 legacy risk.js DOM 恢复）；`FlightsPage` **保留** events 页签与全部 `risk*` 状态，只去掉 hash 互写并在详情既有占位区块接数据 | 总计划 D10 的"迁出 400 行" | 用户 2026-09-06："不要随意改之前的页面，只把数据接到前端" |
| 9-11 | "计划与实际对照"数据源 | 优先读阶段 7 `rule_evaluation` 最新 C01 匹配（`plan_match_code` + hit_details C01）；无研判时 `availability=NO_EVALUATION` | 前端自算 | 前端不做几何/高度计算 |
| 9-12 | GeoJSON 导入 UX | `openFormModal` 内 textarea 粘贴为真源；"选择文件"按钮在脚本里 `createElement('input')` 读文本填入（模板不出现原生控件） | 只保留粘贴 | 满足 `tools/scan.cjs` 且可用 |
| 9-13 | 编号 | 迁移 060–064，`R__stage9_*`，权限 sort 960–964，种子 `@Order` 85/90，PG 隔离库 `stage456_verify_s9`，验收库 `uav_stage9_verify` | — | 按计划 |
| 9-14 | 新页面文案与布局 | 走技能 `writing-user-readable-ui-text`（共享字典、第 N 次、ID 进 title、空值不渲染）+ `b2d1359` 布局规则；报告前 `check-ui-text.sh` 零命中（既有页面的既有文案除外）+ 浏览器截图 | — | 两个会话转达的用户要求 |
| 9-15 | `#/risk` 深链与 UI 上下文键 `'risk'` 的归属 | `SpaceRiskPage` 必须同时消费 `UI.consume('risk')` 的 `{risk|riskId|risk_id|eventId}` 与 `?risk=<id>`（与原 `FlightsPage.consumeRiskDeepLink` 取值口径一致）；`FlightsPage` 的"全部风险事件"页签只响应页内点击，不再消费该键（领导在 9.0 文件内删除 `consumeRiskDeepLink`）；三个既有生产者（工作台、处罚页、态势页）不改 | 新页面另起键名并改三个生产者 | 审查第 1 轮 P1-1/P2-1：既有"转风险"在 HEAD 上已断；改生产者会碰既有页面与 A 的页面 |
| 9-16 | C04 定时任务的生产安全与 DEMO 规则集激活 | `SpaceRiskEvaluationJob` 只用 `@ConditionalOnProperty`（正式能力，不用 `@Profile` 排除生产）；迁移 062 不得把 `SPACE-RISK-DEMO` 置 ACTIVE，激活移到 `LocalStage9SpaceRiskSeeder`（local/test），生产经 `POST /rule-sets/{code}/activate` 并受 `allow-demo-active` 约束；Job 在无 ACTIVE 版本或 ACTIVE 为 DEMO 且不允许时不评估、不写风险 | 给 Job 加 `@Profile("!production")` | 与阶段 7"生产默认没有 ACTIVE 规则集、DEMO 只能影子"一致；C04 将来要在生产跑 |
| 9-17 | C04 的数量/趋势输入 | 不给 `target_latest_state` 加数量与趋势字段；决策表在 `object_count`/`trend` 缺失时不上调等级，并把 `OBJECT_COUNT_UNAVAILABLE / TREND_UNAVAILABLE` 写进 `space_risk_fact.unknown_reasons`；两个参数留在目录标 DEMO | 加字段 / 移出目录 | 数量与趋势要靠光电或雷达群目标能力，本期没有来源，不伪造 |
| 9-18 | 空间风险的地图坐标 | `space_risk_fact` 加可空 `target_location`（POINT 4326）与 `target_altitude_raw`，记录评估时刻的目标位置快照；页面按等级着色画点，无坐标不画 | 只画航线中心线 | 恢复 legacy 风险页"标记按等级着色"的最小数据 |
| 9-19 | `flight_risk.risk_type` 是否加 CHECK | 不加；契约把 `SPACE_OBJECT` 标为本期取值，既有自由文本值不动 | 加白名单 | 会碰阶段 4 语义与既有数据 |
| 9-20 | C04 定时任务的单实例保护 | 本期进程内互斥；多节点需数据库租约，记入验收"未接入" | 复用 `rule_engine_lease` | 该表是阶段 7 引擎单行租约，扩成多行是阶段 7 契约改动 |
| 9-21 | 9.3 浏览器验收 | 由领导在 9.5 统一做（执行会话不代填口令） | 执行者自验 | 与阶段 7/8 一致 |
| 9-22 | `airspace_version.kind_code` 字典 CHECK 的历史写法 | 本期 CHECK 额外保留 `HEIGHT_LIMIT` / `TEMPORARY`（阶段 7 种子与测试仍写这两个值），061 已把存量行归一，写接口只收规范五值；阶段 7 种子归一与收紧迁移由领导在 9.5 后做 | 严格五值 | 严格五值会让阶段 7 种子一插入就违约、全仓上下文起不来 |
| 9-23 | 并发确认导入批次的 409 码 | 批次已被另一请求确认/丢弃 → `IMPORT_ALREADY_DECIDED`；`expected_version` 过期 → `VERSION_CONFLICT`；契约措辞按实现修订 | 统一 `VERSION_CONFLICT` | 更具体的码已在契约错误码表内，页面可据此提示"已被他人确认" |
| 9-24 | 演示风险与 C04 评估风险重复 | 种子按空间后端分流：PostGIS 可用时不插直接 ingest 的演示风险，播种后同步跑一次 C04 评估（MANUAL）；H2 保留两条 ingest 演示风险并标注"演示数据，未经评估器"。备注：风险按 (计划, 目标) 生成，同一目标可因多个时间重叠的计划有多条风险（各计划各自结论），幂等键是 (plan, target, window)；PG 用例断言"HIGH 恰好一条 + 同 (plan,target) 无第二条" | 验收环境播种后手工停用演示风险 | 页面上的风险来自真实规则；本地 H2 仍有数据可看 |
| 9-25 | 飞行计划详情指标条"目标匹配 / 尚未接入" | 只改数据绑定接到 `actuals.match.plan_match_code`，不动结构 | 保持"尚未接入" | 与下方"计划与实际对照"同屏矛盾；属于"只接数据" |
| 9-26 | E1 对共享字典的两处改动 | 接受：`LEGALITY_LABEL.UNDETERMINED` 统一为"不可判定"（与合法性页、复核弹窗一致，A 的大屏消费同一码，提交说明知会）；`RISK_TYPE_LABEL` 增 `SPACE_OBJECT: 空中异物风险` | 回退 | 同一码全站一个中文 |
| 9-27 | 计划与实际对照的 DEMO 提示 | `actuals.match` 与 `actuals.legality` 各加可空 `param_status`（取自 `rule_set_version`），页面 DEMO 时显示"参数为演示值，尚未确认"徽标；C01 原因码经 `RULE_REASON_TEXT` 翻中文 | 保留英文原因码拼接 | 可读性规则 + 不丢 DEMO 标注 |
| 9-28 | 合法性引擎误把 SPACE-RISK-DEMO 当规则集运行 | 领导集成修复：`RuleEngineRepository.ruleSetsWithVersions()` 只取生效/影子版本成员含 C03 的规则集；空间风险规则集只由 `SpaceRiskEvaluationJob` 消费；回归 `RuleEngineRuleSetSelectionTest`。已知取舍：以"成员含 C03"作为合法性规则集的启发式判定，只配 C01/C02 的规则集会被无声跳过；需要时改为 `rule_set` 显式用途列 | 给 `rule_set` 加 purpose 列 | 不改阶段 7 表结构即可区分；E1 验收时撞到 `C03.fresh_seconds` 缺失 |
| 9-29 | 执行会话截图落盘 | 不批准下载 chromium；浏览器验收由领导统一做，执行者以逐帧抄录代替 | 下载 150 MB 浏览器二进制 | 外部下载不擅自做 |
| 9-30 | 阶段 7 生产隔离测试对 `rule_set` 的零计数断言 | 改为"无生效/影子版本、无 LEGALITY-DEMO 种子规则集"，允许迁移 062 登记的 SPACE-RISK-DEMO（PUBLISHED+DEMO，未激活）及其参数存在 | 让 062 不登记规则集 | 决策 9-16 已定规则集与参数由迁移登记、激活由种子/接口做 |
| 9-31 | 空间风险页"编号"列显示 C04 技术键 | `source_risk_id` 以 `C04:` 开头时屏幕显示"C04 自动评估"，技术键与 `risk_id` 进 title；种子/外部来源的业务编号照常显示 | 给 C04 风险另造业务编号列 | 键里的规则版本/计划/目标/窗口在详情各自有字段，不重复上屏 |
| 9-32 | 空间风险页分页组件传参错误（验收发现） | `UPagination` 的 `size` 是尺寸档位，每页条数走 `page-size`、总数走 `item-count`；领导修正，页面此前因 naive-ui 渲染异常整页空白 | 交 E2 返工 | 验收阻断，一行修复 |
