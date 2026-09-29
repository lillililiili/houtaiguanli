# 验收清理前端实施与验证

日期：2026-09-28。实施依据为本目录 README.md、index.html 与 design.js 的九处设计。原有未提交改动保留；没有提交、切换分支或修改数据库。页面继续使用现有主题、布局与地图；设计稿标注没有复制到产品。

## 已实施行为

- 接口配置仅提供真实天气接入。保存真实配置不生成预报，也不依据保存动作推定已接入；旧模拟配置继续显示历史测试来源。
- MQTT 正式配置只创建 live 通道，保留默认停用。历史回放通道保留来源与查看，可以停用，不允许重新启用或修改为正式来源。
- 空域监测删除页面本地场景生成、模拟模式切换和逐帧播放；只读接口目标，读取失败不补模拟数据。服务端历史记录来源、真实风险、轨迹回放与原地图几何保留。
- 移除 Canvas 模拟视频。目标视频与风险光电区共用 TargetLiveVideo → AuthenticatedHlsVideo；跟踪操作仍读取原任务与回执，不由播放触发跟踪。
- 播放资格要求目标相符、status=TRACKING、video_status=AVAILABLE、playback_type=HLS，以及完整 task/device/stream/playback_url。只有媒体 playing 事件显示播放中。未接入、等待与断流分别展示；测试视频持续标识。
- HLS 清单、分片使用当前 sessionStorage 会话的 Authorization Bearer；无 token URL 或 localStorage 凭据。禁止跨源、越目标、越流和 HTTP 重定向；查询仅放行 平台签发的单个 session=标准 UUID 临时媒体标识（非登录凭据；真实上游会话仅存后端），拒绝其他、重复或编码查询。任务、目标、命令、流或会话变化及卸载均销毁播放器；网络错误清空 src 并隐藏旧帧，手工刷新重建播放器。
- 调测创建和继续操作只对正式来源设备开放；历史模拟通过报告使用警示标签，不计成正式步骤完成，不作为维护关联的通过结果。结果、日志及恢复核验仍读取后端事实。
- 处置预案只能创建 live；历史模拟/回放版本只读保留。不能编辑、发布、复制成新版本或重新关联为正式预案；已有版本与关联历史保持。
- 合法性历史结果与统计口径保持原样，未确认参数增加“正式判定不可用”副标签和原因；不能因参数列表非空就显示已确认。原始结论、依据和复核历史保留，既有告警/合法性复核分工不变。新结果是否不可判定由后端决定。
- 移送送达与签收分别展示，测试送达/签收使用历史测试标签，测试通知入口只读；查询失败不提示成功。无案件结果不推定已罚款。
- 报表预览、明细及导出共同传 source_mode=live。混合/模拟响应拒绝展示与导出，空数据与请求错误分开。正式统计的数据库过滤由后端配套实现。

## 代码变更说明

下表仅列本次代理改动；同文件中既有用户变动不算入本次工作。

### 管理前端（E:/houtaiguanlii/ruoyi-ui）

| 文件/组件 | 方法/函数 | 方法作用与本次代码作用 |
| --- | --- | --- |
| src/views/operations/InterfacesView.vue | fill、save、模板 | 将编辑来源固定为 live；去除模拟天气选项及自动模拟启用推导；如实显示保存和接入状态。 |
| src/views/operations/DevicesView.vue | openBrokerEditor、saveBroker、toggleBroker、模板 | 正式创建/编辑与启用校验；历史测试通道保持来源且不能重新启用。 |
| src/views/operations/CommissionView.vue | isTestSource、isSimulation、formalDevice、statusText、tagType、stepIndex、createTask、connectTask、saveConfig、startTask | 区分正式设备和历史测试任务，禁止测试来源创建/继续正式调测、黄色显示历史通过，步骤不冒充正式完成。 |
| src/views/system/ResponsePlansView.vue | blank、edit、save、change、bind、模板 | live 默认与提交校验，历史测试预案只读，正式版本和历史关联仍可查询。 |
| src/views/operations/ReportsView.vue | params、loadPreview、模板 | 同一正式来源参数传到预览/明细/导出，拒绝混合来源响应。 |
| tests/weatherInterfaces.test.js | 天气组件挂载用例 | 验证只提供 live、旧模拟状态不冒充接通、保存载荷只能 live。 |
| tests/businessReports.test.js | 报表组件用例 | 验证正式过滤参数、混合响应拒绝、错误不当成零和禁用导出。 |

