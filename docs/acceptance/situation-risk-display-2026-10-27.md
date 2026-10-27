# 融合感知风险展示实施与验收记录（2026-10-27）

## 当前结论

代码、构建及新版服务加载已完成，尚未完成真实浏览器验收。不能把本记录视为整体验收通过。

本次在已有工作区继续实现，保留前序光电、模拟视频及其他未提交修改；没有提交、迁移、启用开发种子、修改告警规则或操作真实设备。使用隔离 PostgreSQL 测试库 stage456_verify_riskdisplay_20261026；浏览器验收仍须使用正常 local,qa 服务和明确模拟来源。

## 已实现

- 光电放大窗口右侧增加全部、无人机、鸟类异物、气象分类及服务端计数、分页，按风险事项而非目标数统计；同一目标不同区域独立。
- 当前列表及计数仅保留有效 CURRENT；未知、失效不冒充当前无风险，未结束处置及真实停止/通知回执继续在处理中视图保留。
- 选中详情独立按 key 刷新，不依赖列表页；同版本的反制审批和执行进度单独刷新。失效、权限变化、请求失败清除当前地图依据并提示历史状态。
- 地图及详情读取告警保存的研判、空域版本、事件航线、鸟类空间事实、气象事实及实际执行事实。资料缺失或坐标系未记录保持未知；不以最新位置/新边界替代当时依据。
- 原地图、视频点选/框选按 risk_case_id 维护勾选；查看鸟类或气象不清空无人机批量选择。预测转实际时沿用同风险事项，提交绑定该事项最新版本和当前办理事件。
- 批量逐项权限、证据、授权、版本、幂等与结果回读保持；不同区域的成功、失败和结果未知互不串用。鸟类及气象复用核验、通知和回执组件。
- 视频窗口展开状态由父级统一控制；定位依据时可正常折叠回主地图。光电预测风险使用当前查看目标构建预测点，选择不下发转向指令。

## 代码变更说明

以下仅列本轮风险展示相关改动，不将前序光电/视频/模拟器修改重新算入。

| 文件/类 | 方法/函数 | 方法作用和本次改动 |
| --- | --- | --- |
| 后端 modules/situation/api/SituationRiskController.java、SituationRiskDtos.java | list、detail、Page/Item | 新增统一分类分页及按 key 详情契约，保留各类读取可用性和未知字段 |
| 后端 modules/situation/application/SituationRiskReadService.java | list、detail、activeUav、activePresence | 同权限聚合风险、当前性判断、排序计数、独立焦点权威读取 |
| 后端 modules/alarm/infrastructure/AlarmReadRepository.java | situationRows、situationItem、targetVisible | 按风险事项投影；单项读取精确限制 key，校验关联目标范围 |
| 后端 modules/risk/infrastructure/RiskRepository.java | situationCandidates、currentUnit、planReferenceVisible、routeReferenceVisible | 预先排除无未完工作的确定历史，保留真实通知回执，验证关联引用 |
| 后端 modules/alarm/api/AlarmAirspaceController.java | evaluationRefs | 增加告警冻结研判引用读取入口 |
| 后端 modules/alarm/application/AlarmAirspaceReadService.java | evaluationRefs | 只返回首次和真实升级告警保存的研判引用，不替代为目标最新研判 |
| 后端 modules/assessment/api/ExecutionEvidenceController.java | read | 增加实际执行事实证据读取入口 |
| 后端 modules/assessment/application/ExecutionEvidenceReadService.java | read及证据映射 | 核对精确事实 ID、来源、计划版本、坐标系与可读身份资料 |
| 后端 modules/flight/infrastructure/FlightExecutionFactRepository.java | historical、pilotLabel、reportingLabel、planReportingLabel | 读取历史原始事实及版本，身份名称按权限标明当前目录来源，不按最新事实替换 |
| 后端 modules/alarm/api/UavEventBatchDtos.java | PrecheckItem、ItemResult | 批量输入输出增加风险事项、版本与预测关联 |
| 后端 modules/alarm/application/UavEventBatchService.java | precheck、verify、counter及逐项处理 | 同一事件的不同风险事项分别处理、审计和返回结果 |
| 后端 modules/alarm/application/UavEventBatchItemService.java | resolve及逐项核实/反制 | 精确绑定 case、办理事件及版本，锁后重验关联；旧事件关联不唯一时拒绝 |
| 前台 src/pages/SituationPage.vue | eoCandidates、focusEoRisk、receiveRiskStatus、locateRiskEvidence、syncMapData、mapPredictions | 接入分类、详情、勾选和共用地图依据，同步焦点和当前目标定位 |
| 前台 src/pages/situation/useSituationRiskItems.js | useSituationRiskItems、refresh | 分类分页轮询、请求竞态隔离和失败状态，不把失败当零 |
| 前台 src/pages/situation/situationRiskItems.js | candidateFromRisk、riskFromAlarm、riskCoverageText | 风险事项转换、可操作提示和光电覆盖关系 |
| 前台 src/pages/situation/SituationRiskList.vue | 分类、列表、分页模板 | 显示风险等级、原因、区域/任务、当前阶段和选择入口 |
| 前台 src/pages/situation/SituationRiskEvidence.vue | load、refreshStatus、canLocate | 详情独立权威刷新、办理进度更新、异步代际保护及操作门禁 |
| 前台 src/pages/situation/riskEvidence.js | loadRiskStatus、loadRiskProgress、loadRiskEvidence、riskEvidenceContext、riskEvidenceView | 按类型读取真实依据，映射历史/当前文字和图形，保留未知与零值区别 |
| 前台 src/pages/situation/riskEvidenceGeometry.js | riskContextCoordinates、drawRiskEvidenceGeometry | 绘制选中事项的证据图形及定位坐标；实测目标由融合层管理 |
| 前台 src/components/video/DeviceVideoWorkspace.vue | toggleExpanded、模板和样式 | 统一展开状态，接入右侧分类与详情，保留已勾选事项和批量操作 |
| 前台 src/pages/situation/eoSelection.js、EoMapSelection.vue | selectionKey、toggleSelected、reconcileSelected、地图选择 | 同一目标多个 case 独立保留，框选聚合，不按 event 合并 |
| 前台 src/components/video/EoVideoDetections.vue | 识别框关联和选择处理 | 一个识别目标可对应多个区域事项，保持原帧关联守卫 |
| 前台 src/components/video/EoBatchActions.vue | identity、refresh、submit、isPending等 | 按 case 隔离校验、草稿、结果未知、结果回读及处置链接 |
| 前台 src/services/eoWorkspaceApi.js | precheck及批量接口 | 传递 case/版本，保留旧事件传输兼容 |
| 后端测试 SituationRiskReadServiceTest、UavRiskCaseReadPostgresTest、AlarmAirspaceReadServiceTest、ExecutionEvidenceReadServiceTest、UavEventBatchItemServiceTest、UavEventBatchServiceTest、UavEventBatchApiTest | 相应回归用例 | 覆盖当前/历史、同事件多区域、真实停止/回执、权限范围、版本幂等和依据读取 |
| docs/situation-risk-read-api.md | 无具体方法 | 新增接口契约、历史和权限边界 |
| artifacts/local-runtime/load-risk-display.ps1（本机非提交文件） | 加载脚本 | 由操作者执行，先核对原后端身份及模拟器停止状态，再按原配置加载新 jar并记录运行清单 |

