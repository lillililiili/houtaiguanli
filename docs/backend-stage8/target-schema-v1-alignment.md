# 阶段 6.5 Schema 对齐关卡：Target Schema V1 与设备资料对照

> 状态：2026-09-06 领导冻结；同日补充凌云协议对照（§5）。客户提供三份凌云平台协议（`设备资料/凌云协议/`：A 设备数据及感知数据接入 v8.6、B 反制/光电设备控制 V2.4、C 光电设备边端协同接口 20250826），并确认**云端只保留我们的平台，不再保留凌云或其他中转**（决策 8-30）：我们坐到凌云原来的位置——按协议 A 收设备数据、按协议 B 发控制指令、在融合箱里按协议 C 当光电的边缘中心。三路字段从 Demo 升级为“协议已提供、未联调”，库内 `schema_status` 联调通过前仍为 DEMO，页面标注改为“按凌云协议 v8.6 建模，待联调”。厂家原始协议（纪要 A2）仍欠。
>
> 原状态：2026-09-06 领导冻结。`设备资料/` 只有雷达（T02 v3.0.0 协议、两份规格书）与反制文档，**没有 5G-A、TDOA/AOA、光电资料**。按用户决定，本关卡只对齐雷达；其余三路以 Demo 字段建模，`source_type_catalog.schema_status=DEMO`，页面与文档必须标"演示字段 / 待确认"。资料到位后只改映射层（`SourceObservation` 适配），不改融合算法与表结构。

## 1. 来源类型目录

| source_type | schema_status | 资料依据 | 位置 | 精度 | 类别 | 身份线索 | 高度基准 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RADAR | CONFIRMED | `低空监视雷达网络通信协议_v3.0.0`（已接入 `RadarV300PayloadDecoder`） | 雷达体坐标 `x/y/z_m` → 经站址 RTK（`Rtk.latitudeDeg/longitudeDeg/headingDeg`）换算 WGS-84；已接入代码给出 `longitude_deg/latitude_deg` | 协议无精度字段 → Demo 缺省 15 m（`filter.accuracy_default_m.RADAR`） | `classification 0–5`（待识别/人/车辆/无人机/鸟/未识别），**无类别置信度** | 无 | `z_m` 为雷达站相对高度，站址高程未知 → AGL/AMSL 均不可判定，记 `NOT_REPORTED` |
| EO（光电） | DEMO | 无资料 | Demo：WGS-84 点（若给出） | Demo 25 m | Demo：`class_code + class_confidence [0,1]` | 无 | Demo：AMSL |
| TDOA | DEMO | 无资料 | Demo：WGS-84 点 | Demo 60 m | 无 | Demo：`identity_clue`（射频指纹/遥控器标识） | 无 |
| FIVE_G_A | DEMO | 无资料 | Demo：可选 WGS-84 点 | Demo 80 m | 无 | Demo：`identity_clue`（小区/终端标识） | 无 |
| FUSION_BOX | DEMO | 无资料（融合感知箱输出格式未见） | Demo：WGS-84 点 | Demo 20 m | Demo 可选 | 无 | Demo：AMSL |

## 2. Target Schema V1 字段对照

| V1 字段（`target` / `target_latest_state` / `track_point`） | 雷达 T02 来源 | 换算 / 判定 | Demo 三路 |
| --- | --- | --- | --- |
| `target_no` | 平台生成 | 融合层分配统一编号（B02） | 同 |
| `object_type_code` | `classification`→{0 UNKNOWN,1 PERSON,2 VEHICLE,3 UAV,4 BIRD,5 UNKNOWN} | 雷达类别不带置信度，`classification_confidence` 保持 `NOT_REPORTED` | EO 提供类别与置信度；TDOA/5G-A 无 |
| `location` (POINT 4326) | `x/y/z_m` + 站址 RTK | 无站址 RTK → 位置 `REFERENCE_UNKNOWN`，不补 (0,0) | 直接 WGS-84 |
| `position_accuracy_m`（新增于 `source_observation`/`track_point`） | 无 | 取目录缺省值并在页面标 DEMO | 各自缺省 |
| `altitude_amsl_m` / `height_agl_m` | `z_m`（相对站址） | 站址高程与地形基准未知 → 两者皆 `REFERENCE_UNKNOWN` | EO/融合箱 Demo 给 AMSL；不互推 |
| `speed_mps` / `heading_deg` | `velocity_x/y/z_mps` → 水平速度与航向（需 `northFlag`/站址航向） | 缺站址航向 → 航向 `REFERENCE_UNKNOWN` | 同源提供或由滤波推导（标 DERIVED） |
| `observed_at` / `received_at` | `radarBootMicros + 帧时间` / 平台接收 | 开机微秒相对时钟，需帧时间对齐；本期回放数据集直接给 epoch | 同 |
| `source_session_key` / `external_target_id` / `external_track_id` | `deviceId:bootMicros` / `externalTrackId` | 与 `ops_target_source_link` 一致 | 数据集 / 来源目标号 |
| `classification_confidence` | 无 | `NOT_REPORTED` | EO 有 |
| `fusion_confidence` | 平台计算 | `1 − confidence_deficit`（B05） | 同 |
| `unknown_fields` | 平台计算 | 每个缺失字段带原因码（`NOT_REPORTED / REFERENCE_UNKNOWN / DERIVED`…） | 同 |
| 质量：`snr_db`、`rcs_*`、`latency_ms` | `snrDb`、`legacyRcsM2/highResolutionRcsM2` | 进 `source_observation.quality` JSONB，参与异常源降权 | Demo：`latency_ms` |

