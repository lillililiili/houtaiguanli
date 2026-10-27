# 光电实际朝向与多目标调度

## 范围

本次按用户确认，同时调整业务前台、唯一后端和设备模拟器。仅本机 local,qa，dev-seed=false；未部署服务器、未启用演示种子、未改当前核实/反制授权规则、未直接写运行库业务记录。模拟视频继续使用已接入的可见光录制素材，明确标为测试视频、非现场；不声称视频背景与地图地理位置一致。

设备静态配置覆盖决定能否分配；设备上报的实际云台朝向与视场决定地图青色示意。没有有效姿态时显示未知，不使用目标方向或装饰扫描代替。转向请求、成功接令、捕获、媒体就绪分别判断。阅读设备、放大视频与选择目标均不下发转动指令。

一台光电一次观察一个目标，多台设备独立分配。队列持久化并按现有风险优先级调度，同级FIFO；高优先级只能自动替换普通AUTO，处置保护和人工观察不被普通自动请求打断。切换先等精确旧任务的成功停止回执，再重新校验并开始新任务。多选只表示待办理对象，核实与反制仍逐项预检、授权和回读结果。

## 联调发现并修复的通用问题

1. 新光电来源的一点RAW轨迹被当作最新业务轨迹，覆盖原有连续FUSED依据。改为同目标当前未结束FUSED优先，无当前FUSED保留既有兼容逻辑；不降低点数与质量门槛，不造轨迹点。
2. 同一设备同帧观察到两架明确不同机身身份的无人机，被误当作一个目标拆分。增加身份互斥校验，待定后获得不同身份也不得升级为拆分；保留真正拆分及无身份场景的原保护。
3. 真正合并/拆分后旧光电任务不能继续自称跟踪。旧任务进入停止流程，成功回执后才可按新依据创建新任务；旧识别关联不复活。
4. 批量核实打开期间，其他选中项可用性刷新可能清掉操作意图；预检失败也曾静默返回。现在保留草稿、显示原因，提交仍逐项检查当前关联、版本和权限。
5. 高频观测恰在请求开始后提交时，前台按请求开始时间限制 `last_seen` 会漏掉当前目标；当前日查询改用日边界，既有当前性判断保留。光电实况则先读取全部证据，再取共同验证时间，避免把读取过程中刚收到的姿态误报过期。
6. 告警列表取时间后还会读取配置，期间新观测提交会被旧时间误判为未来，导致风险暂时变UNKNOWN。真实PostgreSQL风险读取改用单条SQL的共同时间，测量、预测和当前事项使用同一时刻；保持原TTL、未来拒绝和来源门禁。固定应用时钟保持原语义。每条SQL各自一致，不承诺跨列表、计数及详情多次请求的原子快照。
7. 模拟器原来把一条最新观测复用于整批视频帧，双路媒体稍有延迟时触发后台严格的2秒关联校验。改为每帧选择精确目标、设备及任务下最近且时间差不超过2秒的观测，并以后台返回的实际LINKED结果显示关联状态；后端窗口没有放宽。
8. 双设备串行上传整批识别帧会使2秒位置上报延长到5～6秒。媒体元数据改用有界后台工作线程，每台最多一项在途、全局最多两项；主线程继续云台和观测。上传结果由主线程按对象、任务、视频会话及捕获状态重验后应用，旧任务/旧流/失捕的晚回包不能覆盖当前状态；关闭后不再提交，也不等待媒体线程排队。原始停止和注销的IO仍按既有闭环执行。
9. 视频帧含未关联识别框时，不能据此把其他已选目标断言为画面外。界面显示“画面关联待确认”；已明确关联的目标仍显示画面内，完整关联帧中未出现的目标才显示画面外。
10. 识别帧时间改用实际完整HLS分片的PDT、解码条码及逐帧PTS，限定已证明的分片区间，不再把编码进度套入全局时间起点。没有新PDT的断点、条码/PTS不一致或未完整编码的区间不生成识别关联。短暂LOST时仍记账后台已接收帧的递增游标，但不能恢复LINKED；旧任务或旧流结果丢弃。原先试验的全局锚点失效并自动重启媒体方案未通过连续验收，已从最终生产路径移除。
11. QA新增最多30帧的批量元数据入口，一次读取关联候选、逐帧重验权限、任务、身份和来源、当前注册流及时间守卫，分别返回接受/拒绝。避免30次HTTP往返拖慢元数据，接受但没有可靠关联仍为UNLINKED。原单帧接口以及后台的5秒、2秒和单调校验保持。

