# 光电自动追踪一期后端实施与验证

## 实施结果

已实现目标级共享调度、只读状态查询、持久暂停/恢复、人工补跟踪与既有结束接口兼容。自动能力由显式开关及 MQTT 能力共同决定；不因页面打开而启动。

- 告警待核实/补证贡献观察需求；已确认告警保守持续观察，复用现有位置/空间观察确认 LEFT 时移除对应需求，失联不等于飞离。
- 研判读取同目标最新 ACTIVE 结果及有效人工改判；当前 ILLEGAL/ABNORMAL 贡献需求，LEGAL/UNDETERMINED 不猜测视觉依据。最新研判观察依据默认 5 分钟有效。
- 非气象风险按实际 reason_code（偏离航线、空域冲突、进入走廊/机场区域等）及 HIGH/CRITICAL 等级选择；资料/身份/参数/高度基准缺失不单独触发，LOW 的仅邻近航线不触发。
- 当前目标位置默认 15 秒有效，未知类别明确阻断。绑定模式、设备心跳、已知空闲状态和主体范围均校验；不使用旧融合事件内的坐标下发。
- 所有自动/人工发起、暂停、结束及下发使用共同持久锁；候选先过滤业务需求并优先高风险，遍历候选直到填满批次；自动任务结束检查遍历全部，避免固定前 20 条造成饥饿。
- 指令发送前重新检查资格、人工发起人当前启用/权限/范围。先独立提交 SENT，再执行 MQTT 发布；SENT 不重发，发布结果未知及已发超时保留占用。有效迟到回执可对账；旧任务结束回执不关联新任务命令。
- 历史 FAILED 但曾发送且结果未知的任务在运行时保守占用目标和设备，不批量改写历史。显式暂停可在没有其他当前设备任务时提交旧任务停止；存在新任务则拒绝，避免误停。
- 每任务保存可信 EO 回报时间，回报过期显示 LOST。统一状态和视频不把指令排队当跟踪成功；视频存在与否仍独立。

## 代码变更说明

以下路径均相对仓库根目录；未恢复、提交或清理其他未提交工作。开始前保存了相关既有 EO 文件副本用于区分本次变更。

| 文件/类 | 方法/函数 | 方法作用及本次修改作用 |
| --- | --- | --- |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoAutoTrackService.java` | `scheduled/poll` | 只做调度入口，调用独立事务 Bean，避免自身调用绕过事务锁。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoTrackingScheduler.java` | `poll` | 共用需求、资格和锁，创建/停止 AUTO 任务，不抢占、不盲重试。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoTrackingPolicy.java` | `enabled/block/demand/visualRisk/bootstrap/deviceReady` | 能力开关、业务需求、时效/类别/模式、引导数据和未知历史阻断。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/infrastructure/EoTrackingRepository.java` | `snapshot/candidates/alarms/evaluation/risks/lock/pause/request` 等 | 当前事实、人工改判、持久暂停和请求幂等；候选优先级及设备范围。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoTrackingStatusService.java` | `status/control/view/checkTaskScope` | 状态与允许动作、暂停恢复、原任务停止、目标及设备范围复核。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/api/EoTrackingController.java` | `status/pause/resume` | 新增三条公共接口，保持 snake_case、ApiResponse、字符串 ID；原接口保留。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoManualTrackService.java` | `begin/availability/pickDevice/end` | 复用共享时效和占用守卫；人工 begin 不解除暂停；旧 end 持久暂停且核对当前任务。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoEdgeCommandService.java` | `enqueue/dispatch/prepare/timeout/operatorAllowed` | 结束去重、独立持久发送标记、下发前复核、未知结果保留占用、重启不重发。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/infrastructure/EoEdgeRepository.java` | `idleDeviceForMode/uncertainTaskByTarget/targetHasOpenTask/refreshBootstrap/trackingReport/freshTrackingReport` | 设备选择直接过滤有效心跳，旧未知任务隔离，保存任务 EO 时效水位。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/EoEdgeIngressService.java` | `tracking/completeBegin/endTracking/completeCommand` | 回报时间关联、迟到超时回执对账、结束失败不释放、不用旧任务结束新任务。 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/TargetVideoService.java` | `tracking` | 当前任务 EO 回报、目标位置及心跳过期时停止宣称 TRACKING。 |
| `server/src/main/resources/db/migration/V202609280008__eo_auto_tracking_control.sql` | 无具体方法 | 新增目标暂停、控制幂等表及任务来源；不修改旧迁移。 |
| `server/src/main/resources/db/migration/V202609280009__eo_task_report_freshness.sql` | 无具体方法 | 新增任务回报接收/观察时间。 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/EoManualTrackApiTest.java`、`EoTrackingPostgresTest.java` | 目标状态/控制/并发/历史兼容用例 | H2 与独立 PostgreSQL 同一契约和 SQL 验证。 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/EoEdgeMqttTest.java` | 既有协议测试及迟到对账测试 | 夹具使用真实当前目标位置；安全语义变更同步预期；真实本地 MQTT broker 验证。 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/application/EoTrackingPolicyTest.java`、`EoTrackingCommandSafetyTest.java` | 需求和发送边界测试 | 排除条件、缺失事实、过期、队列复核、发送未知、禁止重发、结束去重。 |
| `server/src/test/java/com/uav/lowaltitude/modules/device/api/QaVideoStreamApiTest.java` | `registrationPlaybackAndRevocationStayBoundToTask/insertVideoTarget` | 为媒体测试夹具增加当前位置和任务可信报告水位，不改变媒体业务断言。 |

