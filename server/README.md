# low-altitude-server

2026-09-30：`local,qa` 默认开启飞行计划时段推进，每 60 秒扫描一次，启动后自动补处理已过期的待执行/执行中计划；结束时间到达后记为“已完成”，已取消计划不变。这仅表示计划时段结束，不代表已确认实际起降或飞离。固定状态测试可显式设置 `APP_FLIGHT_STATUS_ADVANCE_ENABLED=false`；普通 local 与生产默认值不变。

2026-09-30：`local,qa` 联合测试覆盖层启用自动短信、自动语音和本机四通道 QA 设备准备；普通 `local` 保持原默认值。自动通知仍按事件、观测、飞手及接收端配置逐项校验，`POST /api/v1/local-interface-simulator/bindings` 连接实时接收端时会把已有且启用的过期 QA MOCK 通道切换到数据模拟器通道，不修改联系人。旧 `app.qa.notification-setup.enabled` 入口不必为此开启。相同单位和区域的模拟计划可重复调用 `countermeasure-device` 并取得原 `QA-LOCAL-CM4`，不同范围或本机连接配置冲突时返回 409。

2026-09-29：合法性页取消独立“异常”结论。新行为违规在证据充分时判非法并沿用告警合并流程，依据不足时不可判定；历史异常只在读取层归入不可判定，通过 `original_legal_status` 保留原始结论，不改旧记录。见[合法性三类结论](../docs/合法性三类结论-2026-09-29.md)。

2026-09-29：合法性研判使用融合后的目标类别；明确类别冲突保持未知，类别变化可在位置时刻不变时触发重新研判。有无计划的无人机仍按原规则处理。新研判保存当次类别快照，历史不回填；旧记录在类别变化后只读。按用户后续要求，`local` 默认开启自动融合和自动研判，后台持续处理有效的新鲜无人机观测，不依赖页面打开。真实目标遇到未确认规则参数仍为不可判定；不启用演示数据、启动回放或其他自动处置。实施与验证边界见[统一目标类别](../docs/合法性研判统一目标类别-2026-09-29.md)。

2026-09-28：补齐业务前台已使用的 `/api/v1/evidence-ledger` 只读接口，统一录像、图片、轨迹及设备指令的台账、筛选、统计、精确详情、关联材料和 CSV。复用现有文件权限与保管规则，不迁移或重写历史记录；原 `/evidence-files`、`/evidence-chains` 保持兼容。见[接口修复说明](../docs/证据台账接口修复-2026-09-28.md)。

### 2026-09-28 第十轮续测修复

- PDF 预览支持 `page`（从 1 开始），返回 PNG 和 `X-Pdf-Page-Count`；越界返回 `EVIDENCE_PAGE_OUT_OF_RANGE`。鉴权、来源范围、原件校验和审计仍适用，下载保留原始 PDF。
- 运行统计及 CSV 支持可选 `owner_org_id`，单位选项只包含当前可见范围；无效或不可见单位拒绝，不静默扩大至全单位。
- 合法性读取新增 `alarm_verification`；仅共享同事件、同范围且不早于研判的核实，已共享核实记录不重复进入待复核队列，不回写原研判结论。
- 注册已被其他（包括退役）设备占用的身份返回 409 `DEVICE_IDENTITY_CONFLICT`，失败事务不留下新设备/来源。
- `app.fusion.max-future-skew-millis` 默认 30000，允许 0～300000。观测时间超过入箱接收时间与该偏差之和时，以 `OBSERVATION_TIME_IN_FUTURE` 记录处理失败，不写目标/轨迹/融合结果；原始入箱消息保留。历史回放沿用原时刻，不改系统时钟，也不自动清理旧污染样本。
- MQTT 入口在推进工参及感知水位前同样拒绝未来时间，并保留拒绝诊断。协议 A `workState=2` 显示 `ABNORMAL`；异常状态心跳超时仍转 `OFFLINE`，恢复心跳不补造 `GOOD` 健康结论。协议 C 的状态码不受此映射影响。
- 审计列表/详情返回 `module_name`、`action_name`，保留 `object_type`、`object_id`；维护动态动作及证据预览/缩略图均有中文标签。