## 验证

本轮本机 `local,qa` 模拟验收已完成。中间截图、启动检查和测试输出位于 `artifacts/eo-direction-20261026/`。测试模拟数据不作为现场设备验收。

已运行的独立验证：后端整合151项测试及打包，后续光电读取时间修复57项测试及打包，告警查询时间修复38+52项（90项）通过并打包；模拟器前阶段90项EO测试和41项引擎、12项坐标、17项登记、7项范围回归；前端API契约178断言、数据适配315断言、7项朝向与数据源测试、4项批量窗口异步回归，构建与扫描通过。测试集合有重叠，不相加为总测试数。批量上传版本JAR与运行副本SHA256均为 `E1AAF92AE003CBCC3556DFE0B93885D1B2700C2BFAB67580984170A3E419ED06`，2026-10-26 15:02北京时间重新核对一致。

已完成的实际浏览器操作及只读记录核对：

- 单光电场景 `sim-1026011701-5865`：东侧006先获得观察，西侧007排队193.408秒；东侧实际离区，停止成功回执后2.375秒才发出西侧启动指令，朝向从约90°转270°。`one-camera-readback.json` 保留任务、队列、指令及回执；数据库没有历史入队优先级字段，文件明确标为未知，未补造数值。
- 双光电场景 `sim-1026012407-e6c9`：008、009分别绑定独立设备和任务，同期实际上报89.99647°与270.00231°。`two-camera-readback.json` 保存不同来源、成功回执及零合并/拆分结果；已离区East与仍违规West按事实区分。
- 停止模拟上报及收发后，页面目标归零、光电离线，视频显示不可用原因，不沿用旧AI画面与朝向，截图 `offline-video.png`。
- 最终人工场景010/011：实际框选两架、打开批量核实、分别填写依据并提交，两项均保存人工核实记录；闲置光电显示无识别框原始流，捕获后自动切AI，截图 `raw-idle-video.png`。
- 对010/011批量提交同一本机四通道模拟设备：两项均正确返回设备被早期005占用、未下发启动，而非假成功。正常UI对005急停并取得全关回执后，分别在原有效授权内重试010和011，两项SET_MASK15启动均取得实际模拟协议成功回执；之后逐项UI急停，全关SET_MASK0也均确认，补充的停止原因保留。`final-manual-readback.json` 保存2项人工核实、6条相关指令及6条实际协议回执、3次全关和审计。
- 005、010、011均已停止，不留本次模拟反制在执行中。没有用继电器静态零值替代后台停止回执。批量结果及实际执行截图为 `batch-counter-result.png`、`counter-executing.png`。
- 012/013补验：实际小地图框选两架、点击013行切换当前详情，两个勾选均保留；缩小后显示“已选2架”，再展开仍保留两架和当前查看013。选择没有切换设备正在跟踪的012，也没有自动发出核实或反制。
- 014/015补验：鼠标点击东侧014识别框、拖拽视频矩形选择014、切换光电一后鼠标点击西侧015识别框，均同步到清单；两台视频任务分别保持014/90°与015/270°。截图 `ai-two-camera-selection.png` 显示已选2架、015画面内及014画面外。连续采样仍发现媒体帧409/空档，故单次交互通过不等于媒体稳定性验收通过。
- 018/019补验：东侧018鼠标点选及视频拖框成功，西侧019点选后清单保留两架，019画面内、018画面外；缩小显示“已选2架”，再展开仍保留两架及当前查看019。截图 `final-ai-selection.png`。两段各约38.6秒采样分别40/40设备状态为TRACKING、LINKED，拒绝码及分片拒绝均为0，最大关联时间差分别1904ms和1179ms；文件 `metadata-segment-final.json`、`metadata-segment-extended.json`。这些采样证明采样时最新关联有效，不证明中间每一播放帧都有识别元数据；无当前可证明帧时，界面明确提示且不显示旧框。
- 018/019在14:56发生一次重新分配：后端接收后至融合处理的延迟最高10.173秒，研判读到短暂单来源状态，置信度0.65低于现有0.75门槛，两任务按依据不足停止；实际成功停止回执后约4.45秒才创建新观察。恢复后019先重新入队、018随后，两台空闲设备重新分配。没有同级抢占或合并/拆分，不把处理延迟称作设备真实离线；具体锁、负载或IO原因尚未确定。详见 `final-task-reallocation-readback.json`。

