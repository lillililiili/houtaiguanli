# 独立信号模拟器光电与测试视频实现记录

日期：2026-09-28。仓库 `E:/rwurenji/_rong`，修改目录 `tools/device-simulator`。本报告为子模块实施与验证记录，真实媒体服务和业务播放器联调由总验收记录汇总。

## 当前结果

独立 Python 模拟器现在订阅平台绑定的光电指令主题，校验协议 C 的 event、edgeId、metadata.deviceId/taskId、指令时效及当前开放任务。BeginTracking / EndTracking / CameraStatus 成功仅回协议已知 codeStatus=200；不认识的事件、错误身份、陈旧任务不造失败码，留平台超时。回执不补造 aiStatus 或现场观测。

测试视频默认关闭；显式开启后用 FFmpeg 循环本地文件或生成动态测试图，无声 H.264，烧录 TEST VIDEO / DEVICE / TASK。向后端任务登记接口申请随机流路径，每15秒幂等刷新，服务端 stream_id 改变时停止旧编码再启动新流。编码失败保留当前任务登记并限频15秒重试，重试前必须再确认同一OPEN任务；平台能够显示断流并在同任务恢复。编码状态与跟踪状态分开，不伪造媒体就绪或跟踪失败回码。

单设备单任务；重复不新建；停止、暂停、离线、MQTT 断开、会话失效及系统任务结束清理自有进程与登记。重启默认关闭、不恢复旧任务。Windows 用独立 Job 的 KILL_ON_JOB_CLOSE 防止父进程退出后遗留自有 FFmpeg。

## 代码变更说明

| 文件/类 | 方法/函数 | 方法作用与本次修改 |
| --- | --- | --- |
| `eo_video.py` / `EoSimulator` | `enqueue`, `drain`, `handle`, `receipt` | 有界 MQTT 输入队列，拒绝保留消息、过大包、重复 JSON 字段、错误身份和旧任务；发匹配设备任务的真实协议模拟回执。 |
| `eo_video.py` / `EoSimulator` | `_refresh`, `tick`, `_kill`, `_deregister`, `_stop`, `suspend`, `resume`, `availability`, `heartbeat`, `snapshot` | 管理任务登记租约、单一 FFmpeg、状态检查、编码退出、暂停停止、设备离线及心跳跟踪状态。视频失败保留租约，每15秒复核当前任务后只重试视频进程；停止或闭任务后禁止恢复。 |
| `eo_video.py` | `video_config`, `public_video_config`, `sanitize_video_error`, `ffmpeg_arguments`, `OwnedProcessJob.__init__/close` | 验证显式启用、回环RTSP、本机文件与FFmpeg；内存凭据与错误脱敏；构造无 shell 的参数和本机水印字体；只清理所属进程。 |
| `platform_client.py` / `Platform` | `prepare_devices` | 注册光电后读取 protocol-status.details 的真实绑定主题，校验身份并放入批次映射。 |
| `server.py` / `Runtime` | `__init__`, `status`, `connect`, `run` | 保存仅内存视频配置；暴露独立状态；连接 MQTT 并等待订阅确认；工作线程处理指令和生命周期，停止时释放视频。 |
| `web/runtime.js` | `videoSettings`, `eoStatus`, `connectionForm`, `renderLog`, `showRuntime`, submit listener | 在现有浅色连接/运行弹窗中设置测试源和显示跟踪/视频各自事实，所有动态内容使用 esc。 |
| `tests/test_eo_video.py` | `EoVideoTests`, `ConfigTests` | 23项新测试，验证回执匹配、重复/陈旧/错误指令、来源配置、编码失败与限频恢复、退出、租约更换、停止、离线、注册中暂停与凭据脱敏。 |
| `tests/run_eo_media_smoke.py` | `run`, `fetch`, `ContractDouble.call` | 显式 opt-in 真MQTT、FFmpeg、RTSP/HLS两源联调，使用平台契约替身并明确标记边界。 |
| `tests/run_eo_platform_smoke.py` | `run` | 显式 opt-in 18091隔离平台全链路测试：真实API登录、任务、outbox指令、回执、媒体代理与结束；固定隔离目标/绑定，不直写数据库。 |
| `README.md` | 无具体方法 | 更新光电能力边界、操作、依赖、恢复与模拟验收范围。 |