本机完整回归期间使用 `target/qa-runtime/qa-server.jar` 独立运行副本，防止编译触发开发热重载中断业务。Windows Maven 测试如需本机 Unix socket/临时资源，使用已有可写 ASCII 临时路径并同时设置 `java.io.tmpdir` 与 `jdk.net.unixdomain.tmpdir`；不修改全局系统临时目录。普通测试的 PostgreSQL 条件跳过必须单列，相关 SQL/约束另在隔离 PostgreSQL 验证。

2026-09-28：人工核实接口 `POST /api/v1/uav-events/{id}/verifications` 的 `note` 改为可选字符串，省略或空白时记录为空字符串，提供时仍限制为 1000 字。前台移除核实说明输入；结论、版本、权限、幂等和核实历史保持原有约束。

2026-09-28 联合测试：可显式使用 `local,qa` profile 启用融合、研判和仅规则目录准备，管理员通过原接口激活 DEMO 规则。此模式不生成业务夹具、不启用自动短信、语音、反制或移送。新增四类材料台账接口与运行边界见[联合测试阻塞修复](../docs/联合测试阻塞修复-2026-09-28.md)。普通 `local` 默认设置保持不变。

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

2026-09-29：目标列表新增可空 `map_expires_at`，按最新观测时间加当前融合配置 `identity.terminate_after_ms` 计算；没有观测时不返回。大屏地图目标透传 `observed_at` 与 `map_expires_at`，前台据此移除过期实时点。该只读展示契约不删除目标、告警或风险，不推进飞离、解除或处置状态。

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

2026-09-21 起，告警事件建立后即自动发送一条，不再检查核实结论、合法性研判、目标类型、观测或事件时效。同一事件只自动发送一次。只发给已核验的执行飞手；没有飞手、联系方式未核验或名册不可用时，短信和电话都不发送，不使用演示飞手或单位联系人。本地或测试环境对 mock、replay、live 都调用模拟通道，回执保持 `simulated=true` / `SIMULATED_DELIVERED`。生产环境策略关闭，不发送，也不把模拟回执写成真实送达。电话录音在执行飞手之外仍使用原资格校验。

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

2026-09-23：三类规则只服务前台告警到处罚，不控制人工按钮。核实规则全部满足后自动核实属实。反制规则全部满足、事件已到待反制，且证据、急停和唯一可用设备都允许时，自动发起一次直接反制，不写审批人。干扰完成后，通知处罚规则全部满足才自动通知处罚部门；未满足时交接仍在，前台「通知处罚部门」可以手动发送。本地预置每类两条启用规则。

2026-10-03：执行引擎默认打开，`app.automation-rules.enabled` 缺省为 true，环境变量 `APP_AUTOMATION_RULES_ENABLED=false` 可关闭。替代上一段「生产环境默认不启用该引擎」。没有启用规则的类别仍暂停。重启后端后生效，不需要新的数据库脚本。

2026-09-29：自动规则主体发起的直接授权，在排队实际发送和反制后的干扰续链前重新检查当前反制规则。引擎关闭、条件停用、运行记录缺失或版本变化、时段不符、当前事实未知/过期/不满足时停止推进；规则读取异常同样阻断。人工直接授权保持现有权限与范围检查，不受自动规则启停控制；停止命令继续走原安全通路。

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


2026-09-22：合法性研判查询新增可选 `has_alarm` 布尔筛选，按引擎、合并成员与人工转告警历史关联，在分页前统一过滤列表和统计；业务前台用于“核实位置”。既有调用省略参数时不变，管理前端无此接口消费者。详见规则引擎接口文档的“核实位置筛选”；不改变核实状态和权限。

### 运行统计业务数据接通（2026-09-22）

