# low-altitude-server

2026-09-17：飞行风险列表、CSV 与空间汇总支持可选 `exclude_demo_samples=true`，供业务前台隐藏预设气象与计划通知样例；默认查询、历史记录和 MQTT replay 规则结果保留。详见[风险接口契约](../docs/backend-stage4/alarm-risk-api-contract.md)。

2026-09-14：旧工作区 `dongyiwurenji/server` 的目标查询、飞行航迹/系统核验、天气风险、空域提前结束与本地模拟改动已迁入本仓库；保留本仓库的地图管理、统计导出、设备删除和完整调测信息。迁移版本冲突以追加独立迁移处理，详见 [迁移记录](../docs/新后端迁移记录.md)。

无人机融合感知与低空安全管理平台的唯一后端，同时服务业务前台和后台管理系统。沿用 Java 17、Spring Boot 3.4.5、MyBatis Starter 3.0.4、Flyway、Maven Wrapper（Maven 3.9.9），开发数据库示例为 PostgreSQL 16/PostGIS 3.5。身份权限、设备运维以及目标/轨迹只读切片已形成可运行接口；其余业务域仍按开发基线渐进建设。

- [后端开发基线](../docs/后端开发基线.md)：业务范围、能力状态、资料缺口与开发顺序。
- [数据库设计文档](../docs/数据库设计文档.md)：现有表、拟建字段与关系、约束索引和分期迁移设计，尚未执行建表。
- [设备运维接口契约](../docs/设备运维接口契约.md)：设备台账、实时监测、重启与接入调测的 REST 契约和联调边界。
- [运行统计接口契约](../docs/运行统计接口契约.md)：分析报告「运行统计」聚合查询。
- [系统管理接口](../docs/系统管理接口.md)：登录、菜单、用户、组织区域、自定义角色和审计接口。
- [后端项目规则](AGENTS.md)：分层、接口、安全、事务、测试及交付要求。
- [仓库目录约定](../docs/目录结构.md)：前后端与部署位置。

已有登录/退出/当前用户、数据库 Bearer 会话、角色权限、审计写入，以及设备台账、状态历史、事件、告警、重启命令、调测任务与运行统计聚合接口。设备动作由持久化 Outbox Worker 推进；开发模拟结果均显式标记。设备适配层已按 `source_mode + protocol_code` 路由，并实现 T02/兼容机扫雷达 TCP v3.0.0 只读接入与固定式四通道网络控制器 v2.0 的查询及继电器设置。真实射频发射、雷达启停和正式验收阈值仍关闭。运行统计当前聚合 `report_*` 样本事实与设备台账，并提供原同口径 CSV 以及日报、自然周报、自然月报的即时预览和四工作表 XLSX 导出。

T02 只读契约与设备运维模型使用独立表：契约表保留 `device/target/track/alarm` 等稳定名称，既有设备运维与 live 感知表使用 `ops_*` 前缀。`device:read/target:read/alarm:read` 是独立动作权限，不能由运维菜单权限或 `ROLE-ADMIN` 名称隐式推导；生产迁移只建目录，不自动授权。合成动作授权同时要求 `local`/`test` profile 与 `app.dev-seed.enabled=true`，生产 profile 即使误开该属性也不会加载授权 Seeder。

## 本地启动

准备 JDK 17 和 Docker；用 Wrapper 固定 Maven 版本。本仓库 local API 端口为 8081、管理前端 5175；业务前台如需接入，应显式将 API 代理改为 8081。local 默认数据库是独立的 `houtaiguanli`，与旧工作区 `uav` 库分开。以下数据库必须是隔离开发实例，不使用生产库或已有业务库作试验。

在仓库根目录启动开发数据库（需要 MQTT 模拟时一并启动 Mosquitto）：

```bash
cd deploy
docker compose up -d db
# 可选：本机凌云 MQTT 回放
docker compose up -d db mosquitto
```

从 `deploy/` 进入后端，Windows PowerShell：