### 业务前端（E:/rwurenji/_rong/dongying-vue）

| 文件/组件 | 方法/函数 | 方法作用与本次代码作用 |
| --- | --- | --- |
| package.json、package-lock.json | 无具体方法：依赖块 | 按用户授权新增本地打包 hls.js 1.7.3，不依赖 CDN。 |
| src/components/video/AuthenticatedHlsVideo.vue（新增） | start、destroy、interrupt、waitForData、playing、watch、onBeforeUnmount | 真正的 HTML video/HLS 播放，实际媒体事件状态，错误停止旧画面和生命周期清理。 |
| src/components/video/authenticatedHlsLoader.js（新增） | streamUrl、authenticatedHlsLoader、load、request、abort、destroy | 每清单/分片附加当前 Bearer，限制同源同目标同流，拒绝重定向，超时/取消/401 处理；响应返回后先检查已销毁、超时与会话变化，防止旧 401 清除新会话。 |
| src/components/video/TargetLiveVideo.vue | clear、refresh、reloadVideo、watch、模板 | 消费独立 tracking/video 状态；轮询保持来源，手动刷新清理失败播放器，使用共享 HLS。 |
| src/components/video/targetVideoState.js | targetVideoState | 不完整、错目标、非 TRACKING、未 AVAILABLE 不播放；测试源标签独立。 |
| src/components/video/SimulatedOpticalVideo.vue（删除） | 原 Canvas 实现删除 | 消除浏览器自行绘制视频画面；已确认 src 无剩余引用。 |
| src/pages/flights/components/RiskOpticalPanel.vue | refresh、begin、end、watch、模板 | 删除 prepare/begin AirspaceDemo 写入，保留原光电追踪，读取失败清理旧任务画面，复用 TargetLiveVideo。 |
| src/services/deviceApi.js | 删除 prepareAirspaceDemoTarget、beginAirspaceDemoTrack | 不再提供页面自产演示目标/追踪写入口。 |
| src/pages/airspace/useAirspaceMonitor.js | reload、filtered、watch、定时器 | 去除 DEV 默认模拟、buildAirspaceDemo 和逐帧调度，只读真实接口。 |
| src/pages/airspace/AirspaceRiskList.vue | appliedFilters、clearFilter、模板 | 删除模拟模式、场景、帧播放器，保留风险筛选、空态及错误。 |
| src/pages/airspace/useAirspaceRiskList.js | rows、watch | 移除已失效的模拟模式/场景筛选依赖，历史来源仍保留。 |
| src/pages/airspace/AirspacePage.vue | locateTarget、paintMap、installOverlay、模板 | 删除自产场景覆盖绘制与专用图例；保留接口风险/目标、坐标和底图。 |
| src/pages/airspace/AirspaceMonitorPanel.vue、airspaceMonitorDemo.js（删除） | 原闲置演示面板/数据生成器删除 | 两文件无其他业务消费者，删除不再使用的本地演示入口。 |
| src/pages/LegalityPage.vue | unconfirmedParams、parameterNote、evaluationReason、conclusionQualificationText、模板 | 历史结论不改写，参数未确认时明确正式依据不可用，逐项确认参数状态。 |
| src/pages/punish/handoffStatus.js | deliveryView、receiptView | 测试来源成功态标记为历史测试事实，送达与签收仍独立。 |
| src/pages/punish/PunishmentNotification.vue | notifyDepartment、模板 | 测试通知入口只读，回读失败不 toast 成功，历史仍可查询。 |
| src/pages/punish/PunishmentOutcome.vue | 无具体方法：模板 | 空案件不推定移送或罚款完成，历史测试案件醒目标明。 |
| tools/targetVideoContract.test.cjs | 五个合约用例 | 验证可播放资格、陈旧响应清理、每片鉴权/地址约束/会话变化/401；补充已销毁与换会话后的迟到 401 不触发全局退出回归。 |

## 已实际执行的验证