`/api/v1/stats/operations`、CSV 及旧式报表预览/XLSX 已统一读取实际 target、punishment_case 和当前设备台账；旧 report_* 样本表及历史保持原样。新增目标按首次发现归属，非法/高风险为生成时状态，处罚只使用有效决定，未知数值不补零。新增 `generated_at`、指标 `availability`，各源读取权限与数据范围分别校验，设备复用现有台账权限范围。界面/导出口径和隔离测试入口见[运行统计契约](../docs/运行统计接口契约.md)。本项无结构迁移。

### 统一目标视频查询（2026-09-22）

新增只读 `GET /api/v1/targets/{targetId}/video`，复用目标读取、devices.op 与关联设备业务范围。明确模拟的当前跟踪任务必须取得匹配的 Protocol C 成功回执才能返回 SIMULATED_CANVAS；真实流继续返回 NOT_INTEGRATED。接口不创建任务、不下发动作、不生成证据。视频状态、错误语义与验收入口见[目标视频查询契约](../docs/目标视频查询接口契约.md)。本项无结构迁移。

### 模拟通知内部闭环（2026-09-22）

短信发送租约超时、适配器异常/空响应或无法确认的结果统一持久化 UNKNOWN；调度和人工 retry 均不盲目重投。此规则替代此前“超时核查后补发”的旧短信描述；明确 FAILED 仍按既有资格及幂等键重试。短信与电话历史结果独立于当前配置和时效。通知目录模拟适配器未知结果保存为 SUBMITTED/PENDING + DELIVERY_OUTCOME_UNKNOWN；计划反馈和通知上级前台如实显示，既有防重关系保留。配置诊断检查后台任务开关和实际 WAV 录音，不再只看目录是否填写。追加迁移 `V202609220001__automatic_sms_unknown_result` 仅扩大短信状态约束，不重写历史。隔离测试及五类链路边界见[模拟通知内部闭环](../docs/模拟通知内部闭环-2026-09-22.md)。正式通道与正式业务录音未接入；本批没有修改运行中开发库配置。


2026-09-23 合法性读取优化：新增 `/api/v1/legality-evaluations/summary`，单次条件聚合替代业务前台五次串行列表统计，共用原列表权限、筛选及最新记录规则。无需数据库迁移，不修改历史。需同步部署业务前台及本后端，详细字段见 `../docs/backend-stage7/rule-engine-api-contract.md`。

### 飞行计划当前风险读取（2026-09-23）

2026-09-29 后续实现：按已确认规则，C04 仅在新的可信实测位置明确离开原风险范围时保存独立解除事实；通知、签收、停报不解除。既有 C04 评估链追加 `risk_clearance_evidence`，读取不写。所有风险列表/详情新增 `current_status/current_reason/current_observed_at`，与当前风险列表同源；已保存 CLEARED 移入历史，后续观测过期不复燃。C05 与缺权威后续依据的类型仍 UNKNOWN。本条替代下段“其他类型暂缺持续／解除契约”中 C04 的旧限制，详细边界和本轮验证计划见 [C04 风险解除证据](../docs/designs/2026-09-29-risk-clearance.md)。

`GET /api/v1/risks/current` 必填 `plan_id`，支持 `page`、`size`、`exclude_demo_samples`；先检查 `risk:read` 与 `flight:read`，复用原风险数据范围。响应包含 `items[{risk,current_status,current_reason}]`、`total`、`current_total`、`uncertain_total`、`as_of`。在只读事务中先按持续依据分类再分页，不以发生时间或通知状态替代风险持续性。EXCLUDED 不列入；气象有效期内 CURRENT，未到有效期不列入，过期及缺少持续依据 UNKNOWN。其他类型暂缺持续／解除契约，显示 UNKNOWN，不新增人工必经步骤。旧列表、历史与通知权限不变。业务前台已接入；管理端没有该接口的直接消费者。RiskReadApiTest 覆盖旧记录、通知独立性、排除、过期、未到期、50条跨页及权限。

## 2026-09-23 五组 MQTT 样本续验

融合管线现保存单源最近观测快照，在既有新鲜度内组合异步来源；无关设备帧不再清空新鲜观测，迟到帧不回退快照。规则引擎为融合目标读取 target_attribute_selection.identity_clue，使计划匹配与融合身份一致；组织和区域范围保持不变。没有新增接口字段或数据库迁移，旧快照没有 source_estimate 时不补造。