```powershell
cd ../server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Linux/macOS 在 `server/` 执行：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile 启用 `spring-boot-devtools`：`target/classes` 变化后会快速重启应用，不必关掉 `spring-boot:run`。保存 Java 或 `src/main/resources` 后需要先编译（IDE 自动构建，或在 `server/` 执行 `.\mvnw.cmd compile -DskipTests` / `./mvnw compile -DskipTests`）。这不是前端那种方法体热替换；改方法签名、配置类或 Flyway 迁移仍可能需要重新执行 `spring-boot:run`。可执行包默认不包含 DevTools。前端静态页由 Vite 热更新，与此后端重启相互独立。

数据库连接按 [application-local.yml](src/main/resources/application-local.yml)与 [Compose](../deploy/compose.yml)保持一致；修改了数据库凭据后须同步本地连接配置，不要提交或输出真实凭据。Flyway 会对所配置的数据库执行迁移。

`local` profile 幂等补齐唯一合成超级管理员 `admin1` 以及三个目标/轨迹示例，不再预置运维模拟设备台账；默认密码为 `changeme`，可通过 `APP_DEV_SEED_PASSWORD` 覆盖。目标示例包含可信 WGS-84 目标以及无最新位置、仅有历史轨迹的目标。`app.dev-seed.enabled` 为真时还会写入运行统计样本事实（近 30 天空中目标与处罚案件），供统计页查询，不是生产指标。其他角色和账号由 `admin1` 在系统管理中按需创建。所有开发 Seeder 同时受 `!production & (local | test)` profile 和 `app.dev-seed.enabled=true` 约束，默认环境和 `integration` profile 默认关闭，`test` profile 显式启用；设备模拟夹具仅在 `test` profile 注入，不能当作现场设备。MQTT 回放种子 `LocalMqttSimSeeder` 只在 `local` 注册，避免把 `S85*` 设备写进 `test` 台账。本机四通道模拟器与 `CM4-LOCAL` 同样只在 `local` 注册，不挂 `test`。本工程不是可直接上线的生产配置。

两位开发者的个人数据库、共享联调库与迁移协作流程见[协作开发环境](../docs/协作开发环境.md)。

启动后可检查 `GET /actuator/health`；无 Bearer 请求 `GET /api/v1/devices` 应为 401。登录返回 `session_id` 后，以 `Authorization: Bearer <session_id>` 请求 `/api/v1/auth/me`。所有系统管理写接口还必须带 8–128 位 `Idempotency-Key`，更新已有资源须提交 `expected_version`。运行仓库根目录的 `.\scripts\verify-dev.ps1` 会检查健康状态、登录、`/auth/me` 和 Vite API 代理。

协议 A MQTT（`LINGYUN_MQTT_V8_6`）与协议 C 光电边端（`EO_EDGE_MQTT_20250826`）由 `app.mqtt.enabled` 控制，默认开启；`test` profile 关闭以免占用嵌入式测试库。本地模拟：在设备页配置 `source_mode=replay` 的 MQTT 连接（回环仅允许 replay）。协议 A 登记雷达/5G-A/TDOA/AOA/协议破解/RemoteID，向 `bridge/{providerCode}/device|device_data/{type}/{externalDeviceId}` 发布。光电登记 `edgeId` 与设备 `deviceId`，设备向 `iot-reporting/cmlc/edge/{edgeId}` 上报 HeartBeat / BeginTracking，平台向 `iot-dispatcher/cmlc/edge/{deviceId}` 下发。有效告警或高风险目标会自动选择同机构、同辖区的在线空闲光电设备；`local` profile 默认开启，生产环境通过 `app.eo-edge.auto-track.enabled` 显式开启。人工补跟踪接口为 `POST /api/v1/targets/{id}/eo-tracking-tasks`。真实 broker 的密码只通过 `credential_ref=env:变量名` 注入，配置了 live 不等于现场已联调。目标/跟踪上报进入 `inbox_message` 后仍为 `RECEIVED`，融合消费由协作者 B 领取。雷达 TCP 与四通道反制维持厂家原生协议（四通道不登记凌云 `cm`）。本机回放步骤见下方「本地 MQTT 模拟」；数据集说明见[凌云回放说明](../docs/直连接入计划/凌云回放说明.md)。

## 本地 MQTT 模拟

迁入的 `server/scripts/keepalive_lingyun_static.py` 用于持续发送带新时刻的本地工参，避免冻结回放的时间戳被判为旧报文；`publish_lingyun_ndjson.py` 支持 `--eo-task-id` 和 `--renumber-msgcnt`，需要时显式指定，冻结原文件保持不变。`simulate_flight_check_mqtt.py` 为 `FP-CHECK-*` 设备发送本机工参；local 下 `FLIGHT_DEVICE_CHECK_MQTT_DEMO_ENABLED=true` 仅供 mock 计划演示，真实计划不使用模拟检查结果。脚本默认连接本机 MQTT，不是现场设备联调。

飞行核验现行入口是 `POST /api/v1/flight-plans/{id}/verifications/automatic`，携带 `expected_revision`；旧人工结论入口返回 `MANUAL_VERIFICATION_DISABLED`。系统检查保留起飞状态 UNKNOWN；来源回告通道缺失时不冒充已送达。

本机用 Compose 里的 Mosquitto（只绑 `127.0.0.1:1883`）发布仓库内冻结的 180 行 NDJSON，验证协议 A/C 适配器把报文写入 `inbox_message`。这不是现场联调，也不打开融合。

`local` profile 且 `app.dev-seed.enabled=true` 时，启动会幂等登记：

- MQTT 连接 `local-lingyun-replay`：`127.0.0.1:1883`，`tls=false`，`allowed_cidrs=127.0.0.1/32`，`source_mode=replay`，并启用
- 设备：`S85R1` 雷达、`S85T1` TDOA、`S85A1` AOA、`S85G1` 5G-A、`S85D1` 协议破解、`S85I1` RemoteID、`S85Y1` 诱骗、`S85F1` 干扰、`S85B1` 驱鸟炮（`LINGYUN_MQTT_V8_6`，`providerCode=dongying`）；光电边端 `edgeId=S85E1`、`externalDeviceId=S85E1D1`（`EO_EDGE_MQTT_20250826`）

**`S85R1` 不是现场 T02 TCP 雷达。** `test` profile 不插入这些设备。已有同名连接或外部编号则跳过，不改人工登记。`api` Compose 服务不依赖 Mosquitto；本机 `spring-boot:run` 连宿主机 1883。

```bash
# 1. 启动库和本机 broker（在 deploy/）
docker compose up -d db mosquitto

