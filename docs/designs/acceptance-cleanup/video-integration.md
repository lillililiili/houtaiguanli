# 独立测试视频接入

链路：设备模拟器 FFmpeg → RTSP/TCP → MediaMTX 1.21.1 → 同源受权 HLS → 共用播放器。
只支持测试视频；现场摄像机未接入不显示已接入。运行不访问公网、CDN 或公共视频源。

## 开启

默认关闭。后端必须同时启用 local、qa，且不能含 prod / production，另设 `APP_VIDEO_QA_ENABLED=true`（或 `app.video.qa-enabled=true`）。既有数据库、通知配置保持不变。

媒体服务可使用 `docker compose -f deploy/compose.qa-video.yml --profile qa-video up -d`。
Windows 离线部署可预备官方 MediaMTX 1.21.1 可执行文件，用 `deploy/start-qa-video.ps1 -MediaMtxPath <可执行文件绝对路径>` 启动。
FFmpeg 使用已安装的本地程序或在模拟器设置绝对路径；二进制、录像和日志不提交到仓库。
启动前设置两组不同的24–128位base64url随机密码：`QA_VIDEO_PUBLISH_PASSWORD` 和 `QA_VIDEO_READ_PASSWORD`。配置文件默认拒绝所有匿名访问。
模拟器使用 qa-publisher 和发布密码（只保存在进程内存）；后端设置 `APP_VIDEO_MEDIA_USERNAME=qa-platform`、`APP_VIDEO_MEDIA_PASSWORD` 为读取密码。发布账户不能读取视频，读取账户不能推流。密码不得进入源码、日志或场景导出。
所有媒体端口仅监听本机：8554 RTSP、8888 HLS、9997 状态 API；匿名直接访问也被拒绝。平台视频清单与分片使用业务同源授权接口。

## 协议

- `PUT /api/v1/local-interface-simulator/video-streams/{taskId}`：`device_id` 必填，`target_id` 可选；后端按当前任务查目标并核对设备/目标/命令的 mock 或 replay 来源。
- 返回 `stream_id,stream_path,task_id,target_id,device_id,source_mode,expires_at`。推流只能使用返回的 `qa/<UUID>` 路径，不允许传任意网络地址。
- 登记60秒失效；模拟器每15秒重复登记续租，同一有效任务保持流标识，后端重启或租约失效则新流标识，模拟器关闭旧进程后重推。
- `DELETE` 同一路径注销，保留业务任务及历史回执；只有设备范围和目标范围校验通过的用户能操作。
- 现有目标视频 GET 保留字段，新增 `video_status,source_mode,stream_id,playback_url`。跟踪确认后 `status=TRACKING`；视频状态独立为 `NOT_CONFIGURED/WAITING/AVAILABLE/INTERRUPTED`。`AVAILABLE` 必须经媒体服务器核验，`playback_type=HLS`。
- 清单与分片均经 `/api/v1/targets/{targetId}/video/streams/{streamId}/{resource}`，逐次校验 Bearer、目标/设备范围、当前任务和流租约。只允许平台签发的单一 `session=<标准UUID>` 临时播放标识；它不代替平台会话鉴权。禁止其他参数、重复参数、重定向、绝对地址、路径穿越或非媒体文件，返回禁止缓存。
- MediaMTX真实会话本身可读取媒体，不能透传浏览器。后端将清单中该会话替换为独立随机UUID，在内存按流映射；5分钟未访问失效、最多512项，重启失效。将浏览器URL照搬到媒体端不能读取。初次主清单在后端使用固定 `cookieCheck=1` 选择无Cookie模式，浏览器不能指定该参数，所有HTTP重定向仍被拒绝。

原 Canvas 播放方式及平台内置 `LocalRiskVideoEoSimulator` 移除。独立模拟器接收 MQTT 跟踪指令并返回显式测试回执；推流故障与跟踪结果分开记录。

## 代码变更说明

以下后端路径均相对于 `server/src/main/java/com/uav/lowaltitude/`。

| 文件/类 | 方法/函数 | 方法作用与本次代码作用 |
| --- | --- | --- |
| modules/device/application/VideoStreamRegistry.java | enabled、requireEnabled、register、find、remove、observedReady | 测试环境门禁、60秒内存租约、每设备单流；登记校验和审计通过后才替换旧流，重启不恢复。 |
| modules/device/application/QaVideoStreamService.java | register、remove、authorize | 校验设备操作权、目标/设备范围、当前任务、指令及来源；记录登记/注销审计。 |
| modules/device/api/QaVideoStreamController.java | register、remove | 仅显式测试环境开放 PUT/DELETE 登记契约。 |
| modules/device/application/TargetVideoService.java | video、resource、tracking、validReceipt、sameSourceDomain、result | 独立跟踪/视频状态，校验有效回执，每媒体请求重新授权；兼容 mock 目标关联 replay 测试设备。 |
| modules/device/api/TargetVideoController.java | video、resource、TargetVideoDto | 扩展原读取契约，提供无缓存的授权媒体内容。 |
| modules/device/infrastructure/VideoMediaClient.java | ready、resource、read、origin、validatePath、validateResource、validateSession、proxyReference、proxyManifest、proxySession、upstreamSession | 只访问部署配置的媒体源，真实媒体凭据仅留后端，清单改写平台临时标识；禁止其他查询、重定向/路径逃逸，限制超时、响应大小与映射容量。 |
| integration/mock/LocalRiskVideoEoSimulator.java（删除） | 原内置回复循环移除 | 跟踪回执由外部模拟器通过正常 MQTT 链路返回。 |
| server/src/main/resources/application-local.yml | 无具体方法：profile 条件 | local 配置也显式排除 prod，避免混合 profile 绕过生产门禁。 |
| deploy/mediamtx-qa.yml、compose.qa-video.yml | 无具体方法：可选媒体配置 | 固定版本、本机端口、默认拒绝匿名，区分发布和读取权限。 |
| deploy/start-qa-video.ps1 | 启动流程及 finally 环境恢复 | 注入环境凭据启动本地媒体进程，不将凭据写入配置文件。 |
| server/src/test/java/com/uav/lowaltitude/modules/device/api/EoManualTrackApiTest.java | videoRequiresMatchingReceiptAndDoesNotManufactureCanvasVideo | 更新契约：跟踪回执不制造可播放画面。 |
| 同目录 QaVideoStreamApiTest、QaVideoStreamPostgresTest | registrationPlaybackAndRevocationStayBoundToTask、PostgreSQL配置 | 实际应用鉴权、任务关联、来源、注销与 PostgreSQL 审计；媒体返回在该单测中使用桩。 |
| 同目录 VideoStreamRegistryTest、VideoMediaClientTest | 门禁、租约、容量、审计失败、媒体地址用例 | 验证重启失效、禁止错误审计/旧流丢失，以及资源路径和重定向拒绝。 |

前端与模拟器逐文件清单分别见 [前端实施](frontend-implementation.md)、[模拟器实施](simulator-implementation.md)。实际验证结果及尚待接入的真实设备边界统一记录在 [实施记录](implementation-progress.md)，不以编译通过代替联调。