## 验证

实际命令在 `server/` 执行，Java 17；没有新增依赖。PostgreSQL/PostGIS 使用显式 `stage456_verify_monitor0928` 隔离库的随机 schema，测试结束清理 schema，不写业务库。

1. 先运行新增只读状态/暂停用例，确认原接口缺失时失败，再实现。
2. `.\mvnw.cmd -Dtest=EoManualTrackApiTest,EoTrackingPostgresTest,EoTrackingPolicyTest,EoTrackingCommandSafetyTest,EoEdgeMqttTest,EoEdgeMonitoringPostgresTest,AuthApiTest test`，后续只重跑受修复影响的类。
3. 本地 MQTT 测试设置 ASCII 临时目录 `target/qa-tmp`（`java.io.tmpdir`、`jdk.net.unixdomain.tmpdir`），隔离 broker 不连接实机。
4. `.\mvnw.cmd -DskipTests package`；编译、测试编译、打包，测试结果来自前述正式回归。未声称全仓全量 test。
5. `git diff --check` 无错误，仅既有文件行尾转换提示。

最后一次各类结果（累计 89 次正式测试执行，失败 0、错误 0、跳过 0）：

| 测试类 | 数量 | 环境 |
| --- | ---: | --- |
| EoManualTrackApiTest | 10 | H2 |
| EoTrackingPostgresTest | 10 | 隔离 PostgreSQL/PostGIS |
| EoEdgeMqttTest | 19 | H2 + 本地 MQTT broker |
| EoEdgeMonitoringPostgresTest | 19 | 隔离 PostgreSQL/PostGIS + 本地 MQTT broker |
| EoTrackingPolicyTest | 5 | 单元测试 |
| EoTrackingCommandSafetyTest | 6 | 单元测试 |
| AuthApiTest | 9 | H2 |
| QaVideoStreamApiTest | 11 | H2 + 媒体客户端测试替身 |

说明：此处汇总各类末次通过结果；媒体子类继承基础契约用例，计数是实际执行数，不宣称 89 个互不重复的场景。初始红灯、旧回执夹具与 Mockito 默认空 Map 导致的中间失败均已解决；末轮曾遇到其他并行工作的一处测试编译错误，所属任务修正后重跑通过。

最终 `.\mvnw.cmd -DskipTests package` 成功。交付包 `server/target/low-altitude-server-0.1.0-SNAPSHOT.jar`，67,228,007 字节，文件时间 2026-09-28 23:25:20；核验关键类与当前 `target/classes` 的 SHA-256 一致，已包含最终历史未知占用保护、P2 修复和视频时效门禁。Flyway 008/009 已在两类随机 PG schema 从头迁移及主任务隔离 UI 库升级验证；未修改已应用脚本。

## 未验收与限制

- 未联调真实光电硬件、真实视频或真实现场目标；本地 MQTT broker 和模拟/回放协议回执不代表实机验收。
- 当前协议没有未知类别的安全引导定义，未知类别保持 BLOCKED；没有明确视觉补证原因码的 UNDETERMINED 不自动触发。画面不替代身份、计划、高度基准或合法性证据。
- 无可信停止回执的设备继续保留占用，不以超时释放。历史未知任务若设备已有新任务，必须先核查设备，不能用旧任务停止新任务。
- 三页浏览器、最终本地服务重启/端口/HTTP 200 和确认文档由主任务统一记录；本报告不据此宣称硬件可用。
- 后端 README、最新接口契约、开发基线由主任务同步；本次未改反制、通知时长、合法性状态机或人工复核规则。
