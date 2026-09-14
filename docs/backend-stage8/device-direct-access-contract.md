# 设备直连接入契约（阶段 8.5，A/B 边界）

> 状态：v1.7（2026-09-09）。在 v1.6 上打开四通道厂家原生 TCP 设置（`0x11/0x12/0x13`），仍不登记凌云 `cm`、不用协议 B 接管四通道。inbox 探测类仍是雷达 / 5G-A / TDOA / AOA / 协议破解 / RemoteID。`oe`/`isrs` 仍不走本切片 MQTT 登记。P3 值班员手点跟踪已落地（自动跟踪默认关）。P4-A 默认关；P5 控制不写融合 inbox。雷达 TCP 维持厂家原生只读。B 侧阶段 8.5 领取前缀、映射入口与 `ingest_seq`（§6–§7）及阶段 10 的 `fusion_event` 摘要与联调输入物（§9）已落地。依据：客户 2026-09-08 确认（凌云协议 A/B/C、MQTT 模拟可先用）、会议纪要 V1.0、凌云协议 A v8.6 / B V2.4 / C 20250826、对齐文档 `target-schema-v1-alignment.md` §5。配套计划：《协作者 A 直连接入计划》《协作者 A 任务单 20260908》。

## 1. 边界

- **A（设备接入）**：MQTT/TCP 适配器、设备注册与在线态、光电边端中心、控制指令下发与回执。适配器把报文**原样**写入 `inbox_message`，不解释语义，不写 `target / track / track_point / target_latest_state / source_observation`。
- **B（统一目标库）**：从 `inbox_message` 领取并映射为 `SourceObservation`，驱动阶段 8 融合引擎与阶段 7 规则；提供 `fusion_event` 供 A 触发光电跟踪。
- 两侧共用同一套 Schema；厂家原生协议到位后只换 A 的适配器与 B 的对应映射分支。

## 2. inbox 信封（A 写、B 读）

P1（协议 A MQTT）、P3（协议 C 光电边端）、P4-A（雷达 TCP 提升）信封与 P5（协议 B 控制）已冻结。P5 控制指令不写融合领取前缀的 inbox。不得使用「任务 `msgId` 等于观测唯一编号」或「按随机 UUID 递增」。

### 2.1 P1 已冻结（协议 A，雷达 / 5G-A / TDOA / AOA / 协议破解 / RemoteID）

| 列 | 值 | 说明 |
| --- | --- | --- |
| `source` | `lingyun:<deviceTypeAbbr>:<内部标准设备ID>` | 内部 ID 是 A 登记时写入 `device.device_id` 的稳定主键，不是 Topic/`SenseData` 里的外部 `deviceId`。外部编号、提供方编码保留在 `mqtt_device_binding`。模拟 `replay` 与真实 `live` 使用不同内部 ID，避免碰撞。无 `taskId` 的来源（雷达 / TDOA / AOA）B 用**整条 `source` 串**作会话键参与 link 身份，因此该内部 ID 在同一 `source_mode` 下对同一台物理设备必须保持稳定；重新登记或迁库改号会使同一台设备的目标分叉成新的一批 |
| `source_msg_id` | `<ptTime>:<msgCnt>` | 不再单独使用循环序号。同键同原始 UTF-8 SHA-256 为重复；同键不同哈希记冲突，不覆盖、不静默丢弃 |
| `source_id` | 标准侧 `integration_source.source_id` | `source_type`：`radar→RADAR`，`5ga→FIVE_G_A`，`tdoa→TDOA`，`aoa→AOA`，`dcd→DCD`，`rid→RID`。主题缩写对应协议 A 附录 `deviceType`：`5ga=0`、`radar=1`、`aoa=9`、`tdoa=10`、`dcd=11`、`rid=102`。控制类 `dec=5`/`ifr=6`/`bsc=12` 可登记工参与协议 B 下发，**不写**本前缀 inbox，`integration_source.source_type` 为 NULL（融合目录仍恰好八种探测类）。光电 oe、察打一体 isrs、反制 cm 仍不走本切片 MQTT 登记 |
| `payload` | 协议 A 整条原始 JSON（工参不进 inbox；仅 `device_data` 的 `SenseData`） | JSON；**不**向原文插入平台字段 |
| `payload_hash` | 收到的原始 UTF-8 字节 SHA-256 小写十六进制 | `ck_stage2_inbox_payload_hash` |
| `status` | `RECEIVED` | B 领取后 `PROCESSING → DONE/FAILED`，`processed_at` 同时写。A 的独立验收不以融合目标出现为条件 |
| `received_at` | 平台实际收到时刻（`AppClock` 毫秒） | `ptTime` 与 `objects[].time` 原样留在 payload，A 不解释坐标与高度 |