后端路径基于 E:/houtaiguanlii/server/src/main/java/com/uav/lowaltitude；前台路径基于 E:/rwurenji/_rong/dongying-vue。

## 已取得的验证证据

- 本轮八个后端相关测试类最新报告合计77项，0失败、0错误、0跳过；其中25项是隔离真实PostgreSQL测试。不同类按变更范围分批执行，未声称整仓全套测试通过。
- 最后 CURRENT 口径修正单独重跑 SituationRiskReadServiceTest 10项通过，2026-10-27 02:06:11 -04:00完成package/repackage，可运行jar为 server/target/low-altitude-server-0.1.0-SNAPSHOT.jar。
- 前台外置验证覆盖：同事件不同区域独立选择、预测转实测事件切换、部分未知结果、分页请求竞态、读取失败不显示0、几何有效性、精确历史事实、数量未知/零值、撤权及当前单位状态、进度独立刷新、A慢B快切换不污染。
- 前台生产构建与源码合规扫描通过。扫描保留既有 riskNoticeState.js 一条待办；不是本次新增。
- 后端本轮文件及前台 git diff --check通过，仅有既有换行警告。
- 启动检查：后端就绪、前后台构建来源/API代理、前后台地图及一致性通过；模拟器收发状态 FAILED，所以整体启动检查失败，未宣称全部就绪。

## 尚未完成的实际验收

自动审批此前拒绝本代理停止PID31584并启动新版，只返回 blocked by policy，无进一步原因。用户已于2026-10-27 02:14:53 -04:00执行人工加载脚本成功；回读确认新运行包 target/runtime-risk-display-20261027-021452/low-altitude-server-0.1.0-SNAPSHOT.jar 与构建包SHA256一致（78AD079359FC9BFBE4602DB8275FFDDBCF915F7C2259D81C746E449986441EFD），readiness=UP。保留旧包、原DB/地图/视频配置与dev-seed=false、weather-risk.qa=false。无需再次运行切换脚本。

浏览器工具多次返回 Work 设置禁用本地浏览器；用户已表示开启，但工具仍未获得浏览器权限。未用其他路径绕过，未把组件测试当作真实浏览器验收。

待继续：
1. 新版API加载、指纹及健康已核对；恢复正常模拟收发后重新运行启动检查。
2. 保留模拟器未保存草稿，通过正常模拟输入生成无人机、鸟类及气象同场景。
3. 真实浏览器检验分类/数量、同机多区域、地图与详情、选择保留、放大缩小和越范围定位。
4. 实际提交无人机核实和模拟反制，鸟类/气象核验与通知，读取真实业务回执；验证停止及未知结果。
5. 更换编号/位置/来源复验，保存实际截图及结果后再给整体验收结论。

不接入真实设备，不开启开发种子，不手动向业务库注入结论。
