# 验收清理实施记录

用户已确认 2026-09-28 实施方案，包含独立模拟器 FFmpeg / MediaMTX / HLS 推流。

## 约束与决策

- 短信、录音电话不改；历史不删除，测试来源不得冒充正式。
- 两仓库有大量既有未提交内容，在当前内容上创建 codex/acceptance-cleanup 分支增量实施，不另建丢失未提交状态的工作树，不提交用户改动。
- 原始文件哈希、差异保留在忽略的 server/target/acceptance-baseline（临时，不交付）。
- 使用子代理分工：非视频后台；两端页面；独立模拟器；主代理负责视频后台和部署、集成与复核。
- 任务共享边界：视频 DTO、模拟器登记接口、媒体路径由主代理固定，代理不修改其他任务文件。
- 验证级别 L3：来源隔离、回执、权限、媒体资源访问以及跨前后端集成。

## 任务

- [x] 后台正式来源限制、天气、预案、调测、研判、交接、报表、正式部署默认值。
- [x] 两端九处页面按设计落实，统一 HLS 播放。
- [x] 独立模拟器光电指令和视频生命周期、UI 与测试。
- [x] 视频登记、状态、授权媒体转发与测试部署。
- [x] 自动化、PostgreSQL、浏览器与实际推流验证；两前端重启、隔离后端启动与可达性验证。
- [x] 复核本轮差异、文档与交付说明。
- [ ] 常驻服务切换：现有8081未替换；8766/8767常驻模拟器未加载最后恢复补丁，自动审批拒绝重启。

## 预检

| 共享项 | 生产者 / 消费者 | 决策 |
| --- | --- | --- |
| 视频接口 | 后台 / 业务前台 | 保留现有字段，加 video_status/source_mode/stream_id/playback_url；HLS |
| 视频登记 | 模拟器 / 后台 | 仅 local+qa 或 test，排除 prod/production；按当前 task/device/target 和来源校验 |
| 正式环境 | 后台 / 管理端 | 服务端最终限制；前端不创建模拟配置，历史只读 |
| 数据和通知 | 各任务 | 不删除历史，不修改短信/语音逻辑与配置 |

## 验证结果

已通过：

- 后端最终19类123项测试全部通过，包含 PostgreSQL/PostGIS 全迁移、报表、视频鉴权与审计。容量/审计失败边界、分片范围、错流标识、媒体bootstrap与隐藏上游会话补测通过。隔离 Maven 最终打包成功。
- 管理前端 lint、129项测试与构建通过；业务前端5项视频契约、171文件扫描与构建通过。旧401迟到清新会话问题已修复并补两种回归场景；平台播放标识只接受单一标准UUID。
- 独立模拟器最终55项 Python、39项 Node通过（其中视频23项；其他并行工作新增的通知测试不归为本轮修改）。实际MQTT→FFmpeg→MediaMTX两组动态测试图/本地文件推流成功，H.264无声、重复幂等、旧任务拒绝、结束回收。
- 动态图与本地视频两组完整真实平台链路通过：登录18091→数据库任务→平台outbox→MQTT→独立模拟器回执/登记→FFmpeg/MediaMTX→平台授权清单及分片→实际浏览器解码。没有接口或媒体fixture；仅使用隔离HTML壳挂载产品真实共享组件，没有改现有业务路由代理。
- 两组实测主动中断编码后为INTERRUPTED且跟踪仍TRACKING；每15秒校验原OPEN任务后仅恢复视频，同一task/stream/command不变。浏览器清除旧帧，恢复后重新播放；切目标、退出/重新登录、真实End均正确清帧，页面脚本错误0。
- 平台匿名请求401；将浏览器临时播放标识照搬媒体源不能读取；任务结束旧资源404。媒体服务匿名初始访问和错误账户被拒绝，真实媒体session仅后端保存。历史失败探针保留并脱敏，不把修复前行为算作通过。
- 此前隔离API fixture的组件测试与三页面503错误态仍保留，明确不属于全业务成功数据联调。两组最终真实播放截图另行保存。
- 两前端5173/5175已重启，确认端口及HTTP200。后端使用回环18091和全新验收数据库继续联调，不干扰其他流程当前8081服务。禁种子启动后 target、eo_tracking_task、alarm、flight_risk、ops_device 均为0，证据保存在 target/acceptance-runtime/empty-start-counts.txt。
- MediaMTX原生服务启动成功；Compose配置校验通过，Docker镜像拉取因网络EOF未运行，不声称容器方式联调。
- 以 production profile 在回环18092实际启动，health200；即使qa-enabled=true，测试登记路由404，创建replay MQTT连接409/SIMULATION_DISABLED。该进程仅用于正式门禁，不声称其包含随后媒体兼容补丁。
- 对照实施前基线：本轮未修改Flyway迁移，未改SMS/Voice/Phone模块；相关配置仅正式来源与profile门禁变更。没有删除业务数据。

详细文件、方法与验证证据：

- [后台清理](backend-implementation.md)
- [两端页面](frontend-implementation.md)
- [视频接口与部署](video-integration.md)
- [独立模拟器](simulator-implementation.md)
- [模拟器界面修改位置](simulator-video-design.md)

最终两仓 `git diff --check` 均退出0。前端5173/5175、隔离后端18091和正式门禁验证18092均HTTP200；媒体残留推流0，8767为IDLE且视频默认关闭，没有自动恢复旧批次。最新后端运行包与本轮打包产物SHA256一致。Reachability证据见 [最终可达性](assets/final-reachability.json)。

实际链路证据：

- [动态图完整任务/回执/媒体验证](assets/eo-platform-smoke-dynamic-chart.json)
- [本地文件循环及恢复验证](assets/eo-platform-smoke-local-file-loop.json)
- [真实动态播放](assets/frontend-qa/real-platform-hls-playing.png)
- [真实断流清帧](assets/frontend-qa/real-local-video-interrupted.png)
- [恢复播放](assets/frontend-qa/real-local-video-recovered.png)

真实摄像机和真实移送渠道没有供应商接入资料，本轮不提高其验收状态。现有8081仍为其他流程正在使用的qa-r11.jar，视频类与本轮最终包不同；本次没有覆盖该进程或既有业务库，新版后端在18091隔离验收。测试视频服务默认关闭，常驻环境需按 [部署说明](video-integration.md) 显式配置后切换。

旧模拟器8766及本轮隔离8767的停止/重启均被工具自动审批拒绝，返回原因仅为“策略拒绝”；未绕过工具继续终止旧进程。最后恢复补丁在独立真实验收进程验证，不能称这两个常驻进程已加载。额外启动15173前端也被拒绝，已改用现有Vite模块与隔离HTML壳完成浏览器验证，没有重新尝试启动该进程。
