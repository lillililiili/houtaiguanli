# 发起反制选择迫降或驱离：实现与验证记录

日期：2026-10-20（本机时区）。

状态：代码与构建已完成，自动化验证通过；本机后端尚未恢复，运行库迁移及真实浏览器业务操作未完成，不能作为整体验收通过记录。

## 行为

- 普通反制申请与直接反制共用“反制方式”单选项：迫降、驱离，默认迫降。
- 两种方式均保持 `action_type=COUNTERMEASURE`。申请时保存 `countermeasure_mode`，审批、执行确认、授权详情和处置进度展示同一选择；执行请求不能覆盖已保存的方式。
- 四通道协议：迫降 `0x0F`，驱离 `0x0D`；凌云协议 B：迫降 `operationCmd=60003`，驱离 `operationCmd=60002`，两者 `operationType=1`。
- 权限、证据、设备占用、有效期、幂等及急停检查沿用已有实现。旧授权不回填；旧调用及自动反制未传方式时沿用原动作与策略映射。
- 追加迁移 `V202610200001__disposal_countermeasure_mode.sql`，不修改已应用迁移。

## 代码变更说明

后端路径以 `E:\houtaiguanlii` 为根，前台路径以 `E:\rwurenji\_rong` 为根。Java 类除特别说明外位于 `server/src/main/java/com/uav/lowaltitude/modules/disposal/`。

| 改动文件/类 | 改动方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| `domain/CountermeasureMode.java` | `parse`、`mask`、`command` | 校验方式并生成协议参数 | 统一迫降、驱离枚举与两种设备协议映射 |
| `api/DisposalDtos.java` | `CreateRequest`、`AuthorizationDto`（记录结构） | 定义请求与响应 | 新增方式字段 |
| `application/DisposalAuthorizationService.java` | `parseCreate`、`createInternal` | 解析并创建授权 | 校验、保存方式，写入事件与审计 |
| `application/DisposalExecutionGateway.java` | `dispatch`、`dispatchAs`、`dispatch4ch`、`dispatch4chAs`、`mode`、`command`、`mask` | 下发设备指令 | 只读取授权已保存方式，生成对应指令 |
| `application/DisposalReadService.java` | `dto` | 返回授权详情和列表 | 回读方式供审批及页面展示 |
| `infrastructure/DisposalRepository.java` | `insert`、行映射、`AuthorizationRow` | 存取授权记录 | 保存、读取方式，旧插入入口兼容空值 |
| `server/src/main/resources/db/migration/V202610200001__disposal_countermeasure_mode.sql` | 无具体方法：新增列及 CHECK | 持久化约束 | 限定枚举、反制动作和支持的通道 |
| `dongying-vue/src/ui/labels.js` | 无具体方法：`COUNTERMEASURE_MODE_LABEL` | 统一显示名称 | 显示迫降、驱离 |
| `dongying-vue/src/ui/disposalAuthModal.js` | `showDisposalRequestForm`、`summaryHtml` | 创建申请表单及确认摘要 | 增加方式选择、校验、提交及回读展示 |
| `dongying-vue/src/pages/alarms/AuthorizationQueue.vue` | 无具体方法：详情模板 | 展示待办授权 | 显示所选反制方式 |
| `dongying-vue/src/pages/AlarmsPage.vue` | `counterSubject` | 整理进度刷新字段 | 保留方式字段 |
| `dongying-vue/src/components/disposal/counterProgressView.js` | `counterProgressView` | 生成处置进度文案 | 在人工反制标题后显示方式 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/DisposalDeviceRunApiTest.java` | `selectedModeIsSavedReadBackAndUsedForDirectExecution`、`approvalAndExecutionKeepTheRequestedMode`、`unknownOrMisappliedModesDoNotCreateAuthorizationOrCommand` | 验证申请与执行链路 | 覆盖两种方式、审批后执行、禁止覆盖、幂等、非法值及急停 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/DisposalDeviceRunPostgresTest.java` | `databaseRejectsInvalidModeWithoutChangingExistingAuthorization` | 验证 PostgreSQL 约束 | 防止非法方式及动作组合入库 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/api/DisposalMqttFlowTest.java` | `selectedModeUsesTheProtocolBCommandOnTheWire` | 读取本机测试 MQTT 报文 | 验证两个真实序列化指令及重复下发保护 |
| `server/src/test/java/com/uav/lowaltitude/modules/alarm/application/AlarmRuleCounterTest.java` | 授权行夹具构造 | 构造自动规则测试记录 | 新字段保留空值，保持原场景 |
| `server/src/test/java/com/uav/lowaltitude/modules/alarm/application/CounterHistoryTest.java` | 授权行复制夹具 | 构造历史测试记录 | 复制方式字段 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/application/AutomaticCounterAuthorizationTest.java` | 授权行夹具构造 | 构造自动授权记录 | 新字段保留空值 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/application/AutomaticCounterPersistencePostgresTest.java` | 授权行夹具构造 | 构造自动授权持久化测试记录 | 新字段保留空值 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/application/DirectDisposalAccessTest.java` | 授权行夹具构造 | 构造直接反制权限测试记录 | 新字段保留空值 |
| `server/src/test/java/com/uav/lowaltitude/modules/disposal/application/DisposalReceiptSyncTest.java` | 授权行夹具构造 | 构造回执同步测试记录 | 新字段保留空值 |
| `dongying-vue/tools/disposalDeviceCancel.test.cjs` | 普通申请/直接反制参数化用例及已有夹具 | 检查表单提交契约 | 覆盖两种方式，保持反制动作及原设备字段 |
| 两仓库 `docs/backend-stage13/disposal-authorization-api-contract.md`、后端 `docs/后端开发基线.md` | 无具体方法：契约说明 | 记录接口与行为 | 补充枚举、协议映射、兼容规则 |