本机最终批次 sim-0923035711-e377 已经实际 MQTT、规则、告警、模拟双通道通知、申请审批、本机 CM4 协议回执、关联干扰和自动处罚交接，送达及签收均为 MOCK。运行期间显式启用 app.advisory.auto-voice.enabled、app.rule-engine.c04.enabled 和 app.disposal.receipt-sync.enabled，并配置既有模拟录音；不是生产默认启用。临时通知设置已恢复，停止后的观测失效会阻止新反制，历史送达结果保留。未验收真实射频、短信、电话或处罚决定。

18 项受影响测试、package 和隔离 PostgreSQL 临时表查询验证通过。完整样本分支、重启/重新登录证据、前一批次运维结果和缺少 BVLOS 严重度配置的边界见 [业务前台仓库续验报告](../../dongyiwurenji/docs/交付/信号模拟器五组样本-20260923/全流程模拟验收.md)。


### 通知后的飞离观察只读查询（2026-09-24）

`GET /api/v1/uav-events/{eventId}/advisory/observation` 复用 `alarm:read`、事件数据范围和 `PilotDepartureWatch`。返回 `event_id/channel/status/presence/started_at/deadline_at/evaluated_at`；时间为 epoch 毫秒，未开始时可空字段按既有 JSON 规则省略。`status` 为 NOT_STARTED、WATCHING、ASSESSED；只有 ASSESSED 的 LEFT/STILL_PRESENT 表示明确观察结论，UNKNOWN 表示新位置不足或读取异常。短信送达后观察 3 秒，电话播放完成后观察 10 秒，复用各自窗口；读取不确认告警、不发送、不改事件或通知状态，不放宽反制资格。当前消费者为本机信号模拟器，业务前台和管理端既有 advisory 契约未改动。回归命令：`bash ./mvnw -Dtest=UavDepartureObservationTest,NotifyFlowTest,UavAdvisoryApiTest test`（22 项通过）。

### 上级空域下发（2026-09-27）

新增正式空域接收契约与 local/test 模拟入口，支持新增、版本更新、撤销及幂等回执；人工写入入口默认关闭，历史读取保留。正式入口必须配置来源与接收账号，尚未完成供应商联调。追加迁移仅建立接收记录及模拟来源元数据，不生成演示空域。真实业务仅查询 live 空域，模拟/回放业务可查询模拟空域。字段、权限、时间约束和启动说明见[上级空域下发接口](../docs/上级空域下发接口-2026-09-27.md)。

### 验收清理与测试视频（2026-09-28）

正式配置默认 live，禁用种子/回放/模拟适配；模拟环境必须显式 local+qa 或 test，且不能同时启用 prod/production。此限制替代上文仅 local 的旧启动说明。短信和录音电话不在本轮变更范围内。

可选媒体服务、两组独立凭据、HLS 授权转发与临时流登记见 [视频接入说明](../docs/designs/acceptance-cleanup/video-integration.md)。媒体二进制须提前放在内网，不依赖 CDN；默认关闭，测试视频不可作为现场证据。本轮后端与两端页面行为、实际验证及部署限制见 [实施记录](../docs/designs/acceptance-cleanup/implementation-progress.md)。

### 本地计划回放输入（2026-09-28）

`POST /api/v1/local-interface-simulator/plans` 支持可选 `source_mode`：省略时保持 `mock`，显式 `replay` 用于与回放设备进行同来源联测；`live` 和其他值返回 400。此字段仅决定新计划来源，不修改已有计划，不改变模拟环境限制、接口操作权限、幂等与审计。设备检查继续隔离不同来源；不需要开启开发种子数据。

可选 `status_code` 省略时为 `PENDING`，另支持 `EXECUTING`、`COMPLETED`、`CANCELLED`。执行中要求当前时间处于计划窗口，已完成要求结束时间不晚于当前时间；非法状态或时间返回 400。同一来源消息重放不能改写状态。此能力仅在受限模拟入口创建新计划，不修改旧计划、不启用全库状态推进，也不生成实际轨迹、研判或执行结果。2026-09-29 定向验证：H2 13 项、隔离 PostgreSQL 23 项、环境隔离 3 项全部通过。