Topic 与正文必须一致：`bridge/{providerCode}/device|device_data/{deviceTypeAbbr}/{externalDeviceId}`。未登记、类型不在白名单、retained、非 QoS1、身份不一致：记拒收诊断后确认，不自动创建设备。工参只更新已登记设备在线态与 `workState`；30 秒无有效工参离线；`workState=0` 不是离线。A 不解释 `objects[]`（含 SN、飞手位置、方位）；映射仍由 B 的 `LingyunSenseDataMapper` 领取 `lingyun:` 前缀完成。高度原样留在 payload，不进合法性比较（基准待客户确认）。

### 2.2 P3 已冻结（协议 C 光电边端）

| 列 | 值 | 说明 |
| --- | --- | --- |
| `source` | `eo-edge:<edgeId>` | `edgeId` 是登记时的边缘中心 ID（平台持有），不是内部 `device.device_id`。同一 edge 下多台光电共用该 `source`，设备靠 payload `metadata.deviceId` 区分。replay / live 使用不同 `edgeId` |
| `source_msg_id` | `<timestamp>:<event>:<deviceId>:<taskId或'-'>` | 取自协议 C JSON。HeartBeat / CameraStatus 无任务时最后一段为 `-`。**不得**用 `extention.msgId`（命令回显，整段跟踪期间不变）或随机 UUID |
| 去重 | 同 `(source, source_msg_id)` 且同 UTF-8 SHA-256 → 重复；同键不同哈希 → 冲突，不覆盖 | 协议 C 跟踪上报无 `msgCnt`；同一毫秒两条不同正文记冲突 |
| `source_id` | 该光电设备的标准侧 `integration_source.source_id` | `source_type=EO` |
| `payload` | 协议 C 整条事件 JSON | 不向原文插入平台字段 |
| `payload_hash` | 原始 UTF-8 SHA-256 小写十六进制 | |
| `status` | `RECEIVED` | |
| `received_at` | 平台收到时刻 | `timestamp` 原样留在 payload |

只把入站 `event=BeginTracking` 且带 `metadata.codeStatus` 的上报写入 inbox。HeartBeat / CameraStatus / EndTracking 只更新运维态或 `device_command`。确认表 D6 默认「点选之后再跟踪」：值班员手点入口为 `POST /api/v1/targets/{targetId}/eo-tracking-tasks`（`devices.op` + 目标 `target:read` 可见），停止为 `POST /api/v1/eo-tracking-tasks/{taskId}/end`；查询进行中任务 `GET /api/v1/targets/{targetId}/eo-tracking-tasks`。自动轮询 `fusion_event` 受 `app.eo-edge.auto-track.enabled` 控制，**默认关**。未开任务的跟踪上报仍拒收 `TRACK_NOT_OPEN`。协议 C 标「暂不支持」的事件不实现。

### 2.3 P4-A 已冻结（雷达 TCP 轨迹提升，默认关）

| 列 | 值 |
| --- | --- |
| `source` | `live-radar:<deviceId>`。`deviceId` 是标准 `integration_source.source_code`（雷达 TCP 接入时等于台账 `device_no`），**不是** ops UUID |
| `source_msg_id` | `<boot_micros>:<frame_id>`，取自解码后的 `radarBootMicros` 与 `payloadFrameId` |
| `source_id` | 标准 `integration_source.source_id`（B 的端口按 `source_code` 且 `enabled=true` 解析；未登记或停用拒收，不自动建来源） |
| `payload` | `{device_id, boot_micros, frame_id, items:[{external_track_id, longitude, latitude, z_m, velocity_x_mps, velocity_y_mps, velocity_z_mps, snr_db, rcs_m2, classification}]}` |
| `payload_hash` | 序列化后 UTF-8 SHA-256（端口计算） |
| `status` | `RECEIVED` |