# 2. local 启动后端（种子登记连接与 replay 设备）
cd ../server
# Windows PowerShell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
# Linux/macOS: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. 登录 admin1 / changeme，设备页确认连接已启用、七台 replay 设备存在

# 4. 仓库根目录发布 NDJSON（payload 原样 UTF-8 字节，禁止再 json.dumps）
python server/scripts/publish_lingyun_ndjson.py
# 缺少客户端时：pip install paho-mqtt

# 5. 按 payload_hash 对账 inbox（RECEIVED 不等于融合已出目标）
# SELECT source, source_msg_id, status FROM inbox_message
#   WHERE source LIKE 'lingyun:%' OR source LIKE 'eo-edge:%';

# 6. 光电 BeginTracking 须先手点跟踪（POST /api/v1/targets/{id}/eo-tracking-tasks），
#    否则适配器记 TRACK_NOT_OPEN；local 环境默认开启融合消费，可用 APP_FUSION_ENABLED=false 显式关闭；其他环境默认关
```

发布脚本参数：`--host --port --file --limit --sleep-ms --dry-run`。默认文件为 `docs/直连接入计划/stage85-lingyun-demo.mqtt.ndjson`，不要改这个文件。工参与 5G-A / 协议破解 / RemoteID 用阶段 2 文件：

```bash
python server/scripts/publish_lingyun_ndjson.py --file docs/直连接入计划/stage2-lingyun-static-dcd-rid.mqtt.ndjson
```

诱骗/干扰/驱鸟炮工参（阶段 3，不改冻结文件）：

```bash
python server/scripts/publish_lingyun_ndjson.py --file docs/直连接入计划/stage3-lingyun-control-static.mqtt.ndjson
python server/scripts/reply_lingyun_control.py
```

处置执行须绑同族设备：COUNTERMEASURE/JAMMING→`S85F1`，DECOY→`S85Y1`，DISPERSAL→`S85B1`。回执脚本订阅控制主题并回 `code=0`；Java 不对 replay 伪造成功。

雷达 TCP 航迹提升（P4-A）由 `app.fusion.live-promotion.enabled` / `APP_FUSION_LIVE_PROMOTION_ENABLED` 控制，默认关。打开后每条 `UPLOAD_TRACK_V3` 航迹批另写一行 `live-radar:<source_code>` 信封；不写融合业务表，打开开关也不等于客户现场雷达联调完成。

协议 B 控制：`POST /api/v1/devices/{id}/commands/lingyun-control`，经已有 MQTT 会话发布 `bridge/{provider}/device_control/...`，回执主题 `device_control_resp`。诱骗 `dec`、干扰 `ifr`、驱鸟炮 `bsc` 已按附录开通，须与绑定类型同族。急停接口固定返回「设备协议未提供」。

四通道原生 TCP：`POST /api/v1/devices/{id}/commands/countermeasure-4ch`，`action` 为 `CHANNEL_ON` / `CHANNEL_OFF` / `SET_MASK`（`mask` 仅 0/13/15）。调测和轮询只发 `0x10`。停止是全关 `SET_MASK 0x00`，不叫急停。`local` 且 `app.dev-seed.enabled=true` 时启动本机 `127.0.0.1` 模拟器并登记 `CM4-LOCAL`（`source_mode=live`，`simulated=true`，CIDR `127.0.0.1/32`）。不登记现场 `192.168.0.7`。配置 live 不等于现场射频联调完成。不用协议 B 接管四通道。

目标/轨迹只读接口为 `GET /api/v1/targets`、`GET /api/v1/targets/{target_id}`、`GET /api/v1/targets/{target_id}/tracks` 和 `GET /api/v1/tracks/{track_id}/points`。这四个接口均要求 `target:read`，并使用账号的组织/区域数据范围；越权对象按不存在返回 404。响应 ID 为字符串，时间为 epoch 毫秒，坐标仅在存在可信 WGS-84 位置时输出。

## 测试

以下命令都在 `server/` 执行。`AuthApiTest`、`SystemManagementApiTest` 和 `DeviceOperationsApiTest` 启用 `test` profile，使用 H2 PostgreSQL 兼容模式；不会依据 Docker 是否启动自动切换数据库。普通 `./mvnw test` 会发现这些测试。

Windows PowerShell：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

Linux/macOS：

```bash
./mvnw test
./mvnw package
```

核对 `target/surefire-reports` 中的实际用例数、失败及跳过。H2 测试不替代 PostgreSQL/PostGIS 的 SQL、空间查询、锁、约束和迁移验证；新增相关功能时，在隔离真实数据库中补充验证，不连接生产库。

PostgreSQL/PostGIS 回归只在显式提供隔离数据库时运行。迁移回归只创建和删除随机的 `stage2_compat_*` schema：

```bash
POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:5432/<isolated-db>' \
POSTGRES_TEST_USER='<isolated-user>' POSTGRES_TEST_PASSWORD='<isolated-password>' \
./mvnw -Dtest=PostgresStage2CompatibilityTest test
```

如果隔离数据库不是由 `postgis/postgis` 镜像作为默认数据库创建，须先在该隔离库执行 `CREATE EXTENSION postgis`。目标查询回归使用同一组环境变量，但为防止误连只接受名称匹配 `stage2_target_verify_*` 的数据库，并且只创建和删除随机的 `stage2_target_api_*` schema：

```bash
POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:5432/stage2_target_verify_<suffix>' \
POSTGRES_TEST_USER='<isolated-user>' POSTGRES_TEST_PASSWORD='<isolated-password>' \
./mvnw -Dtest=TargetReadPostgresApiTest test
```

## 约定

- 服务端执行设备动作授权；业务数据访问仍按账号已有范围过滤。用户管理不再维护或展示数据范围，新建用户内部固定为 `ALL`，调整角色时不改写已有范围。
- 成功状态及审计保持一致，失败尝试也须可靠留痕。当前审计 Mapper 只有 INSERT，不代表完整防篡改方案已完成。
- 生产禁止公网依赖；真实部署网络按确认资料配置。live 来源默认停用，显式启用前逐台校验 TCP 配置、协议配置、凭据引用与 CIDR 白名单；任何连接失败都不得自动降级为 mock。`APP_LIVE_DEVICE_ENABLED=false` 可整体关闭 live 连接监督器，但不能把 live 数据改标为模拟成功。
- 当前本地证据目录适配只用于开发测试；真实文件、元数据、哈希、下载授权和保管策略随业务切片建设。
- 启动配置、默认账号、消息投递与测试发现等差距见[开发基线](../docs/后端开发基线.md)。

### 本地空域风险追踪演示（2026-09-15）

`LocalAirspaceRiskDemoSeeder` 仅在 local 且 dev-seed 开启、已有 `demo-airspace-risk-20260915` 样例时运行，幂等补齐八条风险的目标关联。目标位置/高度/观测时间沿用演示风险快照，不生成实时感知结论，不改变核验状态或历史空间事实。两个已有组织/区域各登记一台专用 replay 光电（`LOCAL-ASR-EO-001/002`），只连接本机 127.0.0.1:1883；`LocalRiskVideoEoSimulator` 按设备分别维护跟踪状态并返回 Protocol C 回执。原模拟视频设备继续支持。没有这些样例或在 production 环境不会补数据。


2026-09-15补迁事件急停、计划备案事实、待执行计划设备预检和风险多类型筛选；需追加Flyway 202609150002–150004，旧迁移保持不变。详见[剩余迁移清单与验证记录](../docs/旧后端剩余改动迁移清单-20260915.md)。


### 无人机短信劝离与现场核查（2026-09-15）

`GET /api/v1/uav-events/{eventId}/advisory` 返回当前版本、短信渠道模式、模拟接收对象、`can_write`、`can_request_counter`、`can_handoff`、阻断说明与按事件版本升序排列的记录。
`POST /api/v1/uav-events/{eventId}/advisory/actions` 使用 `Idempotency-Key`、`expected_version` 和 `kind`：

- `SMS_SIMULATED`：接收对象 `recipient_name`、联系依据 `contact_basis`、劝离正文 `content`。仅 local/test 且事件来源 mock/replay 可调用，明确保存 `simulated=true`、`delivery_status=SIMULATED_DELIVERED`。不采集真实手机号，不发送真实短信。`AdvisorySmsPort` 是后续正式渠道接入边界，live 未接入时返回 `SMS_CHANNEL_UNAVAILABLE`，不回退模拟。
- `CONTACT_RECORDED`：同样三个字段，登记人工联系事实；没有短信送达含义。
- `OBSERVATION`：`outcome` 为 DEPARTED/STILL_INSIDE/UNKNOWN，`danger` 为 HIGH/MEDIUM/LOW/UNKNOWN，必须有 `note`。`urgent=true` 仅在仍在范围内且危险度高、现场依据至少20字时可登记，仍须按既有规则申请有效授权。

读取需 alarm:read 与事件范围；写入还需 alarm:verify、handoff:create 且事件已确认。事务锁事件、校验版本、追加记录与审计并递增事件版本；相同键相同请求重放既有响应，不重复记录。记录中的手机号脱敏，不在材料中携带手机号。

普通反制/干扰新申请要求最后一次联系后的核查明确“仍在范围内且危险度高”。未知、已离开、风险降低或新增联系后尚无核查均阻断；紧急核查可先申请。执行前再次检查新记录；没有劝离记录的存量授权维持原有兼容。既有自动关联干扰链保留，但也须经过最新核查条件。
处罚移送不再要求已完成反制，仍校验事件属实、版本、操作者和接收方权限。v2 材料增加 `advisory_records`，冻结提交时联系/核查事实；旧快照缺少该字段时保持兼容，读取按原事件可见范围裁剪。

### 2026-09-15 拉取时的迁移版本兼容

本地开发库已执行 `V202609150005__uav_event_advisory.sql`，故保留该脚本及历史记录。此次远端新增的设备感知脚本尚未在本地执行，从 `V202609150005__device_sensing_profile.sql` 顺延为 `V202609150007__device_sensing_profile.sql`，内容不变；风险排除脚本保持 `V202609150006`。本版本沿用本地开发库的迁移历史。向其他环境发布前须核对各环境的 Flyway 历史，不能直接覆盖已执行的版本。


### 后台自动短信（2026-09-16，本地模拟策略）

`app.advisory.auto-sms.enabled` 默认关闭，local 配置默认打开；实现再次校验 local/test 环境。策略 `LOCAL_AUTO_SMS_DEMO_V1` 是演示配置，不代表正式监管阈值：目标/研判120秒、事件及人工确认300秒，可分别用 `fresh-seconds`、`event-seconds` 配置。后台10秒轮询，关闭页面不影响执行；GET 绝不发送或创建任务。

触发来自精确关联事件的最新 ACTIVE/FRESH/ILLEGAL 规则结果且无未知原因，**不要求全部先人工核实**。有最新规则结果时，人工确认不能覆盖后来合法/未知/过期的结果；完全没有规则结果时，仅近期人工确认+新鲜明确UAV观测可作为退路。误报、待补证、已飞离、现场未知、目标或事件过期、没有精确关联依据均阻断。来源 live 没接正式渠道时显示不可用，不模拟成功。

自动发送使用独立 SYSTEM 执行主体，actor_id 不借用任何用户，记录 trigger_mode=AUTO、policy_code；渠道返回明确 SIMULATED_DELIVERED 才追加送达记录并递增事件版本。后台不会修改人工核实状态、批准或执行反制、作出处罚决定。新的联系记录仍使之前的观察失效。

新增持久化自动任务，唯一 event_id 和稳定 provider_key 防重复，事件行锁串行领取；渠道调用在数据库事务外，失败/超时状态可回读。租约失效不自动冒进重投，需在触发条件仍满足时补发，沿用同一渠道幂等键。正式短信适配器后续必须按该键去重并接可信送达回执。

GET `/api/v1/uav-events/{id}/advisory` 新增 `auto_sms`：enabled、status、reason、triggered_at、updated_at、can_retry、attempt_count、policy_code、trigger_source、evaluated_at、data_updated_at。判定/观测时间和任务更新时间分开。状态包括 DISABLED、WAITING、SENDING、SIMULATED_DELIVERED、FAILED、UNAVAILABLE、BLOCKED；SENDING/已送达保留事实，其他状态按当前阻断条件显示，仍为纯读取。

POST `/api/v1/uav-events/{id}/advisory/auto-sms/retry`，请求 `{expected_version,note}` 和 Idempotency-Key；复用 alarm:read、alarm:verify、handoff:create 与事件范围，校验版本并只排队，发送仍由后台执行。相同请求重放不重复排队。旧人工短信/联系记录及处罚快照保持兼容。


### 研判轨迹航线对照（2026-09-16）

`GET /api/v1/legality-evaluations/{evaluationId}/trajectory` 复用轨迹响应（availability、target_id、gap_millis、param_status、points、note）。按可见研判钉住目标、航线版本及 min(as_of, evaluated_at)，不使用计划最新研判。点只取实测，携带 corridor_relation 与 break_before；原始轨迹退路需核对目标归属。使用既有研判/目标/航线权限及 PostGIS 距离；无航线时 UNKNOWN，不生成新结论或改观测。前台合法性页消费该接口，管理端无直接消费者。原计划轨迹接口保持兼容。

### 最新研判列表查询优化（2026-09-16）

`GET /api/v1/legality-evaluations?latest_only=true` 将目标和计划的最新记录检查拆成两条等值关联，避免把同类全部历史互相比较。保留原有权限范围、筛选、时间与 ID 尾键、空主体引用语义，以及历史记录；接口结构、研判频率和前台超时不变。没有新增索引或迁移。实现与验收见[查询优化记录](../docs/最新研判列表查询优化-2026-09-16.md)。


### 电话录音通知（2026-09-16，默认关闭）

在无人机事件 advisory 增加独立电话录音通知，与短信按渠道各自防重；两者共享当前目标、规则结论、观测/事件时效及人工联系阻断规则。电话录音按“自动外呼播放预录音频”实现，本轮仅提供明确的本地模拟状态回执，不拨号、不实际播放文件，不代表任何人接听或听取。

- `app.advisory.auto-voice.enabled` 默认 `false`，没有修改 local 配置自动启用。开启后仍必须是非 production 的 local/test 环境、mock/replay 事件来源，且同时配置 `recording-id`、`recording-name`、`recording-path`、`recording-transcript`。
- `recording-path` 仅由部署配置提供已有 WAV 文件的绝对路径，文件上限 10 MiB；校验实际音频帧完整性并从真实文件计算 SHA-256。没有音频、不完整/截断文件、缺文稿或真实渠道不可用均不会生成模拟成功。没有新增录音上传或管理页面。
- `GET /api/v1/uav-events/{id}/advisory` 追加 `voice_mode` 和 `auto_voice`。`voice_mode=SIMULATED` 也用于已有模拟尝试的来源标记，不表示当前仍允许发起；当前启用状态用 `auto_voice.enabled`。无配置且未尝试时为 `UNAVAILABLE`。
- `auto_voice` 字段为 `enabled,status,reason,triggered_at,updated_at,can_retry,attempt_count,policy_code,trigger_source,evaluated_at,data_updated_at,recording_id,recording_name,answered_at,playback_completed_at`。缺少接通/播完证据时相应时间不返回。状态为 `DISABLED|WAITING|CALLING|SIMULATED_PLAYED|FAILED|UNKNOWN|UNAVAILABLE|BLOCKED`。
- `POST /api/v1/uav-events/{id}/advisory/auto-voice/retry` 使用 `{expected_version,note}` 和 `Idempotency-Key`；校验 `alarm:read`、`alarm:verify`、`handoff:create` 与事件范围，只对明确 `FAILED` 且当前条件仍满足的任务排队。`UNKNOWN`（包括接通但播完未知、调用异常、租约失效）不允许盲目重拨。既有任务不允许用同一幂等编号换录音内容重试。
- 完成结果仅在模拟回执明确确认接通和播完、时间顺序合法时追加 `VOICE_SIMULATED/SIMULATED_PLAYED` 联系记录。语音记录与短信/人工联系/观察按事件版本合并，后续交接材料自然包含已完成电话记录；已冻结材料保持原样。电话完成不代表飞离、危险解除、反制授权或处罚办结。
- 关闭策略、后续数据过期或录音移除不会覆盖已有接通/播放结果和模拟来源；关闭策略后后台仍会把过期 CALLING 标记为 UNKNOWN，但不会新呼叫。读取页面不会建任务或发送通知。

迁移仅追加 `V202609160002__automatic_advisory_voice.sql`。详细边界、代码清单和隔离验收记录见[电话录音通知实现与验收](../docs/电话录音通知实现与验收-2026-09-16.md)。管理端 `ruoyi-ui/src` 未发现该 advisory 契约消费者；业务前台同步展示双通道。

## 2026-09-17 合法性自动判定分流

每次规则研判新增证据充分性算法 `EVIDENCE_SUFFICIENCY_V1`，将算法版本、SUFFICIENT/INSUFFICIENT/NOT_APPLICABLE 与原因随研判 INSERT 冻结。前台消费 `decision_assurance` 决定是否显示人工复核，`needs_review` 在服务端分页前筛选；原风险分类、权限和复核历史保留。迁移 `V202609170020` 与 PG `V202609170020.1` 需随本版本应用，旧记录新字段为空时返回 UNAVAILABLE，不补造历史结果。该算法提供规则证据充分性，真实样本统计准确率尚未验证。详见[规则引擎接口](../docs/backend-stage7/rule-engine-api-contract.md)。


## 2026-09-17 直接反制权限

后台角色管理的“处置授权”增加“直接反制（免逐次审批）”，默认无。`disposal:direct` 仅显式 OP 有效，超级管理员不自动继承。前台告警详情按服务端能力显示直接反制/申请反制；DIRECT 记录明确操作人、有效期、执行状态和受阻原因，无审批人。追加迁移 `V202609170030`，需随后端重启应用；没有自动给真实账号授权或操作设备。接口和现有规则边界见[处置授权 API](../docs/backend-stage13/disposal-authorization-api-contract.md)。

## 规则配置管理（2026-09-17）

新增 `/api/v1/automation-rule-groups/{category}` 配置接口及追加迁移 `V202609170040`。三类配置独立保存、版本校验、幂等写入及审计。`V202609170050` 接入后台持续判定与 `/runs` 运行记录；`app.automation-rules.enabled=true` 启用判定，状态由调度心跳提供。当前部署能力仅判定与留痕，不自动派发反制、通知或跟踪动作。原处置预案 API 和人工动作保留。详见[规则判定运行说明](../docs/规则判定引擎接入-2026-09-17.md)；原配置阶段见[规则管理接口与验收](../docs/规则管理实现与验收-2026-09-17.md)。

2026-09-18：`GET /api/v1/legality-evaluations` 新增可选布尔筛选 `needs_attention`。为 true 时返回系统结论 `UNDETERMINED` 或既有 `needs_review=true` 的并集；同一条不重复计数，权限、目标类别、最新记录、分页及 total 共用数据库谓词。其他筛选继续取交集，为 false 时返回该并集的补集；不改变判定结果、人工复核状态或历史记录。业务前台默认待处理队列使用此参数，管理后台原调用不传参时不受影响。

## 单目标 MQTT 通知测试（2026-09-18）

在 `server/` 运行 `python scripts/mqtt_notification_scenario.py --seconds 900`，会生成新的唯一批次，配套独立模拟区域和 MQTT 连接、一条计划、一名模拟飞手、雷达与 TDOA 模拟设备，以及有限有效期的模拟禁飞区。两类设备每两秒上报同一目标的连续观测，TDOA 同时提供模拟飞手位置；计划高度采用 AGL，与报文 height 对应。只追加场景前置资料；研判、告警、短信任务及模拟送达由后端产生。账号沿用现有工具的环境配置，仅允许 localhost API 和本机 replay broker；不接通真实短信，不启用电话或反制设备。

输出保存在系统临时目录 `uav-notification-scenarios/<batch>/`，含原通知配置、前置资料、关联 ID、发布报文和停止时间。同一批次不可覆盖重跑；停止上报不代表目标飞离。临时 MOCK 短信配置和模拟飞手均有有效期；已有其他新鲜告警或真实短信渠道时工具拒绝修改共享配置。若准备中断，应先按批次检查已有资料，不能重复覆盖历史。

尚在原有效期内的已准备批次，可使用 `--batch <原批次> --resume` 恢复发布，沿用原运动时间基准并记录恢复时间；不重新准备资料、不刷新配置有效期或旧告警时间。过期批次须新建。

本地配置使用 4 个调度线程、融合每批最多 5 帧和 `app.fusion.prioritize-fresh-sources=true`，避免历史回放阻塞 MQTT 续租及新来源消费。优先级依据来源最近 120 秒内是否有报文；来源内部仍按接收顺序消费，旧来源队列不删除。此选项默认关闭，仅 local 显式启用；不是放宽通知时效、认可目标仍在场或忽略陈旧观测。已核实事件与通知资格分开展示。

同级/降级归并产生的新研判现关联到归并发生时已有的告警，供短信、电话和接收飞手资格校验复用。`alarm_outcome.kind` 仍为 MERGED/DOWNGRADED，成员表的 `alarm_id` 仍只表示本次新建；告警数量、原发生时间、通知时效和历史研判不修改。读取研判 `alarm_id` 表示有关联告警，不应据此统计本次新建数量。

### 2026-09-18：停用无人机人工现场记录

- `POST /api/v1/uav-events/{id}/advisory/actions` 不再接收 `kind=OBSERVATION`，返回 400 / `MANUAL_OBSERVATION_RETIRED`；历史只读查询、通知记录及冻结材料保留。
- 旧人工观察不再阻断短信/电话，也不再决定列表进度或反制资格。通知仍检查原时效、当前规则结论、接收对象和独立渠道回执。
- 反制申请、执行、排队下发及续链统一读取当前系统依据：已核实事件、当前 UAV 观测、有效 C03.fresh_seconds、关联本事件的最新 ACTIVE / ILLEGAL / FRESH 研判、SUFFICIENT 充分性及空未知原因。无依据或过期时明确阻断，删除空历史兼容放行。
- 权限、审批、设备范围、授权时间窗及急停后的设备停机核查保持；此变更未实现自动飞离解除、自动反制或自动处罚。

### 运行统计业务数据接通（2026-09-22）

`/api/v1/stats/operations`、CSV 及旧式报表预览/XLSX 已统一读取实际 target、punishment_case 和当前设备台账；旧 report_* 样本表及历史保持原样。新增目标按首次发现归属，非法/高风险为生成时状态，处罚只使用有效决定，未知数值不补零。新增 `generated_at`、指标 `availability`，各源读取权限与数据范围分别校验，设备复用现有台账权限范围。界面/导出口径和隔离测试入口见[运行统计契约](../docs/运行统计接口契约.md)。本项无结构迁移。

### 统一目标视频查询（2026-09-22）

新增只读 `GET /api/v1/targets/{targetId}/video`，复用目标读取、devices.op 与关联设备业务范围。明确模拟的当前跟踪任务必须取得匹配的 Protocol C 成功回执才能返回 SIMULATED_CANVAS；真实流继续返回 NOT_INTEGRATED。接口不创建任务、不下发动作、不生成证据。视频状态、错误语义与验收入口见[目标视频查询契约](../docs/目标视频查询接口契约.md)。本项无结构迁移。