## 5. 凌云协议 → Target Schema V1 对照（2026-09-06，供 A 写适配器、B 写 `SourceObservation` 映射）

### 5.1 协议 A `SenseData`（设备 → 平台，MQTT `bridge/{providerCode}/device_data/{deviceTypeAbbr}/{deviceId}`）

| 协议字段 | V1 字段 | 说明 |
| --- | --- | --- |
| `deviceId` / topic `{providerCode}` `{deviceTypeAbbr}` | `device.external_device_id`、`integration_source.source_type` | 类型缩写：`5ga→FIVE_G_A`、`radar→RADAR`、`oe→EO`、`tdoa→TDOA`、`aoa→AOA`、`dcd→DCD(协议破解)`、`rid→RID(RemoteID)`、`isrs→FUSION_BOX(察打一体，子设备按 subDeviceType 拆)`；目录需新增 AOA/DCD/RID 三行 |
| `ptTime` / `objects[].time` | `received_at` / `observed_at` | 毫秒 UTC，直接落 epoch |
| `msgCnt` | `source_observation.quality.msg_cnt` | 0–2147483647 循环，用于丢包统计 |
| `objects[].objectId` | `external_target_id` | 各源自己的目标号；ID 生命周期未说明（§3-2） |
| `longitude/latitude` | `location` | WGS-84；**光电（oe）和 AOA 时无效，必须置 NULL**（协议原文），AOA 用 `extension.direction` 只给方位 |
| `altitude` | 暂不落 `altitude_amsl_m` | 协议 A 写“海拔高度（GPS/北斗）”，协议 B/C 写“椭球高”。**未确认前落 `quality.altitude_raw`，`altitude_amsl_m/height_agl_m` 记 `REFERENCE_UNKNOWN`**，不进合法性高度比较 |
| `height` | `height_agl_m` | “相对基站安装的地面高度”，可选；基准是设备安装点地面，记 `quality.height_datum=DEVICE_GROUND` |
| `speed` | `speed_mps` | 必填 |
| 5G-A/雷达 `extension.speedX/Y/Z` | `heading_deg`（由 X 正东、Y 正北算）、`quality.speed_xyz` | Z 向上 |
| `extension.rcs / length / width / height` | `quality.rcs_m2 / size_cm` | 参与异常源降权，不参与位置 |
| `extension.objectType` | `class_code` | `0 UNKNOWN / 3 PERSON / 7 VEHICLE / 30 UAV / 40 BIRD / 50 SHIP / 100 REMOTE_CONTROLLER / 255 IDENTIFYING`；`object_type_code` 字典需扩 PERSON/VEHICLE/SHIP/REMOTE_CONTROLLER；255 记 `class_code NULL + quality.identifying=true` |
| `extension.probability` | `class_confidence` | 可选 |
| TDOA/AOA/DCD/RID `extension.uavSN / uavModel / channel / bandWidth` | `identity_clue`（`uavSN` 优先，其次 `uavModel`）、`quality.rf` | `uavSN` 在 DCD/RID 必填、TDOA 可选 |
| `extension.pilotLon / pilotLat` | 新列 `source_observation.pilot_location`（POINT 4326，可空） | **飞手/遥控器位置**：阶段 7 C02-6 超视距与 C01 身份维度的真实输入 |
| `extension.taskId`（5G-A） | `source_session_key` | 任务 id 作会话键 |
| 光电 `extension.focalLen / detectionRange` | `quality.eo` | 光电在协议 A 里不给位置和类别，类别与预估位置来自协议 C |

### 5.2 协议 C 光电跟踪上报（边缘中心 ↔ 光电，`iot-reporting/cmlc/edge/{edgeId}`）