受 `app.fusion.live-promotion.enabled`（`APP_FUSION_LIVE_PROMOTION_ENABLED`）控制，默认关：关则零行 `live-radar:`，ops 的 `live-device:*` 与航迹表与关闭前一致。只提升 `COMMAND_UPLOAD_TRACK_V3` 航迹批；点迹、RTK、反制不写该前缀。`items` 空数组合法。`rcs_m2` 取高分辨率 RCS，缺则用协议遗留 RCS。`classification` 为解码器已有 `categoryCode`（`PENDING_IDENTIFICATION` / `PERSON` / `VEHICLE` / `UAV` / `BIRD` / `UNIDENTIFIED`）。`longitude`/`latitude` 仅在 ops 已有非空派生经纬度时填入，否则 JSON `null` 或省略，**不写 0,0**。打开开关不等于客户现场雷达联调完成。

B 的 `FusionInboxRepository.claim` 已按映射器前缀领取 `replay:` / `lingyun:` / `eo-edge:`；`live-radar:` 仅在开关打开时领取。P5 控制走 `device_command` + MQTT `device_control` / `device_control_resp`，回执 inbox 前缀为 `control-resp:`（不在领取白名单）。

## 3. 映射（B 实现，A 不做）

| 协议字段 | `SourceObservation` | 规则 |
| --- | --- | --- |
| 协议 A `objects[].objectId` / `time` / `ptTime` | `external_target_id` / `observed_at` / `received_at` | 毫秒 UTC |
| `longitude/latitude` | `location` | 光电（`oe`）与 AOA **置 NULL**；AOA 的 `extension.direction` 进 `quality.bearing_deg` |
| `altitude` | `quality.altitude_raw`；`altitude_amsl_m/height_agl_m` = `REFERENCE_UNKNOWN` | 基准（海拔 vs 椭球高）未确认前不进合法性比较 |
| `height` | `height_agl_m` + `quality.height_datum=DEVICE_GROUND` | 相对基站安装点地面 |
| `speed` / `extension.speedX/Y/Z` | `speed_mps` / `heading_deg`（X 正东、Y 正北）/ `quality.speed_xyz` | |
| `extension.objectType` | `class_code`：0 UNKNOWN / 3 PERSON / 7 VEHICLE / 30 UAV / 40 BIRD / 50 SHIP / 100 REMOTE_CONTROLLER / 255 → NULL + `quality.identifying=true` | `OBJECT_TYPE_LABEL` 字典同步扩展 |
| `extension.probability` | `class_confidence` | |
| `uavSN` / `uavModel` / `channel` / `bandWidth` | `identity_clue`（SN 优先）/ `quality.rf` | |
| `pilotLon/pilotLat` | 新列 `source_observation.pilot_location`（POINT 4326） | 阶段 7 C02-6 超视距、C01 身份维度输入 |
| `extension.taskId` | `source_session_key` | 5G-A |
| 协议 C `aiStatus.className/detectConfidence/trackConfidence` | `class_code`（drone→UAV, bird→BIRD，未知值 NULL + 原串进 `quality.class_name_raw`）/ `class_confidence` / `quality.track_confidence` | 光电唯一的类别来源 |
| `aiStatus.latitude/longitude/altitude` | `location`（高度同上） | 只在跟踪任务期间存在 |
| `taskId` / `edgeId` / `cameraStatus` / `objectData` | `source_session_key` / `quality.edge_id` / `quality.camera` / `quality.bootstrap_source_*`（**不作为观测入库**） | |
| 精度 | `fusion_config.filter.accuracy_default_m[source_type]`（新增 AOA/DCD/RID，DEMO） | 协议无精度字段 |

## 4. `fusion_event`（B 写、A 读）

`event_type='STATUS_STABLE'` 的 `payload` 追加：`{target_id, target_no, class_code, latest_state:{longitude, latitude, altitude_raw, altitude_datum:'UNCONFIRMED', speed_mps, heading_deg, observed_at}, degradation_level, alarm_active:boolean, max_risk_severity}`。A 的光电跟踪触发只读该表（按 `(created_at, event_id)` 序偶前进，因为 `event_id` 是 UUID），不更新、不删除。

