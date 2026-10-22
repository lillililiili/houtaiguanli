# 融合感知光电视频与多目标办理

## 本轮范围

业务前台位于 `E:\rwurenji\_rong\dongying-vue`，后端位于本仓库 `server`，模拟器位于 `E:\rwurenji\_rong\tools\device-simulator`。保留两个仓库已有的空域、地图、处置方式等未提交修改；本轮未提交、未推送、未部署远程服务，未新增业务种子或修改真实设备配置。

设备入口打开该设备的视频小窗，放大后左侧视频、右侧办理栏，下方使用同一个地图实例。缩小保留选择与操作结果；关闭视频不调用跟踪停止接口。原目标维度视频入口保持。

- RAW 与 AI 自动选择依据有效任务、设备回执和媒体就绪状态；AI 不可用时回落同设备 RAW。
- 地图与视频共享最多20个告警目标；焦点与选择集合独立。事件关联变化的项目保留并标记不可办理，不重绑定到新告警。
- 原始流只观看；AI 框须匹配设备、流、任务、实际播放帧时间和可信目标关联。时间误差超过500毫秒或未关联告警时不开放操作。
- 核实明确选择属实或误报；反制逐项选授权方式、执行设备与迫降/驱离。所有结果逐项显示，独立失败不丢失其他结果。
- HTTP 5xx、超时、网络中断、不完整结果均保留原提交标识并提示核对记录，禁止自动换键重交。处置授权受理不代表设备执行成功。
- 指定设备跟踪请求走原持久化调度流程；等待时不自动改用其他相机，确认切换绑定原任务。

## 代码变更说明

| 文件或类 | 主要方法/组件 | 作用 |
| --- | --- | --- |
| 业务前台 `SituationPage.vue` | `openEoVideo/selectEoTargets/closeEoVideo` | 光电设备入口、共享选择、单地图放大恢复 |
| `DeviceVideoWorkspace.vue` | `refresh/toggleExpanded/endTrack` | 设备双流窗口、操作栏、明确结束跟踪 |
| `DeviceVideoWorkspace.vue`、`EoBatchActions.vue` | 多源 `watch` | 同设备、同事件的实时数据刷新不重建播放器、不关闭正在填写的表单；设备、事件可用性或账号变化仍清理旧状态 |
| `AuthenticatedHlsVideo.vue`、`authenticatedHlsLoader.js` | `reportFrame/streamUrl` | 授权设备媒体、播放帧时间、同设备流限制 |
| `EoVideoDetections.vue`、`RectangleSelection.vue`、`EoMapSelection.vue` | `select` | 点选、框选、视频缩放坐标转换 |
| `deviceVideoState.js`、`eoSelection.js` | `matchingVideoFrame/reconcileSelected` | 播放身份、帧同步、目标事件身份保护 |
| `EoBatchActions.vue`、`eoWorkspaceApi.js` | `refresh/open/submit` | 批量预检、逐项表单、幂等与未知结果保留、进度回读 |
| `EoDispatchControls.vue` | `perform` | 指定设备进入持久队列，确认原任务切换 |
| `DeviceVideoController/DeviceVideoService` | `video/resource` | 设备视频状态与每次授权媒体读取 |
| `QaDeviceVideoController/QaDeviceVideoService` | RAW登记、帧上报 | local+qa 模拟双流与受控帧接入 |
| `VideoDetectionAssociationService/VideoStreamRegistry` | `resolve/current/appendFrame` | 来源观测关联、双流租约、有限帧缓存 |
| `TargetVideoService/EoEdgeRepository` | 现有跟踪守卫、设备当前任务读取 | 复用实际回执、任务和来源约束 |
| `EoDispatchController/EoDispatchService/EoDispatchRepository` | 指定设备请求、轮询 | 复用原device_id列，保持无指定设备的兼容行为 |
| `UavEventBatchController/UavEventBatchDtos` | `precheck/verify/counter` | 三个批量接口和逐项契约 |
| `UavEventBatchService/UavEventBatchItemService` | `submit/verify/counter` | 每项独立事务，调用原核实与授权服务 |
| 模拟器 `eo_video.py/eo_calibration.py/eo_media_clock.py` | 双流生命周期、校准媒体时钟 | 真实编码模拟形状、条码与HLS PDT对账；没有可靠时钟不报框 |
| 模拟器 `server.py/web/video-control.js` | 视频设置 | 校准场景与测试图模式 |
| 模拟器 `eo_observations.py`、`tests/test_eo_observations.py` | `observation` | 标准接口主观测可使用已登记辅助设备的实际 MQTT 来源链接；拒绝错误设备、编号、通道和真实来源，验证不同随机编号仍遵守相同规则 |
| `tools/situationFlow.test.cjs` | 测试加载器 | 适配相对模块路径及页面新增依赖，不改变业务断言 |