未修改 `notification_inbox.py`、`notification_response.py`，未修改任何短信、语音或通知业务逻辑；现有 seed 保持不变，新视频流程不直写数据库。

## 已执行验证

- 在模拟器目录 `python -m unittest discover -s tests`：最终55项通过（原有26 + 视频23 + 并行通知修复6）。既有外部会话测试产生一个 HTTPError ResourceWarning，不影响通过。
- `node --test tests/test_external_ui.cjs tests/test_external_session.cjs tests/test_plan_form.cjs tests/test_airspace_drawing.cjs tests/test_airspace_ui.cjs`：39项通过。
- `node --check web/runtime.js`、`node --check web/app.js`：通过。
- `git diff --check`：通过；仅既有 LF/CRLF 换行提示。
- 实际使用已有 WinGet FFmpeg 8.1.2：初测发现默认 fontconfig 缺配置，退出3221225477；修复为显式 Arial 文件后实际水印帧编码 exit0、stderr空。已查看 [960×540 水印帧](assets/eo-test-watermark.png)，三项标识完整可读。
- Windows Job：真实 Python 子进程绑定独立 Job 后关闭句柄，5秒内退出，未调用按名称批量杀进程。

## 集成契约

- 平台 `GET /devices/{platformId}/protocol-status` → `details.dispatcher_topic/reporting_topic/edge_id/external_device_id/open_task`。
- 平台 `PUT /local-interface-simulator/video-streams/{taskId}`，body `{device_id}`；由平台解析目标，返回 `task_id/device_id/target_id/stream_id/stream_path`。
- 平台 `DELETE /local-interface-simulator/video-streams/{taskId}`；注销失败记日志，服务端租约到期兜底。
- 媒体推流地址：配置的回环RTSP根地址 + 服务端随机 `qa/<UUID>`。不从MQTT指令接受媒体URL。
- 登记需要当前 OPEN 任务；退出清理后相同 EndTracking 可幂等确认，同一旧 BeginTracking 不能复活。

## 阶段记录：首次编码验证

媒体服务尚未就绪时先完成编码与进程验证。以下保留联调阶段记录；当前最终结果见文末真实平台复验，界面证据见 [模拟器视频设计](simulator-video-design.md)。

## 真实媒体联调补充（2026-09-28）

已确认 MediaMTX v1.21.1 的 RTSP 8554、HLS 8888、API 9997 都只监听 127.0.0.1。新增显式 opt-in 脚本 `tests/run_eo_media_smoke.py`；命令：

```powershell
python tests/run_eo_media_smoke.py --run-local-media-smoke --output E:/houtaiguanlii/docs/designs/acceptance-cleanup/assets --credentials-file E:/houtaiguanlii/server/target/qa-media/credentials.json
```

两组均通过真实 MQTT broker（1883）收发 BeginTracking、重复 BeginTracking 和 EndTracking，再由实际 FFmpeg 推 RTSP、MediaMTX 转 HLS、ffprobe 读取 HLS：

- 动态测试图：HLS H.264 960×540/15fps，仅 video；实际 RTSP 采集4秒 HLS样本供前端组件fixture验证。
- 本机文件循环：临时生成2秒含音轨MP4作为本地输入；运行超过源片时长仍推流，HLS输出只有 H.264 video，没有 audio。
- 两组重复 Begin 保持原 FFmpeg PID，31秒前旧任务指令不能抢占。End回执匹配原任务、workState=0，进程退出、媒体 ready 消失、注销调用完成。
- 联调结束后 MediaMTX 路径数量恢复0；未遗留这两组测试流。