## 5. 待确认（客户/厂家）

高度基准；各源标称精度；`objectId` 生命周期与上报频率；光电 `className` 取值表与门限；云台角度控制与"执行中/急停"回执。确认前一律原样透传并标注，不猜测。

## 6. 冻结接口与落点（阶段 8.5，B 已落地）

- `FusionContracts.SourceEstimate` 追加可空 `pilotLongitude / pilotLatitude / classSource`（旧 21 参构造器保留，新字段为 null）；`RuleContracts.TargetState` 追加可空 `pilotLongitude / pilotLatitude`（旧 12 参构造器保留）。
- 迁移 070（领导）：`source_type_catalog` 增 `AOA / DCD / RID`，EO/TDOA/5G-A/融合箱 `spec_ref` 指向凌云协议 A；`fusion_config demo-v1` 的 `accuracy_default_m` 与 `weights` 增三项（AOA 位置权重 0）。
- 迁移 071（E1）：`source_observation ADD pilot_location GEOMETRY(POINT,4326), class_source VARCHAR(16)`；`target_latest_state ADD pilot_location GEOMETRY(POINT,4326)`；`R__stage85_direct_access.sql`：两列 SRID/范围 CHECK + GIST。
- ~~迁移 072~~ 取消（决策 8.5-12）：C02-6 复用既有参数 `C02-6.vlos_m`（500 m，DEMO），不新增参数。
- 映射入口：`modules/fusion/ingest/InboxSourceRouter` 按 `source` 前缀分派 `FrameMapper`：`replay:`（`ReplayFrameMapper`）、`lingyun:`（`LingyunSenseDataMapper`）、`eo-edge:`（`EoTrackingReportMapper`）、`live-radar:`（`LiveRadarFrameMapper`）；`FusionInboxRepository.claim` 白名单同四个前缀（`live-radar:` 受开关控制）。
- `class_source` 取值：`SENSE_DATA`（协议 A objectType）、`EO_TRACKING`（协议 C aiStatus）、`RADAR`（雷达分类码）、`MANUAL`（人工修订）。
- C02-6：`TargetState.pilot*` 存在 → 目标与飞手位置大圆距离 > `C02-6.vlos_m` → FAIL `BVLOS_EXCEEDED`，≤ → PASS；不存在 → UNDETERMINED `PILOT_POSITION_UNAVAILABLE`（不变）。
- 高度：凌云来源 `altitude` 只落 `quality.altitude_raw`，`quality.altitude_datum=REFERENCE_UNKNOWN`，`altitude_amsl_m` 留空（决策 8.5-24）；`height` → `height_agl_m` + `quality.height_datum=DEVICE_GROUND`。
- 协议 C：`external_target_id` 与 `source_session_key` 同取 `taskId`（8.5-20）；未映射事件返回空帧、inbox DONE（8.5-22）。协议 A 帧级 `observed_at` 取首个对象 `time`（8.5-21）。
- A 写入的 `source_msg_id` 以 §2.1 / §2.2 为准（协议 A 为 `ptTime:msgCnt`，协议 C 为 `timestamp:event:deviceId:taskId`），**不要**只用 `msgCnt` 或 `extention.msgId` 去重。

## 7. 提交后跟进（决策 8.5-27 / 8.5-28）

- `target_attribute_selection`：帧内无任何来源估计时不写选源行（保留上一帧归属），"当前无来源"由 `target_degradation` 记录。
- `target_latest_state.pilot_location` 只由携带身份主源的帧改写：身份主源在但未报 pilot → 写 NULL；整帧无身份主源 → 不碰该列。迁移 072 增 `pilot_observed_at`（与 `pilot_location` 同写同留），`RuleContracts.TargetState` 增可空 `pilotObservedAt`（旧 12/14 参构造器保留），C02-6 facts 输出 `pilot_observed_at`。本期不设独立"飞手位置过期"阈值，过期性由目标整体新鲜度（C03 `fresh_seconds`）兜底。
- `inbox_message` 增单调写入序列 `ingest_seq`（迁移 073），`claim`/`failExhausted` 按 `received_at, ingest_seq` 领取；跨来源同毫秒帧无事实先后，管线以"同一批帧任意顺序处理结果一致"为守护性质（8.5-29）。

