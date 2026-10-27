# 光电实况只读契约

`GET /api/v1/devices/eo-situation?page=1&size=100` 复用设备列表权限、数据范围、业务设备来源和分页，最大每页100项。返回 `checked_at/items/page/size/total`，不创建需求、任务或设备指令。任务与目标标识另经目标读取权限检查，无权时返回空值。

每项包含 `device_id/observed_at/valid_until/status/reason/azimuth_deg/elevation_deg/horizontal_fov_deg/vertical_fov_deg/task_id/target_id/tracking_state/simulated/source_mode`。`status` 为 `READY` 或 `UNKNOWN`，只描述姿态数据可用性。`tracking_state` 为 `IDLE/TURNING/TRACKING/LOST/UNKNOWN/STOPPED`；只有同设备当前任务的新鲜实际跟踪上报才能显示 `TRACKING`。排队、指令成功、转动、心跳和视频就绪均不能单独证明已捕获目标。

协议 `cameraStatus` 增量字段要求 `angleReference=TRUE_NORTH`、`angleUnit=DEG`、布尔 `calibrated=true`；`panOrientAngle` 为 `[0,360)`、`tiltOrientAngle` 为 `[-90,90]`、`hfov/vfov` 为 `(0,180]`。缺失、无效、未来或过期时角度均为空，返回明确原因；默认相机实况时效30秒，可由 `app.eo-edge.camera-max-age-millis` 配置。收到有效来源消息后，平台覆盖内部 `platformObservedAt/platformTaskId/platformEvent`，不信任发送端自填的同名字段。模拟标识取设备登记事实。

BeginTracking 可上报 `TURNING` 且 `workState=0`；此时接收任务进度，但不刷新跟踪事实。`TRACKING` 且 `workState=1` 才刷新当前任务跟踪事实。显式 LOST/UNKNOWN/STOPPED 不允许沿用旧AI观测；未提供扩展的既有设备继续遵循既有协议 `workState` 契约。来源、设备、任务和时间校验保留，乱序状态不能覆盖新状态。

设备配置覆盖范围与相机当前视场独立。调度、切换及实际BeginTracking下发前均以目标当前有效实测点检查已配置的WGS84圆形/扇形覆盖；缺配置、缺位置和范围外均不能分配。自动队列保持持久化同级FIFO、多设备独立分配；高优先级只可请求替换普通AUTO任务，仍受原处置保护和真实停止回执约束。既有需求触发条件、核实、反制、视频与预测守卫不变。

新增读取不需要迁移。验证以正式测试与浏览器验收报告为准，本文件不声明运行环境已更新。

合法性研判读取同目标当前未结束的FUSED轨迹作为连续轨迹依据，不能被后来加入来源的一点RAW轨迹替换。没有当前FUSED轨迹时保持原最新轨迹兼容逻辑；已结束的FUSED没有优先权。此修复不改变质量时间窗、点数门槛、来源隔离，不复制或补造轨迹点，不回写历史研判。

任务建立后若目标发生实质MERGE/SPLIT，原任务失去关联资格：读模型立即退出TRACKING，后台沿既有停止指令/真实回执闭环释放设备。即使关闭新的自动启动，也不能遗留身份失效的旧自动任务。停止未确认不启动替代任务；结束后的新有效实测、当前身份、来源、权限、观察需求和覆盖全部重新满足，才能建立新任务。旧任务及其EO融合排除事实不复活。

QA识别帧批量接入：`PUT /api/v1/local-interface-simulator/device-video-streams/{deviceId}/frames/batch`，沿用原QA环境、开关和设备操作授权。请求为 `{stream_id,task_id,frames:[{frame_timestamp,media_time_ms,detections}]}`，1～30帧，detections格式与单帧接口相同，每帧最多32项。按输入顺序处理，不补时间、不自动排序。关联候选仅查询一次，每帧仍复核当前授权、实际跟踪、任务、流、来源及目标血缘/别名，保留每帧5秒接收时效、未来拒绝、媒体时间与帧时间递增、观测关联±2秒边界。

响应为 `{stream_id,task_id,accepted_count,rejected_count,frames:[{index,frame_timestamp,media_time_ms,accepted,error_code,reason,detections}]}`，与输入顺序一一对应；空值可能按既有JSON策略省略。接受的帧仍可能明确为UNLINKED，不伪造关联。重复/未递增、过期、未来、格式错误分别逐项拒绝；坏帧不阻断后续有效帧。处理中任务、来源、血缘或授权失效时，当前及剩余帧明确拒绝，已经在变化前接受的帧不伪称回滚。请求开始即无效的任务/来源或权限仍按原整请求错误返回。原单帧接口行为保持。

## QA 视频识别帧批量接入

`PUT /api/v1/local-interface-simulator/device-video-streams/{device_id}/frames/batch` 复用原单帧接口的QA环境、授权及设备来源门禁。原 `/frames` 保持兼容。请求为 `{stream_id,task_id,frames:[{frame_timestamp,media_time_ms,detections:[原检测项]}]}`，每批1～30帧，每帧最多32个检测项，按输入顺序处理，不自动排序或补时间。

返回 `{stream_id,task_id,accepted_count,rejected_count,frames:[{index,frame_timestamp,media_time_ms,accepted,error_code,reason,detections}]}`。顶层结构、权限或初始任务无效仍拒绝整请求；处理中的过期、重复、格式及任务变化逐项返回，已成功接收的帧保留，不声称整批回滚。`accepted=true` 只表示帧已接收，检测项仍可能为 `UNLINKED`。

批次复用一次关联候选读取，每帧继续校验当前授权、任务与来源、身份变化、视频会话、未来/5秒时效、媒体及UTC时间递增和2秒观测关联。模拟器只提交在实际HLS分片中验证过的编码帧及PDT时钟关系；进程启动、读取到达时间或编码进度不能代替媒体UTC证据。