最终分段同步与批量上传通过103项EO测试；后端批量入口及谱系守卫通过59项测试（PostgreSQL接口49、批量5、注册表5），新包SHA256为 `E1AAF92AE003CBCC3556DFE0B93885D1B2700C2BFAB67580984170A3E419ED06`。最终前台构建成功，HLS时间映射相关5项测试通过；构建保留既有大体积分包提示。最终构建在真实浏览器页完成回读：实拍AI流、任务031与270°朝向显示正常；鼠标点选和视频矩形框选都将031加入清单，告警原因“进入禁飞/限制空域”保留。选择时没有下发跟踪、核实或反制；未核实目标的反制按钮保持禁用。无匹配当前帧时仍会短暂显示“当前播放帧没有可用识别框”，不以过期元数据补画；矩形框选已改为在有效框出现时记录拖拽起始帧，最多2秒内完成选择，视频元数据轮询缩短到1秒。结束后按界面停止全部收发并关闭视频推流；业务前台回读到模拟设备离线、视频不可用和选择所关联观测已变化，没有继续显示旧朝向/AI状态。上传隔离前后采样为 `metadata-before-async.json`、`metadata-after-async.json`；中间全局时钟方案失败采样为 `metadata-final.json`，不当成最终通过证据。第一次根代理重跑旧测试缺少FFmpeg的PATH失败，补上既有FFmpeg路径后通过，未修改测试期望掩盖环境失败。

本次用户原有 `AlarmsPage.vue` 修改及两仓库原有未跟踪目录保持。原模拟器草稿已导出备份。模拟业务历史保留，不通过删除记录掩盖失败轮次。

## 代码变更说明