## 已完成验证

1. 先运行新增回归测试：修改前两种方式均因新字段被拒绝而失败，确认测试覆盖原缺口。
2. 后端受影响模块回归与 `clean package`：653 项中 402 项通过、251 项按环境条件跳过，失败/错误均为 0。包含授权、权限、急停、幂等、协议编码与本机 MQTT 回环。
3. 隔离 PostgreSQL/PostGIS：17 项通过、0 跳过，覆盖四通道运行链路、数据库约束和两种 MQTT 报文。没有在业务运行库写入测试业务记录。
4. 前台聚焦测试：35 项全部通过。前台生产构建通过，变更 JavaScript 语法检查通过，两仓库 `git diff --check` 通过。
5. 扩大前台测试时，已有 `tools/alarmCounterProgress.test.cjs` 中 5 项失败，错误为旧夹具缺少 `mergeProgressRead`/`readProgressSource`；用该页面的 Git HEAD 原始内容重跑仍为同样 5 项失败，未将其计入通过，也未扩大本次修改范围。

本次后端构建产物：`E:\houtaiguanlii\server\target\low-altitude-server-0.1.0-SNAPSHOT.jar`，构建时间 2026-10-20 22:18:29。该产物对应本轮验证时的代码；之后其他任务的工作区改动不属于本报告验证范围。

## 尚未完成与运行状态

- 为清理旧构建并恢复本机服务，已核对并停止本仓库原后端进程；随后两次本机后端启动均被自动审批拒绝，工具仅返回 `blocked by policy`，未给出具体理由。已停止尝试其他启动方式。
- 最后检查本机 `8081` 无监听，业务运行库尚未应用本次迁移。不能声称页面功能已经在运行环境生效。
- 已运行强制启动检查，报告 `artifacts/local-runtime/counter-mode-startup-check.json` 的 `passed=false`：后端就绪及前后台 API 代理失败；地图及模拟器页面检查通过，账号登录未测试。
- 真实浏览器已查看业务页面，但由于后端不可用，尚未完成登录、选择两种方式、提交及详情回读。恢复后端后仍需完成这些操作；仅允许本机明确的模拟设备，保持 `app.dev-seed.enabled=false`。
- 未提交、推送或部署服务器。工作区另有空域相关改动，未改写或纳入本次成果。