| 协议字段 | V1 字段 | 说明 |
| --- | --- | --- |
| `BeginTracking.metadata.aiStatus.className / detectConfidence / trackConfidence` | `class_code`（`drone→UAV`、`bird→BIRD`）、`class_confidence=detectConfidence`、`quality.track_confidence` | 光电类别与置信度的唯一来源 |
| `aiStatus.latitude/longitude/altitude` | `location`，高度同 §5.1 待确认 | “设备预估的目标位置”，只在跟踪任务期间存在 |
| `taskId / deviceId / edgeId` | `source_session_key=taskId`、`device.external_device_id`、`quality.edge_id` | 一个跟踪任务一条 link |
| `cameraStatus.*` | `quality.camera` | 供来源核验面板 |
| `objectData`（引导源） | 不作为观测入库；记 `quality.bootstrap_source_id/type` | 避免把引导目标当作光电自己的观测重复入库 |
| `HeartBeat.workState` | 设备在线态 | 至少 1 s 一次；`fusion/status.online` 可改用心跳 |

### 5.3 协议 B（平台 → 设备控制，阶段 10 / A 的设备控制框架）

指令码表：雷达 `10000` 探测模式；光电 `30000–30003` 拍照/摄像/目标跟踪/配置防区；诱骗 `50002/50003/50005` 方向/角度/经纬度驱离，`50100/50101` 天线切换；干扰 `60002/60003` 驱离/迫降，`60100/60101`；驱鸟炮 `70001`；AOA `90000`、TDOA `100000` 侦测模式。`operationType 0 停止 / 1 开启 / 2 更新定向方向`；`operationParams.targetId/targetLongitude/targetLatitude/targetAltitude(椭球高)/duration[10,300] s/bands/defenseZoneId/cameraId[]`；响应 `code 0/1 + msg`。**缺口**：无“执行中/急停”状态、无回执时序、云台角度控制在协议 C 里标“暂不支持”——阶段 10 按纪要要求建模授权/急停/回执，设备侧缺失的状态标“设备协议未提供”。

### 5.4 对阶段 8 实现的改动清单（阶段 9 提交后做，作为阶段 8 补充切片）

1. `source_type_catalog` 增 `AOA / DCD / RID` 三行（DEMO，spec_ref 指向协议 A）；`object_type_code` 字典扩 `PERSON/VEHICLE/SHIP/REMOTE_CONTROLLER`。
2. `source_observation` 追加 `pilot_location GEOMETRY(POINT,4326)`；`SourceEstimate` 加 `pilotLongitude/pilotLatitude`。
3. 回放生成器改为输出协议 A `SenseData` 与协议 C `BeginTracking` 报文；新增 `LingyunSenseDataMapper`（协议 A → `SourceObservation`）与 `EoTrackingReportMapper`（协议 C → `SourceObservation`），光电改为“只在跟踪任务期间有观测”。
4. 阶段 7：C02-6 超视距与 C01 身份维度接 `pilot_location / uavSN`（新增参数 `C02.bvlos_distance_m`，DEMO）。
5. 高度基准：在客户确认“海拔 vs 椭球高”前，所有凌云来源高度不进合法性比较。

## 3. 待确认清单（资料到位即问）

1. 内部统一坐标系与高程基准：雷达 `z_m` 的站址高程/基准；**凌云协议 A 的 `altitude`（“海拔，GPS/北斗”）与 B/C 的“椭球高”到底是哪一个，与我们空域/计划的 AMSL 如何换算**。
2. 5G-A、TDOA 的目标 ID 生命周期、精度字段含义、上报频率、身份线索字段与语义。
3. 光电分类字段、置信度语义、分类置信度门限——协议 C 已给 `className/detectConfidence/trackConfidence`，门限与 `className` 取值表待要（目前只知 drone/bird）。
4. B03 正式权重、异常源降权、无数据降级策略（当前 `fusion_config demo-v1` 全部 DEMO）。
5. 是否允许跨组织/区域关联（本期 `fusion_domain=(source_mode, owner_org_id, district_id)` 内关联）。
6. 融合感知箱输出格式（若存在），以及它与平台融合的分工。
7. 实测雷达 ops 模型 → 统一目标库的提升（本阶段不做，见契约与 `decisions.md` 8-1）。

## 4. 给 A 的回放场景需求（阶段 8 自建生成器已覆盖）

同目标三路可见、单路缺失（TDOA 20 s 空窗）、交叉、分裂/合并、迟到乱序、精度差异；信封沿用 `docs/backend-stage1/t02-replay-contract.md`（`replay:<source_code>:<dataset_id>`、`record_no`、`payload_hash`）。若 A 后续提供真实回放数据集，替换生成器输出即可，字段以 §2 为准。