## 已完成验证

验证级别为L3，覆盖跨前后端权限、媒体身份、关联、版本、幂等与实际媒体时钟。

- 设备视频/跟踪/认证最终回归：166项通过，EO H2 36、EO隔离PostgreSQL/PostGIS 36、视频H2 40、视频PostgreSQL/PostGIS 40、租约单测5、认证9。包括空闲RAW、有效AI、AI回落、结束后旧资源失效、跨设备/来源/范围、错误帧与撤销关联，以及指定设备忙时排队、停止回执后原设备启动、旧无设备请求兼容。日志 `server/target/device-dispatch-verified.log`。
- 批量后端：24项通过，H2接口8、隔离PostgreSQL/PostGIS接口8、单元8。包括逐项提交、权限、范围、版本并发、幂等、来源、失败与结果未知。
- 模拟器：66项EO、12项视频、5项Node控件通过，包含真实FFmpeg像素/边界检查与真实fMP4/HLS首帧条码/PDT对账。
- 前端：原视频/取证及新增身份/帧/选择共18项通过；新增批量异常结果4项通过；窗口重连、切换设备的迟到响应和失败清屏3项通过；态势数据315断言、接口118断言、流程143断言通过，相关风险选择与处置表单/权限测试通过。
- 业务前台最后一次生产构建通过，保留现有大分包警告；后端最终打包通过，日志 `server/target/device-dispatch-package.log`。
- 新后端已在本地8081启动，PID61316；数据库仍为 `127.0.0.1:25432/houtaiguanli`，`app.dev-seed.enabled=false`。运行JAR SHA256为 `64CB7700CE6B743895D41586A6AA12C9144EC19B1C653BE181E36B30BAE458D7`；Flyway确认203个原迁移有效，无新迁移需要执行。
- 重启后 `check-local-startup.mjs --business-dir E:/rwurenji/_rong/dongying-vue` 退出0，报告 `artifacts/local-runtime/eo-video-startup-final.json`，前后台API、地图及模拟器基础状态均通过。模拟器仍为旧进程，此报告不代表新模拟视频或真实账号登录已验证。
- 独立静态审查发现的5xx未知结果、策略展示额外权限门槛及指定设备排队问题均已修正。页面没有隐式启动或停止设备的查看路径。
- 2026-10-21 浏览器补验：用户完成独立业务前台登录，页面显示超级管理员；实际点击两台光电设备的视频入口、放大、缩小、重连及关闭，均回读了页面结果。当前离线状态显示具体原因，不显示虚构画面；关闭后地图正常保留。原空域编辑页及模拟器资料输入草稿未刷新或关闭。
- 实际截图发现并修正放大窗口被平台页头遮挡、视频高度挤出小地图的问题：视频工作区挂载至 body，放大状态按剩余高度分配视频区，底部小地图固定可见。截图位于 `artifacts/eo-video-20261021/expanded-offline.png` 与 `small-offline.png`。
- 核实和反制表单已改用仓库公共 UField/UFormFooter，目标勾选使用 UControl。上述修正后，前端相关25项测试全部通过，生产构建再次通过；两仓库 `git diff --check` 通过。
- 用户手动启动新版模拟器并完成登录后，启动检查再次退出0，报告为 `artifacts/local-runtime/eo-video-startup-20261021-browser.json`。无目标场景实际浏览器播放 RAW，视频DOM为 `readyState=4`、`paused=false`、`videoWidth=960`、`videoHeight=540`，播放时间推进；截图 `artifacts/eo-video-20261021/raw-playing.png`。原始流不显示画面选择工具，选择通过地图完成。
- 联调补充标准接口主观测与 MQTT 辅助设备的已知关联后，先运行新增回归看到原实现失败，再修正并运行全部67项光电模拟器测试通过。没有扩大纯标准接口目标的推断范围。
- 2026-10-21 16:21–16:37（北京时间）浏览器续验：用户重启并登录的模拟器PID47676已加载来源关联修复。批次 `sim-1021042148-7355` 的两目标由标准接口主观测和已登记MQTT辅助来源生成真实平台告警；未放宽合法性、来源或证据门槛。重启检查 `artifacts/local-runtime/eo-video-startup-linked.json` 七项通过、退出0。
- 浏览器发现定时更新会重建播放器并关闭核实表单：以真实Vue响应式 `watch/nextTick` 编写两项回归，原实现两项失败，改为逐个监听稳定业务身份后全部27项前端视频/批量/工作区测试通过。修复后视频连续播放超过120秒；表单跨多次实时刷新保留核实结论和人工说明。
- 实际勾选两架无人机后显示“批量核实（1）／批量反制（1）”，另一项保留不可处理原因。明确选择属实并提交后，目标005的告警详情读取到超级管理员于16:30:02填写的人工核实记录；两项均显示已核实，反制可处理数变为2。
- 两项均选择本机四通道QA模拟器并提交直接反制。006于16:31:11收到打开回执、16:32:15完成并收到“设备已全部关闭”；005建立授权但因同设备占用未启动，窗口逐项显示执行受阻，告警记录显示尚未启动、无设备回执。随后通过现有页面撤销005未启动授权并保存模拟验收收尾原因；未伪造成功、未自动重交，未发送处罚移送。截图 `artifacts/eo-video-20261021/batch-counter-result.png`。
- 放大后视频、侧栏和小地图实际可见。小地图框选当前区域回读到005已选；006已完成转历史，两架当前告警的地图框选仍待下一批次验收。定位按既有地图32倍下限放大，最后移除会遮挡小地图的重复详情弹出，相关143条流程断言和生产构建再次通过；此最后定位调整仍需浏览器复核。
- 结束005跟踪时显示同设备原始流与“正在结束”原因；之后既有调度为其他目标创建新任务并恢复AI。此过程证明单任务结束状态的切流，但不能算作无其他排队目标的持续RAW回归。
- 实际MediaMTX使用无Cookie会话引导，原媒体时钟读取遇到302。新增严格资源/单UUID会话参数测试（修复前两项失败），保持禁止重定向、外部路径和额外查询参数；bootstrap使用 `cookieCheck=1`，后续只保留受限 `session`。全部69项光电模拟器测试通过，修复代码已对当前两路实际HLS成功解码帧条码并得到可信PDT锚点，未记录媒体凭据。
- 16:37通过既有控制接口正常停止本批次场景及收发端，反制继电器为0。媒体时钟修复仍需用户手动重启模拟器加载；未绕过自动审批的进程启动/停止拒绝。