## 8. A 侧 P1/P3 写入事实（给映射核对）

A 不修改融合代码。B 已领取 `lingyun:` / `eo-edge:`。核对时注意：

1. `source` 第三段是**内部设备 ID**（`device.device_id`），外部 `deviceId` 只在 payload / Topic 中。该主键在首次登记时分配，更新名称/厂家/型号不改号，接入身份字段不可改。无 `taskId` 的来源（雷达 / TDOA / AOA）B 用整条 `source` 作会话键：同一 `source_mode` 下同一台物理设备必须沿用该 ID；删除后重登或迁库改写 `device_id` 会使目标分叉。`replay` 与 `live` 故意使用不同内部 ID。
2. `received_at` 是平台接收钟；观测时刻取 payload 的 `objects[].time`（协议 A）或事件 `timestamp`（协议 C）。
3. 协议 A 工参主题 `bridge/.../device/...` **不会**出现在 inbox。设备在线态在运维 `ops_device_state`。
4. 协议 A 可能写入 `objects: []` 的合法空列表，应视为无观测而不是错误帧。
5. 协议 C 只把 `event=BeginTracking` 上报写入 inbox；`EndTracking` 之后不再有该任务的 `eo-edge:` 观测行。`objectData` 不当观测。
6. 契约 §4 的 `STATUS_STABLE` payload 见 §9：`latest_state` 已由 B 写入；`alarm_active`/`max_risk_severity` 有当前告警/风险才出键。A 的自动跟踪在缺键时跳过；测试可直接插入事件行。
7. P4-A `source` 的 `deviceId` 是标准 `integration_source.source_code`，不是 ops `device_id`。雷达 TCP 登记/启用会幂等补标准 `integration_source`（`source_type=RADAR`），这是登记补全，不是从报文自动发现。ops `live-device:` 与 `live-radar:` 是两行；前者重复则整帧（含提升）跳过。该 `source_code` 对同一台物理雷达同样必须保持稳定（接入时等于台账 `device_no`）。
8. P5 协议 B 控制不写 `lingyun:` inbox。下发 Topic `bridge/{providerCode}/device_control/{type}/{externalDeviceId}`，回执 `device_control_resp`；`msgNo` 等于 `command_no`。急停设备协议未提供（停止用同一指令的 `operationType=0`）。诱骗/干扰/驱鸟炮按附录缩写 `dec`/`ifr`/`bsc` 下发，须与绑定类型同族；协议标明「未有真实设备」的 50000/50001/50004/50006/50007 仍不进白名单。`cm` 不走本切片 MQTT 登记；四通道走厂家原生 TCP（`COUNTERMEASURE_TCP_4CH_V2_0`，REST `countermeasure-4ch`）。

## 9. 阶段 10 补充（B 侧）

- `fusion_event` §4 的 `latest_state` 摘要由 `DefaultFusedLayerWriter.emitEvents` 实现；`pilot_location` 有则出键；`alarm_active`/`max_risk_severity` 从 `uav_event`/`flight_risk` 当前状态取，取不到不出键；`altitude_datum` 固定 `UNCONFIRMED`（10-1）。
- 联调输入物：`docs/直连接入计划/stage85-lingyun-demo.mqtt.ndjson`，每行 `{topic, qos, payload, record_no, received_at, source}`（`source` 是该报文按 §2 应落成的 inbox `source` 值，供 A 核对适配器信封，不是设备报文的一部分、不发布）；协议 A 主题 `bridge/{providerCode}/device_data/{deviceTypeAbbr}/{deviceId}`（providerCode 用 `dongying`），协议 C 主题 `iot-reporting/cmlc/edge/{edgeId}`；payload 为设备原文，与回放种子写入 inbox 的 `payload` 逐字一致（10-4）。A 的 P1 用任意 MQTT 客户端按行发布即可复现 8.5 的 v2 场景。
- 协议 A `objects: []` 由 `LingyunSenseDataMapper` 视为空闲帧（决策 10-16），与 §8.4 一致。
