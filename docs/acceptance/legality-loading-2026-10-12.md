# 合法性研判加载优化（2026-10-12）

## 问题与调整

本地研判历史约 10 万条。旧查询在过滤最新记录之前关联识别、规则版本、运行记录等资料，今日计数路径先处理约 4.4 万条历史记录，导致页面列表与顶部统计等待。

`LegalityEvaluationReadRepository` 对 `latest_only=true` 使用先筛选最新记录的派生表，并以 `OFFSET 0 ROWS` 保持筛选先于外层关联。候选行可先按模式、主体、目标、计划和日期缩小；判断是否存在更新记录仍查完整历史，不把新记录不匹配日期、状态、来源或权限解释为可以展示旧记录。同秒按 evaluation_id 尾键比较，TARGET/PLAN 与空主体引用保持原语义。列表、count、summary 和既有最新目标投影共用实现；历史详情和 `latest_only=false` 保持直接读取。

不新增缓存或预填结果，不改数据、权限、研判、复核、通知及动作状态。无数据库迁移和接口字段变化；业务前台继续调用既有列表/汇总接口，后台现有消费者无需适配。

## 验证记录

- 本地 PostgreSQL 只读快照对比：修改前后均 54 条；旧计数 EXPLAIN ANALYZE 约 3553 ms，先筛最新方案约 280 ms。该项是单次 SQL 耗时，不是整页加载指标。
- 新增 H2 与隔离 PostgreSQL 回归：同秒尾键排序、日期含起不含止、状态/来源不匹配不能回退旧研判、历史仍可读取、summary 与列表同源。
- 按页面完整条件（不额外限定 subject_kind）在同一只读快照比较研判编号与原始结论：双向 EXCEPT 差异 0；该次新查询 EXPLAIN ANALYZE 714 ms。模拟器持续产生记录，样本规模与并发负载会变化。
- 隔离构建执行 `mvnw.cmd -Dtest=LegalityReviewApiTest,LegalityReadApiTest,LegalityScopePostgresTest,LegalityStatusProjectionTest package`：108 项通过、0 失败、0 错误、0 跳过。其中 PostgreSQL 29 项使用 `stage456_verify_no_successor_20261009` 下独立随机 schema，未向业务库写测试夹具。首次验证有新增测试重复参数及测试连接端口错误，修正后上述完整重跑通过。
- 本地后端已加载经过验证的查询类，保留现有其他运行类和启动参数，`app.dev-seed.enabled=false`。无需数据库迁移或历史回填。
- 登录现有业务账号的 Chrome 实测：旧列表请求多次约 4.9—15 秒（含一次 15 秒超时），新列表样本 2400、2829、2746、2798 ms；新汇总 1274、1277、1899 ms。此为本机现有模拟数据与并发负载下的请求耗时，并非生产性能保证；列表响应后详情独立加载。
- 浏览器实际刷新、切换“非法”、翻到第二页（显示剩余 4 条、总数 14）、返回“全部无人机”通过。修复后验收区间无控制台 error；保存页面截图。临时请求计时日志已移除，前台代码无最终变更。
- 前端 `npm run build`、`node tools/scan.cjs` 通过；构建沿用现有大包警告。默认沙箱构建遇 Windows realpath 权限限制，沙箱外重跑通过。
- `npx playwright test e2e/admin-migration.spec.js` 已尝试：默认另起服务未完成，改为复用当前前端后因缺少 Playwright Chromium headless shell 无法启动。该迁移自动用例未通过，不计入上述真实浏览器验收。
- 前后仓库 `git diff --check` 通过（仅已有换行符提示）。