- 管理端 npm run lint：通过。
- 管理端 npm test：28 个测试文件、129 项通过。最终 Commission 来源辅助函数调整后，针对 operationReference、maintenanceCommission、weatherInterfaces、businessReports 重跑：4 文件、15 项通过。
- 管理端 npm run build：通过；最终收口构建也已通过。
- 业务端 node --test tools/targetVideoContract.test.cjs：最终 5 项通过；node --check tools/targetVideoContract.test.cjs：通过。
- 业务端 node tools/scan.cjs：最终 172 文件全部规则通过。
- 业务端 npm run build：通过；有既有体积告警，未把告警说成构建失败。
- 两仓 git diff --check：无空白错误（只输出已有换行转换提示）。
- 真实 Edge 无头浏览器，1280×800，独立上下文/测试请求拦截：清单 503 后 INTERRUPTED、src 被清空、video 隐藏；元数据不冒充播放；跨源 URL 不发出请求；卸载清理；无 pageerror。
- 真实 Edge + 实际 FFmpeg/RTSP 采集 H.264 TS（运行媒体现已移至 E:/houtaiguanlii/server/target/acceptance-runtime/evidence/eo-hls-fixture）：index.m3u8 与 4 个 .ts 均携带测试 Bearer，实际 HTMLMediaElement 播放事件后 PLAYING，无 Canvas，页面显示测试来源，ENDED 后移除播放器，卸载无 pageerror。媒体来自独立模拟器测试，API/会话关联用隔离 fixture，不宣称平台数据库完整集成通过。
- 真实业务页面路由 /#/airspace、/#/legality、/#/punish：拦截 API 为 503 验证错误态，空域切到空域监测后无演示/逐帧/数据模式控件；页面错误明确、无 pageerror、没有业务写请求。空域存在底图+overlay 两个 canvas；其他路由截图未显示遗留地图。该项为隔离错误态验证，不代表真实登录/API 成功数据联调。
- 仓库 e2e/support/session.js 文档中的本地 admin1 测试账号返回 INVALID_CREDENTIALS，未继续尝试其他凭据；真实业务登录数据链待主任务用可用验收账号验证。默认 Playwright v1234 未安装，使用本机 Edge 完成上述浏览器验证，没有声称默认 E2E 全套通过。
- 已独立复核视频后端：每分片会话/范围/任务/回执、lease、清单路径和重定向限制；发现 mock 目标与 replay 设备兼容问题并报主任务，主任务已修复并补充测试。未代写后端。
- 最后复核复现旧 HLS 请求销毁/换会话后迟到 401 仍触发全局退出的问题，已修复并补充回归；当前会话的 401 仍正常触发退出。修改后重跑 4 项视频合约、语法检查与业务构建通过。

## 最终服务重启与可达性

- 重启前分别核对端口监听进程、Vite 命令行与编译组件的绝对 __file：5173 为 E:/rwurenji/_rong/dongying-vue，5175 为 E:/houtaiguanlii/ruoyi-ui。仅停止对应已核验的 Vite 进程，以隐藏窗口从相同工作目录和 host/port/strictPort 参数重启，没有修改后端代理配置。
- 管理端最终进程 PID 9700，127.0.0.1:5175 监听，http://127.0.0.1:5175/ 返回 HTTP 200。
- 迟到 401 修复构建后再次重启业务端，最新 PID 34468，::1:5173 监听，http://localhost:5173/ 返回 HTTP 200；启动 stderr 为空。
- 两端首次重启日志：C:/Users/ADMIN~1/AppData/Local/Temp/acceptance-vite-restart-20260928-211529（5173/5175 的 out.log、err.log 与 reachability.json）。业务端最终重启日志及两端 HTTP 200 结果：C:/Users/ADMIN~1/AppData/Local/Temp/acceptance-vite-final-20260928-211958（5173.out.log、5173.err.log、reachability.json）。日志留在本机临时目录，不提交仓库。

## 真实链路补充验证

- MediaMTX 实际主清单需要上游媒体会话；后端将其映射为按流绑定的随机临时 UUID，真实上游会话不发浏览器。前端已严格放行单个标准 UUID 平台媒体参数，仍每片 Bearer，并以第 5 项合约覆盖重复/其他/编码查询、fragment、跨源和越目标拒绝。重跑视频合约 5/5 与业务构建通过。
- 新端口 Vite 启动命令被自动审核拒绝，未重复执行。改为 18091 后端源的临时 HTML 页面壳，仅该 HTML 由测试工具提供；从既有 5173 加载真实组件，API 与媒体直连真实 18091。独立 Edge 上下文授予本机网络访问权限用于模块资产加载，真实登录、组件挂载与下述实际播放均已通过。
- 8767 模拟器新进程已用隔离库测试账号登录，只展开系统连接和运行记录，没有保存、启用视频或启动模拟。设置与运行记录截图见下文。原媒体运行数据移出源码目录至 server/target，截图及脱敏 JSON 保留。

