# low-altitude-server

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

准备 JDK 17 和 Docker；用 Wrapper 固定 Maven 版本。开发端口为 API 8080、业务前台 5173、管理前端 5175。以下数据库必须是隔离开发实例，不使用生产库或已有业务库作试验。

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
#    否则适配器记 TRACK_NOT_OPEN；看态势需 APP_FUSION_ENABLED=true（默认关）
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
