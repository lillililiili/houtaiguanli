# 阶段 11（融合感知页接真实数据）验收记录

日期：2026-09-07。基线：`main@ae17d1e`。计划 `docs/superpowers/plans/2026-09-07-collaborator-b-stage-11-situation-page-real-data.md`，决策 11-1…11-7。

## 执行者报告（Session 1，`task-11.1-report.md`）
- `src/services/situationData.js` 纯函数装配层 + `tools/situationData.test.cjs` **47/47**；`SituationPage.vue` 只替换 `<script setup>`，模板一行未改；`targetApi.js` 只加 `fusionStatus`。
- `npm run build`、`node tools/scan.cjs`、`node tools/falsify.cjs`、`check-ui-text.sh` 零命中。
- 与计划的三处差异（已认可）：图层键跟 `mock.js` 的 `airspaceType` 走（临时管制属 nofly），否则图例勾选与地图不一致；空域对象补 `center`（`map.js` 无条件读）；上屏用 `airspace_no` 不用内部 ID。
- 验收中修掉的三处自身缺陷：详情与轨迹分开 try；研判分页上限 100（原 200 被静默吃掉导致全场"待确认"）；`subtype` 走字典。
- 审查第 2 轮 P1-1：三个操作按钮被删 → 决策 11-7 要求保留（转风险可用、其余禁用标未接入、置信度条保留），修复后 class 计数应回到基线 62。

## 领导验收
- 浏览器（5174 → 8081 `uav_stage10_verify`，API 登录注入会话）：页面 `window.MOCK` 不再被本页读取；实时告警栏 11 条真实告警（`MB-S7-*`）；地图空域标注为"类型 + 业务编号"（`KY-S7-H1`、`KY-9-001/002`、`KY-S7-P1`），设备点位与回放目标可见；点击告警联动选中目标、地图居中并出现多源融合浮球（置信度 90%，来源"阶段七规则引擎模拟源"，置信度条 1 条）；悬浮卡显示编号/多旋翼无人机/非法/8 m/s/50 m/经纬度，"实时视频（未接入）"为禁用态，"查看告警 →"可用；1280 与 1440 视口无横向滚动；控制台无新增错误（仅残留 HMR 中间态的历史条目）。
- 审查（Session 4）：第 1 轮 DOM 基线（62 处 class），第 2 轮 P1-1（三按钮被删）→ 决策 11-7 修复后 class 计数回到 62；E1 判断认可：禁用按钮不接假回执处理器，未使用的 `CH` 全局绑定不保留。
- 后端零改动；工作区另含 Session 3 的 `Stage9PostgresTest` 用例 24（阶段 10 跟进，一并提交）。

## 未接入 / 已知限制
- AOA 方位线：读接口未透出 `quality.bearing_deg`，地图不画方位线（接口缺字段，留后续）。
- 空域孔洞忽略（11-2）；实时视频/通知机场/派发驱离禁用标"未接入"（11-4/11-7）。
- 悬浮卡"风险等级/违规事由/处置状态"三项接口无对应字段，按空值不渲染。
- 融合面板无分路置信度（接口只给目标级 `fusion_confidence`）。
