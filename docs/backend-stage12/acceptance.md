# 阶段 12（清理杂物 + 筛选工具栏统一）验收记录

日期：2026-09-07。基线：`main@c38becf`（含 A 的 P4-A/P5 合并 `0496c96`）。计划 `docs/superpowers/plans/2026-09-07-collaborator-b-stage-12-cleanup.md`，决策 12-1…12-18。

## 执行者报告
| 任务 | 会话 | 结果 | 报告 |
| --- | --- | --- | --- |
| 12.1 前端清理 | Session 1 | 删除 26 个文件（13 个 legacy 页面脚本、`mock.js`(350 KB)/`case.js`/`search.js`/`app.js`、8 个死文件、`LegacyHost`）；`index.html` 脚本 23 → 7；`map.js` 去 3 处 MOCK（图层键读 `a.layer`、图例按数据填充、告警定位去 `DISTRICTS` 裸引用——后者删 mock 后会让告警页地图抛错）并删除按类型猜图层的回落（缺 `layer` 不画 + 告警一次）；`video.js` 去 2 处；`window.APP/ROUTES` 与 `UI.confirmAction/openRiskVerification/openCounterAuth/openDeviceRebootForm` 死挂载删除；`apis` 键移除；未注册路由键统一 AccessDenied；登录页删除 `DEMO_PASSWORD` 与明文密码；`AccessDeniedPage` 中文页名（`navModel` 给 airspace/risk 补 ROUTES 名但不进 NAV，审查核实菜单未重现）；scan 规则改为"绘图外壳层不得引入第二个时钟"（作用域 `ui|map|charts`）；`falsify.cjs` 删除及三处引用清理；node 单测 53/53、14 页冒烟零错误；`dist` 12M → 11M，`assets/js` 2.1M → 1.1M | `task-12.1-report.md` |
| 12.2 后端 405 + 文档 | Session 2 | 405 `METHOD_NOT_ALLOWED` 包络（`Allow` 取框架原值、不回显路径与方法、未登录仍 401），`UnmappedPathApiTest` 6/6；进度说明加过期横幅；基线 §6 G3/G4 更新并挂验收链接；新建《部署说明-数据库迁移与升级》 | `task-12.2-report.md` |
| 12.3 验证 + 仓库盘点 | Session 3 | 只读盘点《仓库分支与工作树盘点-2026-09-07》（9 远端分支：2 条已合入可删；7 条 09-03 未合入、`merge-tree` 实测冲突 1–8；`codex/workflow-coordination` 零冲突建议合并；1 个 codex 工作树含 6 个未提交改动、3 个游离提交——由用户裁定）；H2 631/0/74 skip（skip 全为 env 门禁 PG 类）、PG 十套件 73/73 | `task-12.3-report.md` |
| 12.4 筛选工具栏两段式 | Session 2 | 共享 CSS `.toolbar > .toolbar-fields + .toolbar-actions`、`.toolbar-note`，控件宽度统一，删各页私有覆盖与 `.spacer`；改 Flights（3 个工具栏）、Punish、Alarms、Evidence、Stats、Airspace、SpaceRisk；`DevicesPage`（A）本已是 grid 两段式不改；`RolesPage` 面板头 spacer 不属工具栏不改 | `task-12.4-report.md` |

## 领导验收
- 工具栏审计（自写脚本按元素垂直中线分行，1280/1366/1440 三视口）：flights 筛选两行 + 动作行；punish 筛选两行 + "查询/重置/说明"一行；alarms 两行无动作；stats 一行；evidence 三行（关键字桥接控件已收窄，与下拉能同行时同行）+ 动作行；devices 五字段 + 动作行。**无任何只有按钮或只有说明文字的孤行**。
- 前端：`npm run build` 通过、`node tools/scan.cjs` 全部通过、node 单测 53/53、`index.html` 7 个 script、`public/assets/js` 只剩 `charts/geo/map/ui/video` + vendor、`dist` 11M。
- H2 全量（执行者全部冻结后）：121 类 / 631 run / 0 fail / 0 err / 74 skip（skip 全为 env 门禁的 PG 类；PG 十套件由助手实跑 73/73）；`git diff --check` 通过。
- 审查（Session 4）：5 轮，P0 0 / P1 0 / P2 3（main.js 过期注释、map.js 图层回落、scan 规则守错位置）全部处置；确认 26 个删除项删前引用为零、12.4 四页 v-model/:disabled 集合与基线逐项一致。

## 留给用户裁定
- 分支/工作树处置（盘点文档）：2 条可直接删；`codex/workflow-coordination` 建议合并；其余先归档再删；codex `df48` 工作树的 6 个未提交改动需本人看过。
- `AirspacePage`/`SpaceRiskPage` 保留不挂菜单（12-8）。

## 后续
- 405/404 已规范；其它 Spring 默认错误（400 参数绑定等）已有处理器。
- `check-ui-text` 在 7 个改过的页面有 7 处既有命中（非本次引入），待文案专项。
- scan 规则的时钟纪律是否扩到 `src/`：E1 处理中（若命中过多则以注释说明靠评审）。