| 文件/类（相对各仓库） | 方法/函数或声明 | 作用与本次变化 |
| --- | --- | --- |
| 后端 `EoSituationController`、`EoSituationService` | `read/item/validate`，`GET /api/v1/devices/eo-situation` | 分页、鉴权只读实际姿态；缺失、过期、未校准和任务不匹配时明确未知。 |
| 后端 `EoCoverage` | `contains` | 按WGS84圆形/扇形配置判断覆盖；拒绝非法位置。 |
| 后端 `EoDispatchService`、`EoEdgeCommandService` | `view/request/poll/devices/idle`及BeginTracking下发校验 | 分配和实际下发均检查覆盖，区分无覆盖与占用；自动切换只针对普通AUTO。 |
| 后端 `EoEdgeIngressService`、`EoTrackingReportMapper` | 心跳/跟踪报告接入与映射 | 姿态绑定平台接收事实和精确任务；TURNING/LOST/UNKNOWN不能续期旧捕获。 |
| 后端 `EoEdgeRepository`、`EoTrackingRepository` | `cameraSnapshot/coverage/taskTargetChanged`及跟踪事实读取 | 提供只读实况、范围、谱系变化依据；新任务不复活旧回报。 |
| 后端 `EoTrackingPolicy`、`EoTrackingScheduler`、`EoTrackingStatusService`、`EoTrackingContinuity` | `trackingBlock/continuationBlock/poll/blockMessage/block` | 真正目标身份变化时停止旧任务并等待回执；失败回报使旧跟踪证据失效。 |
| 后端 `RuleEngineRepository` | `latestTrackId` | 合法性研判优先当前融合轨迹，避免新增原始来源打断连续性。 |
| 后端 `UavRiskReadClock`、`AlarmReadRepository`、`UavRiskCaseIdentity`、`AppClock` | `align/readSql/detailRows/currentMeasuredArea/usesSystemClock` | 风险查询的时效判断使用同条SQL时间；显式固定时钟不改用数据库时间。 |
| 后端 `FusionPipeline` | `sameSourceOrigin/autoSplit` | 不同明确机身身份不认定为同目标拆分，保持来源域隔离。 |
| 业务前台 `situation/eoCameraState.js`、`deviceApi.js`、`situationApiSource.js` | `eoCameraState/attachEoCameraStates/eoCameraText/eoSituation`及刷新生命周期 | 每2秒独立读取姿态，校验设备/来源/时效；失败清空朝向，暂停和卸载释放定时器。 |
| 业务前台 `SituationPage.vue`、`situationData.js` | 地图初始化、设备详情、错误提示、覆盖描述 | 分清配置覆盖与当前视场，展示实际方向与未知原因。 |
| 业务前台 `public/assets/js/map.js` | `_drawEoView/_drawDeviceCoverage`及图标引线 | 去掉光电装饰性来回扫动，以实际角度绘制视场；引线连接真实安装点。 |
| 业务前台 `AuthenticatedHlsVideo.vue`、`hlsFrameTimestamp.js`、`DeviceVideoWorkspace.vue`、`EoVideoDetections.vue`、`RectangleSelection.vue` | `reportFrame/hlsFrameTimestamp/matchingVideoFrame/refresh/beginSelection/select/down` | 识别时间按当前HLS分片PDT映射；视频元数据每秒回读，矩形框选从有效帧起始位置捕获目标，超过2秒提示重试，不使用过期框。 |
| 业务前台 `DeviceVideoWorkspace.vue`、`EoDispatchControls.vue` | 视频工作区状态、`unresolvedFrame`、选中行、`summaryOnly/brief` | 视频旁保留操作，逐项目标显示观察或排队状态；地图与视频选择继续共用清单，未关联识别不误报目标在画面外。 |
| 业务前台 `EoBatchActions.vue` | `watch/open`及加载/错误提示 | 其他目标刷新不抹去核实草稿；无资格或条件变化明确提示。 |
| 模拟器 `eo_pointing.py`、`eo_video.py` | `SimulatedGimbal`、任务回执、捕获/跟踪与心跳、元数据调度及结果应用 | 依据实测目标有限速转向，捕获后才能TRACKING；媒体上传与观测隔离、有界并发，旧任务及旧流结果丢弃；丢失、停止及未知按真实模拟状态回报。 |
| 模拟器 `eo_observations.py`、`eo_worker.py` | 目标观测、心跳缓存 | 观测来源与任务精确绑定；缓存过期撤销角度校准，不能通过新心跳续期旧姿态。 |
| 模拟器 `eo_media_clock.py` | 完整分片读取、解码与已证明帧区间查询 | 以实际分片PDT、逐帧PTS和条码验证时钟，断点无依据时拒绝推导，不按编码进度外推UTC。 |
| 后端 `QaDeviceVideoController`、`QaDeviceVideoService`、`VideoDetectionAssociationService` | `/frames/batch`、`batch/buildFrame/resolve` | 最多30帧、候选一次读取，逐帧沿用当前授权和关联守卫，分别返回真实接受/拒绝结果。 |
| 模拟器 `engine.py`、`platform_client.py` | 场景编译、设备登记清单 | 保留安装位置/高度/覆盖；缺省高度基准不能冒充明确校准。 |
| 模拟器 `scenarios/eo-fusion/*.json`、`README.md` | 无具体方法：新增相反方向、单设备排队及双设备分配输入 | 通过正常模拟上报和配套资料生成风险，未注入最终告警、核实或执行结果。 |
| 后端/模拟器测试、现有前台契约测试及仓外专项测试 | 参数化及异步回归 | 覆盖不同ID/位置/来源、姿态失效、同级/高优先级、身份变更与停止回执、批量打开竞态。 |