### 研判参数缺项诊断（2026-09-29）

规则参数读取遇到缺项或数值格式错误时，返回 503 `RULE_CONFIGURATION_INVALID` 并指明参数键；不再仅显示内部错误，也不使用默认分数或写成业务“不可判定”。现有演示版本缺少 `C03.severity.BVLOS_EXCEEDED`，超视距触发评分时仍须由规则管理员确认并发布完整参数版本；本轮没有猜填权重或修改历史版本。21 项参数、决策、轨迹及异常处理定向测试通过。

### 光电自动追踪一期（2026-09-28）

后台按当前告警、合法性研判和非气象风险事实统一申请光电，同目标共用任务。新增只读状态、持久暂停和恢复接口；自动与人工共用设备占用、时效、范围、来源模式和回执检查。未确认的执行结果保留占用，不自动重试。当前协议支持 UAV/BIRD，UNDETERMINED 不因泛化信息缺口触发自动观察。业务前台三处复用公共状态及视频面板，管理端菜单不变。

自动开关解析 `app.eo-edge.auto-track.enabled`，兼容既有 `app.eo-edge.auto-track-enabled` / `APP_EO_AUTO_TRACK_ENABLED`；同时要求 MQTT 启用，默认继续关闭。新增 `app.eo-edge.position-max-age-millis` 默认 15000、`demand-max-age-millis` 默认 300000（最新研判），设备心跳沿用 `heartbeat-timeout-millis`（application.yml 默认 3000）。这些是工程时效，不是硬件标定承诺。启动迁移追加 008/009，不改已应用脚本。

