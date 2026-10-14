# 当前模拟设备台账同步验收（2026-10-13）

## 结论与原因

旧逻辑复用或登记当前场景设备，但未退役退出场景的稳定设备；运行库还保留系统预置及早期联调台账，因此地图上出现当前模拟器以外设备。本轮在本机实际运行库 `houtaiguanli_fresh3` 经既有接口退役 33 台：9 台旧场景、12 台预置、12 台旧联调。保留 10 台当前场景设备和 1 台当前四通道模拟设备，共 11 台。

原始台账 `/devices` 与业务口径 `/devices?enabled=true&statistics_scope=true` 均为 11 台，设备 ID 集合与保留清单完全一致。已在已登录 Chrome 的融合感知及后台设备管理实际查看，均为 11 台；地图默认范围及坐标不变，没有页面隐藏补丁。

## 自动流程与失败边界

`Platform.prepare_devices` 完成当前全部设备登记后，在发送报文之前执行台账同步。`catalog_reconciliation.rows/owned/binding/plan/apply` 完整分页核对当前数据库的设备 ID、外部身份、模拟器厂家型号、replay+simulated、归属单位区域、Broker 与协议身份，旧设备经 PATCH 停用及 DELETE 退役。已有权限、版本、幂等和审计继续生效。分页不完整、身份不一致、版本冲突、权限不足或忙碌会报告失败，准备流程不假装完成；空场景不做批量删除。其他连接或来源不自动删除。预置与旧联调设备只按本轮用户授权清理。

已删除设备的历史身份不复活；返回含已退役设备的旧场景须使用新的逻辑设备身份。并发编辑仍由逐台版本门禁保护，本次未新增跨整个场景的数据库事务。

后端仅在允许模拟的环境，对无截止时间、从未派发 outbox、明确 mock+simulated 的 REBOOT 样例快照区分静态占位。设备须同为 mock+simulated 且无有效协议来源。这些快照保持原状态和回执，删除时不再永久阻塞。已派发命令、live/replay 指令、调测、OPEN/ENDING 光电任务、反制和未确认急停继续阻断。其他调用 `hasActiveWork` 的恢复、控制等流程保持原门禁。

## 实际清理与历史核验

- 两台旧光电通过原 MQTT 模拟协议接收 EndTracking 并回传 2 条实际模拟停止回执，后端解除占用；没有手写成功状态。
- 五个旧调测样例通过原取消接口进入 CANCELLED，原任务及审计保留。
- 33 台全部正常停用、软删除，33 条删除审计及 33 条 CATALOG_DELETED 事件逐台对应。
- 删除前后比对内容摘要：375,716 条状态历史、14 条设备指令、8 条回执、9 条调测任务、2 条光电任务、19 条异常单及 1 条证据关联均未改变。此摘要在上述正常结束／取消之后、台账删除之前采集；正常结束记录的变更另有回执与证据。
- 重复调用当前真实场景的 `prepare_devices`：10 个原 ID 全部复用，写请求为 0，待退役集合为空；台账仍 11 台。

## 自动化验证

- Python 模拟器完整 unittest：415 项运行，410 通过、5 项条件跳过。使用 `PYTHONPATH=.` 并排除本机 HTTP 代理；最初错误启动目录与代理导致的导入／本机 HTTP 失败已修正调用后重跑。
- 后端 DeviceOperationsApiTest 10、DeviceConsistencyApiTest 14、DeviceCatalogScopeApiTest 22、DeviceRetirementPostgresTest 2，共 48 项全部通过。包含权限、停用要求、版本冲突、历史保留、跨页面同源、已派发指令仍阻断、mock 快照退役。
- PostgreSQL 用独立 `stage456_verify_catalog_20261013` 临时 schema 跑两项合同，未在业务库执行测试夹具。最初继承整组 H2 合同遇到其重复键测试的 PostgreSQL 事务中断差异，调整为独立运行本轮两项合同后通过。
- 后端 Maven package 成功，两个仓库 `git diff --check` 通过。

## 运行边界

后端已加载新 jar，保持 `local,qa` 与 `app.dev-seed.enabled=false`、原数据库和环境配置。模拟器已通过原停止全部接口停报并重启加载新代码，保留场景和批次；用户重新连接后，已恢复原场景运行；新批次 `sim-1013043931-8649` 为 RUNNING、MQTT 已连接，manifest 已保存 `device_catalog_reconciliation.retired=[]`。实际启动后再次回读仍为 11 台且 ID 集合一致。更新服务曾使运行中的模拟器按原断连保护失败停报，未伪造恢复，也未自动续发。当前已验证台账清理、浏览器地图和后台一致、同场景准备幂等以及重启后真实 MQTT 运行；真实设备现场联调和长时间持续运行不在本次通过范围。

## 证据

仓库 `outputs/device-catalog-20261013/`：`before.json`、`cleanup.json`、`after.json`、`history-before.json`、`eo-stop.json`、`commission-before.json`、`commission-cancelled.json`、`repeat-prepare.json`、`map-after.png`、`admin-after.png`、`restarted-runtime.json`、`map-running.png`、`admin-running.png`。不包含登录口令或会话令牌。

## 代码变更说明

| 文件/类 | 方法/函数 | 方法作用与本次变化 |
| --- | --- | --- |
| tools/device-simulator/catalog_reconciliation.py | rows、owned、binding、plan、apply | 读取完整台账并核对来源，通过原停用／删除接口同步退出场景的设备。 |
| tools/device-simulator/platform_client.py / Platform | prepare_devices | 当前设备全部登记完成后、上报前运行同步并保存结果。 |
| DeviceRepository | hasActiveWork、hasCatalogBlockingWork | 分离原强门禁与台账删除门禁；仅识别未派发的静态 mock 重启样例。 |
| DeviceService | delete | 删除使用新的台账门禁，保留权限、版本、事务及审计。 |
| test_catalog_reconciliation.py | CatalogReconciliationTests 的 11 个用例 | 覆盖旧场景清理、身份／范围／分页保护、冲突、业务占用、光电与准备流程。 |
| DeviceOperationsApiTest | mockRebootSnapshotsWithoutDispatchDoNotPermanentlyBlockCatalogRetirement | 验证快照历史保留及实际命令仍阻断。 |
| DeviceRetirementPostgresTest | database、cleanup、contract、两项测试 | 在隔离 PostgreSQL 复验删除门禁与权限、版本、历史合同。 |

AGENTS、模拟器 README、业务流映射与后端开发基线同步本次约定；未改开发种子、已应用迁移、地图范围、坐标或历史业务状态。
