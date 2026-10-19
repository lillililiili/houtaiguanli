# 模拟验收月报加载超时修复

## 原因与处理

本地管理端“综合运行 → 模拟验收口径 → 2026-10 月报”原先等待后报网络错误。实际后端 readiness 为 UP；复现期间 PostgreSQL 正在执行 `ObservationMetricsRepository` 的轨迹查询，读取坐标及来源 JSON 后排序。当前业务库约有 773 万条轨迹点，本次月份的符合条件点数超过既有 500,000 点上限，旧实现仍先装载 500,001 条明细才报告超限。管理端默认 15 秒中断，并把该超时误报成网络错误。

- 在同一 REPEATABLE_READ 快照内，复用完全相同的时间、来源、合并状态和权限谓词，先用无排序、有界的窄行查询检查点数。
- 保留 500,000 点上限和原始连续实测片段计算；超过上限仅返回轨迹指标 UNAVAILABLE，其余业务分区正常返回。不会提供截断合计或把未知变成零。
- 五类业务报表预览等待上限改为 120 秒，与既有导出相同；普通查询仍为 15 秒。TIMEOUT 与 NETWORK_ERROR 分开；写操作超时继续要求先核对结果，避免重复提交。
- 加载失败时统计区间不再显示“正在计算”。未改变真实/模拟来源口径、权限、历史或规则配置，验收种子仍关闭。

## 验证

- 后端：`mvnw.cmd -o -Dtest=ObservationMetricsBudgetTest,ObservationMetricsBudgetPostgresTest,ObservationMetricsTest,ObservationStatisticsApiTest,AcceptanceReportingApiTest,BusinessReportingApiTest test`，66 项通过，失败/错误/跳过均为 0。其中 6 项在独立 PostgreSQL 16/PostGIS 测试库执行，覆盖不足/恰好/超过预算、真实/模拟、时间、合并别名、组织和授权元组边界；测试预算缩小至 4，正式预算不变。测试库与业务库独立。
- 后端：`mvnw.cmd -o -DskipTests package` 成功。打包阶段跳过测试，不重复当作测试通过证据。
- 前端：lint 通过；全量 371 项中 368 项通过，3 项本机代理测试因沙箱连接 EACCES 失败。在允许本机连接的环境复跑代理、报表页面和超时测试，18 项全部通过；前端 build 成功。
- `node scripts/check-local-startup.mjs --business-dir D:\沉积岩\demo-ronghe\dongying-vue --report D:\沉积岩\houtaiguanli\artifacts\local-runtime\report-fix-startup-check.json`：退出码 0，报告时间 `2026-10-18T05:20:09.292Z`，7 项全部通过，无跳过。前后台 API、代码来源、地图、模拟器均已检查。
- 实际浏览器复用用户已登录的管理员会话：正式月报保持真实来源零记录；切换模拟月报后约 5 秒内观察到业务汇总和图表；模拟日报也能返回；最后恢复为模拟月报。仅验证既有登录会话的页面读取，未重新执行账号密码登录。
- 实际月报超限说明正常显示；日报显示“已有观测，但没有符合统计条件的连续实测片段”，保持原质量及来源门槛，不补造时长/里程。导出逻辑由本次后端既有 Excel/PDF 回归覆盖，未把按钮可用当作实际下载成功。

本轮仅本地修复，无服务器部署、数据清理或业务种子恢复。运行日志、自动检查报告和页面截图位于忽略提交的 `artifacts/local-runtime/report-fix-*`。