接口见[统一光电状态契约](../docs/目标视频查询接口契约.md#2026-09-28-统一光电追踪状态与控制)，业务分工与本次实际验收见[一期方案](../docs/光电自动追踪一期方案-2026-09-28.md)。本地验证不代表真实光电及现场视频已接通。

### 单事件通知异常模拟（local+qa，2026-09-29）

仅在非prod/production且local+qa（或隔离test）、mock/replay来源下，可启用app.qa.advisory-scenario.enabled=true并指定event-id；默认关闭。sms-status允许SIMULATED_DELIVERED/SENT/FAILED/UNKNOWN，voice-status允许SIMULATED_PLAYED/NO_ANSWER/ANSWERED/FAILED/UNKNOWN。delay-ms为0至60000，仅延迟指定事件的模拟外部调用，不占业务事务。稳定通道key须与指定事件一致。没有新增供应商回调协议或手工改写业务结果接口。非法配置不回退成功；测完移除启动参数。

60项通知相关回归和package通过。持久化 BLOCKED 电话不因位置恢复而重新显示自动拨号；UNKNOWN 电话保留结果未确认，不启动播放后的观察。实际场景验收进度见第十二轮报告，不以单元测试代替页面/服务恢复。

### 气象风险测试输入（2026-09-29）

新增默认关闭的 `POST /api/v1/local-interface-simulator/weather-risks`，仅 local+qa/test 且显式 `app.weather-risk.qa.enabled=true` 可用。经已有计划范围与接口操作权限校验，保存带模拟标识、无目标的气象风险及不可覆盖的范围/时段快照；支持同消息幂等。与天气预报输入独立，到期按当前风险规则显示待确认，不自动认定风险解除。参数及验收记录见[本地QA气象风险输入](../docs/qa-weather-risk-input.md)。

### 隔离维护页面夹具

`DeviceMaintenanceBrowserFixtureTest#serveBrowserFixture` 是显式启用的人工浏览器测试夹具，默认跳过。它固定使用独立 H2 内存库、回环随机端口和模拟通知，禁止指向现有业务库；不增加生产接口，也不代表真实设备验收。

在 `server/` 运行：

```powershell
.\mvnw.cmd '-Dtest=DeviceMaintenanceBrowserFixtureTest#serveBrowserFixture' '-Dqa.maintenance.browser=true' test
```

启动信息写入 `target/maintenance-browser/manifest.json`，包含后端端口及待办/设备标识。管理前端可另开回环端口，通过 `ADMIN_API_PROXY_TARGET` 指向该端口，并将 `ADMIN_PUBLIC_ORIGIN` 设为测试页面 origin。测试账号沿用 `application-test.yml` 的开发夹具，通过正常登录进入页面。

向同目录 `control.json` 原子写入 `{"id":"每次不同的编号","scenario":"场景名"}`，等待 manifest 的 `command_id` 匹配后再操作页面。场景仅改变该内存库中的测试前置条件，维护流程仍由实际 API 推进：`HEALTHY`、`DISABLED`、`OFFLINE`、`BAD`、`DEGRADED`、`ALARM`、`UNKNOWN`、`PRE_REPORT`、`STALE`、`FUTURE`、`WRONG_SOURCE`、`WRONG_SIMULATED`、`OPEN_INCIDENT`、`EXPIRED_PASS`、`ACTIVE_COMMISSION`/`CLOSED_COMMISSION`、`ACTIVE_COMMAND`/`CLOSED_COMMAND`、`ACTIVE_TRACKING`/`CLOSED_TRACKING`、`LEGACY`、`DELETED_DEVICE`/`RESTORED_DEVICE`。`NEW_TASK` 通过创建接口准备下一待办；`STOP` 正常退出，最长运行 30 分钟。结束后关闭专用前端并保留证据，不能把夹具结果标成现场恢复。

需要真实空间设备检查时，使用 `DeviceMaintenanceBrowserPostgresFixtureTest#serveBrowserFixture`。它只接受数据库名以 `maintenance_browser_verify_` 开头的 `POSTGRES_TEST_URL`，凭据通过 `POSTGRES_TEST_USER` / `POSTGRES_TEST_PASSWORD` 注入。每次启动准备新的可丢弃 PostgreSQL/PostGIS 库及既有空间扩展；业务库不允许用于夹具。此版本把测试设备放到种子计划航线起点，并恢复真实设备检查服务；通知仍为模拟通道，控制文件和 30 分钟上限保持相同。已跑过页面流程的库保留证据，不重复运行创建夹具以免混入旧任务或通知配置。

补充场景：`NOTICE_FAILED`、`NOTICE_UNKNOWN`、`NOTICE_SUBMITTED`、`NOTICE_DELIVERED`、`NOTICE_NOT_SENT` 准备下一次正常通知请求的渠道条件并推进测试时钟 61 秒；请求仍通过页面或正常 API 提交。`NOTICE_LATE_RECEIPT` 仅向当前任务第 1 次通知添加带模拟标识的迟到回执展示数据，用于验证旧回执不会覆盖最新通知；它不实现或验收供应商回调协议。`TICK` 只推进时钟 1 秒。`HIDE_SCOPE` / `RESTORE_SCOPE` 切换任务所属单位的启用状态。

`SCOPE_EXACT` 把隔离 admin1 限制到当前任务的单位及区域组合；`SCOPE_CROSS` 配置两个各自合法、但不能拼成当前任务组合的授权；`SCOPE_NONE` 清空授权；`SCOPE_ALL` 恢复隔离账号全范围。这些场景只修改隔离库中的测试账号和设备范围，不改变真实账号。用它们核对列表、详情、操作及未读数。前置条件测试分别运行 `#verifyNotificationFixtureConditions` 和 `#verifyScopeFixtureConditions`，一次 Maven 调用仅选择一个夹具方法。

Windows 启动该随机端口夹具时，若用户临时目录导致 JDK 回环连接失败，先建立短路径 `target/qa-tmp`，并追加 `-DargLine="-Djava.io.tmpdir=E:/houtaiguanlii/server/target/qa-tmp -Djdk.net.unixdomain.tmpdir=E:/houtaiguanlii/server/target/qa-tmp"`。路径按实际 checkout 调整；不要更改系统临时目录。

PostgreSQL 夹具额外支持 `PLAN_DUE`：仅将隔离种子计划设置到当前执行时段、保留一个同源模拟传感器，并将种子旧设备异常放在计划窗口之前。随后可用 `HEALTHY` / `BAD` / `UNKNOWN` 比较“疑似未起飞”“设备异常”“资料不足”；前置条件回归为 `#duePlanDistinguishesNormalAbnormalAndUnknown`。这不是对业务库计划或设备事实的修正。

雷达待机页面夹具使用 `RadarStandbyBrowserFixtureTest#serveBrowserFixture` 和显式开关 `-Dqa.radar.browser=true`。固定独立 H2 内存库，HTTP 与雷达端口都只绑定回环随机端口。读取 `target/radar-browser/manifest.json`，将专用管理前端代理指向该 HTTP 端口，正常登录后选择“隔离只读待机雷达”，依次创建任务、连接、保存、开始。模拟器只响应登录、心跳和两个已定义寄存器的读取，不发送业务帧或控制写入；预期最终不可判定。结束时在 `target/radar-browser/stop` 写入任意文本，等待测试正常退出，并保存 `protocol-evidence.json`。最长 30 分钟；没有实际调测命令也会使最终断言失败，不将仅启动算通过。

同一隔离 PostgreSQL 浏览器库需要验证服务重启时，可附加 `-Dqa.maintenance.resume-task=<本库模拟待办UUID>`。仅允许 `maintenance_browser_verify_` 前缀库、种子计划及 simulated=true 的现有待办；不创建新待办、不刷新设备健康、不改已保存的恢复/完成结论。任务及历史保留供重登核对。

### 本机 MQTT 心跳页面夹具

`MqttHeartbeatBrowserFixtureTest#serveBrowserFixture` 需显式 `-Dqa.mqtt.browser=true`，并将 `POSTGRES_TEST_URL` 指向全新的 `mqtt_browser_verify_` 前缀 PostgreSQL/PostGIS 测试库；用户名、口令沿用上述环境变量注入。只在 test 配置启动，HTTP 与内置 MQTT broker 都绑定回环随机端口，无外部设备和通知渠道。生产的接入、融合、心跳过期与异常生成服务照常处理本机发布的模拟报文，未直接修改设备健康或异常结果。

端口和模拟设备身份见 `target/mqtt-browser/manifest.json`；专用前后台代理指向其 HTTP 端口。向同目录 `control` 写一个场景名：`RUN` 持续工参与目标流，`TARGET_ONLY` 仅目标流，`HEARTBEAT_ONLY` 仅工参，`PAUSE` 停报，`FAULT` 报协议已定义的工作异常，`OLD` / `FUTURE` 单次旧工参/未来工参后停报，`STOP` 结束。每秒最多一组报文，心跳超时使用实际 30 秒规则，最长 30 分钟自动停；结束会停用专用 broker。协议未提供健康字段时继续显示未知，不能把在线或已接收报文写成健康良好。页面流程由正常登录和 API 推进，夹具通过只证明隔离模拟传输及入库，不代表整条验收流程或现场联调通过。


### TCP 设备归属与连续航迹验证（2026-09-29）

统一 TCP 接入 `POST /api/v1/devices/onboard` 必须提交有效且在操作者范围内的 `owner_org_id`、`district_id`。雷达同时建立同 ID 标准设备与明确业务归属；单位、区域名称由目录解析。既有无归属设备可通过管理端正常编辑补齐，绑定后不得通过改名或编辑迁移归属；停用和逻辑删除同步停用标准设备。历史无归属目标不会回填为其他单位，新协议帧按新明确归属进入融合。四通道仅维护业务归属，不伪装为雷达目标来源。

`RadarLiveBrowserFixtureTest#serveBrowserFixture` 需显式 `-Dqa.radar.live.browser=true` 与独立 PostgreSQL URL `radar_browser_verify_*`。HTTP 和雷达端口仅监听回环，正常管理端登记 `QA-F31-TCP` 并创建、配置、启动调测。通过 `target/radar-live-browser/manifest.json` 读取端口，向同目录 `control` 写入 `RUN`、`HOLD`、`QUIET`、`CLOSE` 或 `STOP`。夹具只响应登录、心跳、工作模式寄存器和 RTK 查询，并发送持续 TCP 航迹；最长 40 分钟。它会显式标记该测试设备为 simulated，不能用于正式验收或真实射频动作。结束必须写入 STOP 并核对正常测试退出。原始 x/y/z 保留厂家坐标含义，未经验证的 RTK 不可替代 WGS-84/AGL 转换依据。

连续航迹回归需同时运行 `TcpMonitoringEventTest` 和 `TcpMonitoringPostgresTest`：同一目标连续帧保持一个目标/链路/航迹，增加不同帧的点，重复帧不得重复插入。PG 重复键不得被吞掉后继续使用已中止事务。

### 2026-09-29 排队指令授权窗口回归

普通审批与直接反制授权在设备启动指令实际发送前均重新检查当前状态和有效窗口：仅 APPROVED/EXECUTING 且已到开始时间、未到结束时间可继续。排队期间过期或转入终态的启动指令取消，不补造发送时刻或设备回执；四通道全关和凌云停止指令仍可进入安全停止流程。外部协议授权的既有兼容约定保持。

回归覆盖 H2 与隔离 PostgreSQL 的普通审批过期、未生效、终态、DIRECT 撤权/范围变化及急停。实际队列用例还验证重复调度不再下发、无发送时刻/回执，以及过期后全关指令可排队。


### 2026-09-29 设备故障与独立回执回归

四通道与凌云启动在入队及实际发送前检查启用、ONLINE 与明确 BAD 故障状态；排队后变化取消未发送启动，不生成发送时刻/回执。停止/全关保留原安全流程，UNKNOWN 不擅自等同明确故障。处置授权因设备故障受阻保留 DEVICE_FAULT 事件和授权历史，前台说明未下发；独立协议指令无关联处置授权时不运行授权结算。新增 V202609290001 迁移已在隔离PostgreSQL和当前本地业务库验证。


### 2026-09-29 处置设备占用

处置启动复用设备已有活动工作判据：未完成指令（QUEUED/SENT/ACCEPTED）、调测或跟踪任务存在时返回DEVICE_BUSY并保留授权及事件。执行与续链均在现有设备行锁下检查，跨事件、跨操作者不能同时入队启动；四通道全关和凌云停止不受该新增启动限制。新增迁移V202609290002保留独立占用原因，不归为设备故障。

### 2026-09-29 凌云迟到回执

已实际下发且随后超时或取消的凌云指令，收到通过 broker、厂商、设备类型、设备外部编号和 msgNo 关联校验的迟到回执后，以 PROTOCOL_B_LATE 保存到原命令。保留原状态、超时或取消原因，不再次触发处置结算、重发或自动续链；并发重复回执在原命令行锁下去重。未下发的取消指令不接纳此类回执，retained、非 QoS 1、身份不符继续拒绝。前台证据详情明确区分迟到成功、失败和原任务结论。

正常与迟到回执均校验完整设备来源；同一 broker 下其他厂商或设备类型即使外部编号与 msgNo 相同，也不能推进原命令或写入原命令回执。


### 自动核实与自动反制的当前判定校验（2026-09-29）

自动核实落笔和自动反制新建授权前，须确认传入的 automation_run_id 仍为本事件、本类别当前的 PASS 判定，并重算当前引擎、规则版本、时段、范围和观测条件。运行记录在核对期间变化则跳过本轮，不把旧 PASS 或其他事件的判定写入新核实历史。自动反制获取设备锁后再次核对；人工核实和人工直接授权入口维持各自原有规则。

验证：AlarmRuleVerificationTest/AlarmRuleVerificationPostgresTest、AlarmRuleCounterTest 与 AutomationRuntimeEligibilityTest；统计跨年边界用 ReportingApiTest/OperationsPostgresTest，浏览器隔离夹具 ReportingBoundaryBrowserFixtureTest 仅对显式 qa.reporting.boundary.browser=true 开放，使用 stage456_verify_* 随机 schema，样本不代表真实案件或处罚结果。