- 隔离真实链路（动态测试图）已实际通过：真实数据库登录及 auth/me → 真实 TRACKING/AVAILABLE 元数据 → 真实 HLS 主/子清单、初始化和媒体分片 → HTMLMediaElement 解码 960×540、currentTime=4.07、paused=false、playing 事件。共记录 18 次媒体请求，成功媒体请求均带 Bearer；匿名读取同资源返回 401。
- 浏览器在真实流播放期间切空目标，旧 video 立即移除；恢复目标重新播放；播放期间 logout 立即移除 video，再次真实登录可恢复播放；随后模拟器真实 End 指令结束任务，API 读回 ENDED，组件移除播放器；最终退出/卸载无 pageerror。无 API/媒体请求拦截；仅测试 HTML 壳被提供。成功证据见 real-platform-browser.json 与四张 real-platform 截图，不将测试壳说成完整业务页操作验收。
- 该链路结束后 5173 再次重启并核验，PID 34468；5175 PID 9700 同时 HTTP 200。日志：C:/Users/ADMIN~1/AppData/Local/Temp/acceptance-vite-verified-20260928-214146，stderr 为空。

- 隔离真实链路（本地视频文件）第二组也已通过：实际本地 H.264+AAC 输入由模拟器循环推流，平台播放器解码 960×540，记录 25 次媒体请求。实际终止当前 FFmpeg 后，UI 显示“等待测试视频推流，或视频流已中断”且无 video；15 秒受控恢复后，同一跟踪任务重新实际播放。再验证匿名媒体 401、切目标清帧/恢复、播放中退出登录清帧/新会话播放以及真实 End 后 ENDED/无 video，pageerror 为 0。媒体无音轨由模拟器 ffprobe 实测，详见其独立报告；本报告不将浏览器静音等同去除音轨。
- 两组均只用真实后端登录、数据库任务、设备回执和媒体，无 API 或媒体 fixture；临时 HTML 壳负责挂载实际 TargetLiveVideo/AuthenticatedHlsVideo，未声明完整业务导航全链自动化通过。动态组和本地组分别保留文件；本地组中断的 503、切换时主动中止请求及任务结束后的资源失效是预期事件，未将它们当作成功播放请求。

## 浏览器证据

既有业务截图为 1280×800，模拟器截图为 1440×1050；错误态截图用显式 503 测试夹具，不是线上故障截图。

- [真实 HLS 片段组件播放](assets/frontend-qa/acceptance-hls-playing.png)
- [空域监测读取失败](assets/frontend-qa/acceptance-airspace-error.png)
- [合法性读取失败](assets/frontend-qa/acceptance-legality-error.png)
- [移送与处罚读取失败](assets/frontend-qa/acceptance-punish-error.png)
- [模拟器光电视频设置（1440×1050）](assets/frontend-qa/simulator-video-settings.png)
- [模拟器运行记录（1440×1050）](assets/frontend-qa/simulator-video-records.png)
- [真实平台 HLS 播放（1280×900）](assets/frontend-qa/real-platform-hls-playing.png)
- [真实播放切换目标清帧](assets/frontend-qa/real-platform-target-cleared.png)
- [真实播放退出登录清帧](assets/frontend-qa/real-platform-session-cleared.png)
- [真实任务结束清帧](assets/frontend-qa/real-platform-task-ended.png)
- [真实平台浏览器脱敏请求证据](assets/frontend-qa/real-platform-browser.json)
- [本地视频真实平台播放](assets/frontend-qa/real-local-video-hls-playing.png)
- [本地视频实际断流清帧](assets/frontend-qa/real-local-video-interrupted.png)
- [本地视频恢复后真实播放](assets/frontend-qa/real-local-video-recovered.png)
- [本地视频真实任务结束](assets/frontend-qa/real-local-video-task-ended.png)
- [本地视频浏览器脱敏请求证据](assets/frontend-qa/real-local-video-browser.json)

未更改短信、录音电话、已有审核历史、数据库/Flyway、底图资源或独立数据模拟器。本报告对应前端代码和上述验证，不代替真实设备验收、正式渠道接通或生产发布验收。
