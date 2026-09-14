# 阶段 12（清理杂物）自动决策记录

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| 12-1 | `index.html` 只保留 `vendor/echarts`、`ui.js`、`charts.js`、`geo.js`、`map.js`、`video.js`，删除 `mock.js/case.js/search.js/app.js` 与 13 个 `pages/*.js` | 所有路由都有 Vue 页面，legacy 页面不可达；350 KB 演示数据白白下载 |
| 12-2 | 删除 8 个死文件（`JamAuthModal`、`AlarmNotifyModal`、`weather.js`、`useChart.js`、`counterAuthModal.js`、`deviceRebootModal.js`、`useCarousel.js`、`CarouselModal.vue`）；`airportApi.js` 保留 | 无引用；机场接口后续阶段要用 |
| 12-3 | `map.js` 空域图层键由数据对象的 `layer` 提供，不再回落 `window.MOCK.airspaceType` | 去掉 mock.js 后回落分支会把临时管制画进 limit 层，与图例不一致 |
| 12-4 | 登录页不再显示任何密码；"忘记密码"改为联系系统管理员 | 明文密码上线即事故 |
| 12-5 | 移除悬空的 `apis` 路由键；删除 `LegacyHost`，未注册键一律 AccessDenied | 消除"给角色分配 apis 就落到 Mock 页"的陷阱 |
| 12-6 | 已映射路径用错 HTTP 方法返回 405 `METHOD_NOT_ALLOWED` 并带 `Allow` 头，未登录仍 401 | 与已修的 404 同类 |
| 12-7 | 分支与 worktree 的删除/合并由用户裁定，B 只产出盘点 | 不可逆操作 |
| 12-8 | `AirspacePage`/`SpaceRiskPage` 保留，不挂菜单，待用户裁定 | 已完成的真实接线，删除是范围变更 |
| 12-9 | 过期的《系统开发进度说明-2026-09-05》加横幅指向盘点文档，不删 | 保留历史，防误引用 |
| 12-10 | 405 的 `Allow` 头如实转述 Spring 的 `supportedHttpMethods`，不补 HEAD/OPTIONS | 不伪造能力；有依赖方再议 |
| 12-11 | 基线 §6 G3/G4 标"已完成"时挂上对应阶段验收记录链接（G3 → 阶段 3/4 验收，G4 → 阶段 7/8 验收） | 基线是对外口径，状态要有验收证据可追 |
| 12-12 | `tools/scan.cjs` 的"展示用时间戳须派生自 `M.now()`"规则改为指向现存的唯一时间源（顶栏时钟），`video.js` 的 OSD 时间不再以已删除的 `M.now()` 为判据 | `M.now()` 随 mock.js 删除，规则文本过期；规则意图（不出现第二个时间源）仍满足 |
| 12-13 | 删除 `tools/falsify.cjs` 及其在文档/脚本中的引用 | 它只对 mock.js 的 `selfCheck()` 做注入证伪，被测对象已不存在 |
| 12-14 | `AccessDeniedPage` 不再回显英文路由键，改用导航字典里的中文页名 | 技术键上屏违反文案规范（清理时顺带修） |
| 12-15 | 全站筛选工具栏统一为两段式：`.toolbar > .toolbar-fields`（筛选项，允许换行）+ `.toolbar-actions`（查询/重置/导出与说明文字，靠右，不与筛选项混排）；控件宽度统一（下拉 128–160、文本 160–220、日期范围 300），按钮不再单独落到孤行 | 用户指出飞行监管/处罚页筛选栏按钮与说明文字落到孤行；1366 视口审计：flights/alarms/punish/devices/stats/evidence 六个工具栏全部 2–3 行且排布各异 |
| 12-16 | 常跑 PG 回归清单加入协作者 A 的 `MqttP1PostgresTest`（env 门禁，H2 下 skip） | 助手指出它既在 H2 被 skip 又不在十个套件里，等于没在跑；只跑不改，不越 A 边界 |
| 12-17（修订） | `DevicesPage.vue`（A）的工具栏保持其 grid 两段式不改；`RolesPage.vue:166` 的 `.spacer` 是面板头（`UPanel` 共享写法）不是筛选工具栏，不改 | 设备页本已是两段式，包一层反而破坏网格；面板头 spacer 由 `panel.css`/`UPanel` 统一产出，单改一处反而不一致 |
| 12-18 | 三视口工具栏审计由领导在自己的预览环境执行（E2 预览名额被占） | 验证不能只靠设计意图 |
| 12-19 | scan 时钟规则：作用域扩到 `src/`（.vue/.js）+ 原绘图外壳层，只匹配 `new Date()`（`Date.now()` 在本仓库是幂等键/查询参数，不是时钟），整文件放行仅 `HeaderBar.vue`（唯一时钟）与 `BigScreenApp.vue`（独立全屏视图），其余三处非展示用法按行豁免 | E1 先量后选：字面照做要写 18 条允许理由，等于教人见规则就放行；已用注入证伪规则会响 |