证据：[eo-media-smoke.json](assets/eo-media-smoke.json)，[本地HLS样本（忽略的运行产物）](../../../server/target/acceptance-runtime/evidence/eo-hls-fixture/index.m3u8)。本次平台API为内存契约替身，**不是平台数据库、鉴权、任务入库的完整端到端验收**。MQTT、FFmpeg、RTSP及HLS链路均为真实进程/网络传输。

生命周期自查：共享会话 `ExternalBridge.invalidate()` 会设置 runtime.cancel；运行循环取消或 MQTT 断开进入 finally，调用 `eo.suspend()` 清理。切换账号在运行/暂停阶段被既有规则阻断，没有新增退出账号接口。已测试注册进行中暂停不会启动编码；队列实际处理时再次校验时间和当前task，暂停清空队列并更新恢复阈值，不允许旧任务复活。

8766 原进程PID20572，启动参数 `C:/Python314/python.exe server.py --port 8766`。发现用户原批次 `sim-0928205124-87f6` 正在运行（1200秒），未强制终止；待原批次自然完成后按原参数重启，不更改配置或登录资料。

## 媒体凭据隔离补充

测试视频启用时必须提供 `publisher_user`（默认 `qa-publisher`）及 `publisher_password`。密码仅保存在 Python 内存：连接表单采用空密码框，留空保留当前值；状态接口及批次导出只返回 `publisher_password_set`，不返回密码或含凭据 RTSP URL。场景文件与批次 manifest 不保存视频配置。

FFmpeg 使用百分号编码后的 userinfo 推 RTSP，配置中的 `rtsp_base` 仍拒绝 userinfo。原始 stderr/stdout 转到 DEVNULL，错误通过退出码和通用原因显示；Python异常文本再对明文密码、编码密码及RTSP userinfo脱敏，避免错误/traceback进入运行日志。新增3项凭据验证/脱敏/status-export测试后，Python全量 **47项通过**，JS语法及差异检查通过。此前无凭据媒体联调仅证明传输机制，认证后的联调另行追加。

凭据防泄漏追加验证：模拟 `Popen` 异常回显含密码RTSP URL，状态结果只保留 `[redacted]`；编码stdout/stderr确认为DEVNULL。Python全量更新为 **48项通过**。认证联调启动时读取媒体API返回401，已清理当次隔离任务；等待媒体账户配置修正后重跑，不把早期未鉴权链路冒称为鉴权验收。

8766重启状态：用户原批次后来处于STOPPED，尝试按核实后的原启动参数停止并重启旧PID时，被工具自动审批以 `blocked by policy` 拒绝，未执行进程停止；原8766服务仍是未加载本轮代码的旧进程。没有改用其他命令绕过此拒绝。


## 媒体认证复验与隔离界面服务

媒体服务修正为索引环境变量配置账户后，动态测试图与本机含音轨短片循环两组认证联调均通过。`eo-media-smoke.json` 当前 `passed=true`：匿名读401、推流账户读401、匿名推流拒绝、只读账户推流拒绝；合法推流和授权HLS读取成功，输出无音轨。结束后两组编码器退出、注册注销、媒体路径恢复0。先前失败记录保留在 `eo-media-smoke-failed.json`，不覆盖失败历史。

额外核查配置/导出/日志：运行状态仅公开 `publisher_password_set`；场景与批次manifest不写视频配置；连接表单密码不回填，提交后清空；运行错误统一脱敏，编码器原始stderr/stdout丢弃。FFmpeg子进程参数在操作系统进程列表中仍可能被本机有权限的用户读取，这是命令行凭据的运行边界，未将其保存为日志。

已单独启动8767新模拟器（PID27872），使用 `server/target/qa-simulator-ui` 隔离数据目录，首页HTTP200，默认视频关闭且没有运行批次。原8766未停止、未修改。浏览器截图由前端代理继续补充。


## 共享工作区最终回归与真实平台问题定位