## 最终浏览器验收（2026-10-21 16:48–17:05，北京时间）

用户重启并登录后，模拟器PID60184已加载媒体时钟兼容修复。批次 `sim-1021044835-e37b` 使用不同目标编号007、008，服务回读 `video_metadata=LINKED`，实际HLS播放帧与已关联识别框一致。原空域编辑页和模拟器输入草稿保持。

| 实际操作 | 页面与业务结果 |
| --- | --- |
| AI识别框点选008 | 已选1架，标为当前画面内；可办理数量按该事件预检显示 |
| 小地图框选007、008 | 已选2架，008画面内、007画面外；批量核实1、批量反制1；未在视频虚构第二个框 |
| 点击007清单行 | 当前查看变为007，仍保留2项勾选，设备继续跟踪008 |
| 视频框选008 | 实际拖框覆盖识别区域，正确选中008；清除选择同步生效 |
| 缩小再放大 | 已选集合保持，视频继续播放，识别框仍对齐；视频、侧栏、小地图均可见 |
| 明确确认切换到007 | 先显示等待原任务停止回执及同设备RAW，随后新任务 `33d8e9e8-9163-44d0-9cf2-1356aedc34c6` 跟踪007并播放其AI识别框；008不串入新画面 |
| 关闭视频并重新打开 | 服务回读仍是007原任务，未发停止指令；重新打开继续显示007 |
| 停止两路模拟推流 | 页面清除旧画面/框，显示断流原因和重连；跟踪任务仍在 |
| 恢复模拟推流 | 页面自动恢复同设备同任务AI播放，框重新关联007 |
| 逐项结束当前及后续排队跟踪 | 设备实际停止回执完成后显示无跟踪目标、原始流自动；17:02仍持续RAW，视频readyState=4、paused=false、960×540 |
| 空闲设备再次明确请求跟踪007 | 先显示指定设备排队，实际回执后新任务 `9ccf718a-f7ff-4922-b5ce-5f4f2e70e382` 进入TRACKING，双流PUBLISHING、元数据LINKED |
| 侧栏定位目标 | 小地图定位并放大，未弹出遮挡小地图的大详情卡 |