后端详细字段见 `server/docs/eo-situation-contract.md`。真实设备仍需厂家提供经过校准的姿态、任务回执、两路媒体及时间同步的识别元数据；本次不能替代现场硬件验收。

### 测试文件清单

| 文件/类 | 测试方法或范围 | 本次验证作用 |
| --- | --- | --- |
| `EoPriorityDispatchTest.java` | `priorityThenDurableFifoAssignsTwoDifferentCamerasAndLeavesThirdWaiting`、同级/人工不抢占、无覆盖拒绝、高优先级先停后换 | 多设备独立分配及优先级、停止回执保护。 |
| `EoSituationServiceTest.java` | 姿态校验和证据读取时序 | 过期/未来/缺失/非法角度不能冒充实际方向。 |
| `EoCoverageTest.java`、`EoTrackingContinuityTest.java` | 圆形/扇形/异常坐标及任务连续性 | 覆盖计算和失败报告失效规则。 |
| `EoEdgeMqttTest.java`、`EoManualTrackApiTest.java`、`EoTrackingPolicyTest.java`、`EoTrackingReportMapperTest.java` | 报告、心跳、任务身份、捕获、停止和人工流程 | 实际接入链路及旧任务保护。 |
| `ConfirmedLegalityPostgresTest.java` | 当前融合轨迹优先 | 新来源的一点轨迹不打断有效研判。 |
| `FusionIdentityClassAndClockTest.java`、`FusionSplitOriginTest.java`、`FusionIndependentIdentityPostgresTest.java` | 明确不同机身身份及无身份/同身份邻近场景 | 防止不同无人机误拆分，同时保留真正谱系保护。 |
| `UavRiskCaseReadPostgresTest.java`、`AutomaticCounterFlowPostgresTest.java` | 实时SQL时间、TTL、未来样本、固定测试时钟 | 新观测不因旧请求时刻而丢失；实际自动办理规则不变。 |
| `situationApiContract.test.cjs`、`situationData.test.cjs` | 源段刷新、日期窗口、覆盖展示 | 数据读取与适配契约回归。 |
| `test_eo_pointing.py` | `SimulatedGimbal`有限速转向、独立设备、失联和覆盖 | 实测位置引导和实际捕获模拟。 |
| `test_eo_dual_video.py`、`test_eo_observations.py`、`test_eo_video.py`、`test_eo_worker.py` | 双流、源关联、捕获前置条件、缓存过期 | 视频和识别关联不由接令或旧心跳伪造。 |
| `test_eo_media_clock.py` | 实际分片区间、逐帧条码/PTS、断点及缺失PDT | 只对已证明媒体帧建立时间关系，拒绝无依据外推。 |
| `QaVideoStreamPostgresTest`、`QaVideoFrameBatchTest`及注册表回归 | HTTP契约、部分失败、权限/身份变化、5秒及单调边界 | 批量接入不放宽原单帧与来源守卫。 |
| 仓外 `eo-camera.test.mjs`、`eo-source.test.mjs`、`eo-batch-opening.test.mjs` | 5项姿态、2项刷新、4项批量异步场景 | 前端显示和操作竞态回归，未往业务项目增加临时文件。 |