并行通知修复完成后再次运行当前共享目录：Python全量54项通过（视频22项、原有26项、通知修复6项）；Node39项通过。模拟器新增功能未修改通知文件。8767连接设置与运行记录的真实浏览器截图已收入 [界面设计说明](simulator-video-design.md)。

18091隔离PostGIS后端首轮实际链路已通过登录、Heartbeat、API创建任务、outbox BeginTracking、模拟器真实MQTT回执入库、注册与视频状态AVAILABLE。HLS仍503，经真实媒体A/B诊断确认MediaMTX子清单/初始化/分片必须带session UUID：原引用200、去掉该参数401，证据 `assets/eo-media-session-query-probe.json`。该问题已交平台代理修复，失败轮次按实际API结束任务并发送End回执，没有数据库手工改任务。完整平台结果等待代理修复后复验追加。


## 最终真实平台复验（已完成）

隔离18091使用独立PostGIS数据库与本机真实MQTT broker。夹具只提供账号、范围、带位置目标、通过API创建的replay光电绑定；任务创建/结束、设备回执和视频登记全部通过真实HTTP/MQTT，不手工写任务或回执。`tests/run_eo_platform_smoke.py` 在登录前锁定18091固定API及acceptance目标，读取绑定后再次要求replay与指定隔离external/edge标识。

| 验收项目 | 动态测试图 | 本地文件循环 |
| --- | --- | --- |
| API任务 → outbox MQTT Begin → 真实回执 → TRACKING/AVAILABLE | 通过 | 通过 |
| 授权HLS主清单、子清单、初始化和分片 | 200 | 200 |
| 输出编码与音轨 | 仅H264 video | 输入2秒H264+AAC；输出仅H264 video，已超过源片时长仍推流 |
| 匿名平台代理访问 | 401 | 401 |
| 将浏览器随机session复制到媒体端直读 | 401 | 401 |
| 自有编码器中断 | INTERRUPTED，跟踪保持TRACKING | INTERRUPTED，跟踪保持TRACKING |
| 15秒后只重试编码器 | 同task/stream/command恢复AVAILABLE，新分片200 | 同task/stream/command恢复AVAILABLE，新分片200 |
| 实际End命令/回执、进程终止、旧URL失效 | ENDED / 404 | ENDED / 404 |

证据：[动态图完整记录](assets/eo-platform-smoke-dynamic-chart.json)、[本地视频完整记录](assets/eo-platform-smoke-local-file-loop.json)。两组最终 `passed=true`；验收结束后媒体路径数量为0。命令通过 `--source-file server/target/qa-media/input-loop-with-audio.mp4` 切换本地组；浏览器通过独立marker协调中断和结束，不干涉自动15秒恢复逻辑。最终Python55项、Node39项、JS语法与git diff检查通过。

实际浏览器由前端代理验证，两组画面均960×540且实际播放时间推进；动态图支持切换目标清帧、退出登录清帧、重新登录播放、End后清帧；本地组额外拍到INTERRUPTED时移除video、15秒后自动恢复播放。证据见 `assets/frontend-qa/real-platform-browser.json`、`real-local-video-browser.json` 及对应截图。图源和本地素材都明确TEST VIDEO/设备/任务水印，无声，不代表现场光电设备验收。

媒体兼容与安全问题均已闭环：后端固定内部初始请求 `cookieCheck=1` 并保持禁止重定向；真实媒体session保存在后端内存，清单只给浏览器独立随机平台UUID，平台逐资源检查Bearer和当前任务。原始媒体session对匿名直读可用的行为已独立证实，因此不能把媒体session发到浏览器或证据；脚本证据中的此类值统一脱敏。

服务状态：8767当前HTTP200、IDLE、视频关闭、无批次、eo为空。最后尝试只重启本轮自有8767（已核实PID27872、原参数及IDLE）以加载恢复补丁，被工具自动审批 `blocked by policy` 拒绝，停止未执行，没有绕过。故8767仍是补丁前进程，原8766也未改动；最新恢复代码已由新启动的真实验收进程执行并通过，但不能声称两个常驻模拟器已加载它。