最终浏览器发现的状态文案已收敛：RAW显示“原始流无识别信息”；没有匹配当前播放帧时显示“画面关联待确认”，不误称画面外；结束请求提示以上方实际跟踪状态为准，不长期停留在等待回执的说法。结束确认弹窗只在有明确任务选择时渲染。

最终测试：后端190项相关检查通过；模拟器69项EO、12项视频、5项Node控件通过；前端视频/批量/工作区27项通过，流程143条断言通过。最后一次生产构建退出0（10.68秒，仅原有大分包提示），主包 `index-B_QUqWyK.js`。最后文案调整不修改业务逻辑。

最终启动检查 `artifacts/local-runtime/eo-video-startup-accepted.json` 时间为 `2026-10-21T09:02:25.311Z`，七项PASS、退出0。脚本的账号登录项仍明确为NOT_TESTED；真实登录另由浏览器显示超级管理员、执行核实/反制与回读记录证明，未混用两种证据。

截图：`artifacts/eo-video-20261021/ai-two-selected.png` 为放大后的实际界面；`raw-after-end.png` 为稳定空闲RAW；`both-streams-unavailable.png` 为两路断流；`batch-counter-result.png` 为上一批次逐项反制结果。最终代码刷新后再次点选007，页面回读当前画面内、已核实、可反制1；截图 `final-live-workspace.png`，独立业务验收页保留打开。两仓库最终 `git diff --check` 退出0。

## 验收结论与接入边界

本地模拟主流程验收通过。AI单路异常自动回落RAW、跨来源/权限/范围、版本冲突及未知结果等边界由后端集成和前端回归覆盖；浏览器实际故障操作覆盖两路断流和恢复，未单独注入AI单路故障。重复提交与结果未知没有通过真实网络故障再次注入，不将自动化结果写成浏览器实测。

模拟元数据当前按1Hz产生，只有匹配实际播放帧且误差不超过500毫秒的识别框开放点选；缺少匹配帧时暂停框交互，不放宽时间校验。真实厂商双流地址、识别框坐标/时间戳及平台目标关联尚需厂商接入，不能宣称现场实拍或真实设备联调通过。所有验收视频保留“测试视频·非现场”标识。

接口参考：[设备视频契约](../目标视频查询接口契约.md)、[批量办理契约](../backend-stage13/uav-event-batch-api-contract.md)。
