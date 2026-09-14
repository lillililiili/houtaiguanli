# T02 雷达只读后端阶段 2 退出报告

- 日期：2026-09-04
- 恢复基线：`9a645b301265eafd5978c71bfd07ccd42c7f2769`
- 实施分支：`main`
- 结论：**READY**
- 未关闭问题：Critical = 0，Important = 0，Minor = 0

## 背景与交付范围

阶段 2 的首轮实现曾在设备管理整合前完成；随后整合提交以同名、不同语义的设备与感知表替换了阶段 2 读模型。本次在保留整合后设备管理能力的前提下恢复阶段 2 契约，没有回退或覆盖后续业务代码：

- 新增 `V202609040006__restore_stage2_read_foundation.sql`。既有设备运维与 live 感知表及 Inbox 旧协议列重命名为 `ops_*`，数据、外键和依赖表继续保留；阶段 2 的 `integration_source/device/target/track/alarm` 等稳定表名重新用于 T02 只读模型。
- 补齐 `device:read`、`target:read`、`alarm:read` 动作权限目录、数据库实时授权服务和 `NONE/ASSIGNED/ALL` 范围决策。动作授权不从会话角色、菜单权限或角色名称推导，也不缓存授权结果。
- 生产迁移不写入三项动作权限的角色映射。合成授权同时受 `local`/`test` profile 和 `app.dev-seed.enabled=true` 约束；production profile 即使误开属性也不会加载阶段 2 授权 Seeder。
- PostgreSQL 专属 repeatable migration 提供 GiST、部分唯一索引、表达式索引、WGS-84/空几何约束和组织/区域循环阻断；H2 test profile 只加载可移植迁移。
- 整合后的身份表已对齐 `permission_code varchar(96)`、`permission_version bigint`、父区域和授权查询索引；Inbox 的 `payload_hash` 只接受 64 位小写十六进制 SHA-256。
- 既有设备管理仓储和相关回归测试已切换到 `ops_*` 表。原菜单权限查询显式排除 ACTION 权限，避免生成 `device:read.read` 一类错误权限码。

恢复基线验收当时没有新增 T02 雷达 HTTP 查询、replay 摄取、目标/轨迹/告警应用查询、设备控制或前端改动。其中目标/轨迹只读闭环已由下文“后续增量”补齐；其他能力仍属于后续范围。

## 后续增量：协作者 B 目标/轨迹只读闭环

- 新增目标列表、目标详情、目标轨迹和轨迹点四个标准查询接口，沿用 `target:read` 及组织/区域数据范围。
- 列表的 `items`/`total`、详情、轨迹和轨迹点使用同一范围语义，越权对象按 404 处理；坐标仅输出可信 WGS-84，未知位置不生成 `(0,0)`。
- `local`/`test` 环境提供确定性目标与轨迹数据；所有开发 Seeder 均在 `production`（包括 `production,local` 组合）下禁用。
- 态势页仅将既有目标、详情和轨迹交互接到标准 API，不回退 `window.MOCK`。告警、合法性、风险、视频和处置继续显式标记为“尚未接入”。

增量验收使用 H2 和 PostgreSQL 16.9/PostGIS 隔离库。`TargetReadPostgresApiTest` 只允许连接名称匹配 `stage2_target_verify_*` 的数据库，并在其中创建严格随机的 `stage2_target_api_*` schema，结束后级联清理。

| 增量验收项 | 实际结果 | 结论 |
| --- | --- | --- |
| 目标 API H2 回归 | 12 tests，0 failures，0 errors，0 skipped | 通过 |
| 目标 API PostgreSQL/PostGIS 回归 | 2 tests，0 failures，0 errors，0 skipped | 通过 |
| 目标 Seeder 及幂等性 | 5 tests，0 failures，0 errors，0 skipped | 通过 |
| 生产 Seeder 隔离 | 2 tests，0 failures，0 errors，0 skipped | 通过 |
| 全量 `package`（显式启用隔离 PostgreSQL） | 80 tests，0 failures，0 errors，0 skipped | 通过 |
| 前端生产构建、源码扫描和断言证伪 | 全部通过；仅保留既有大 chunk 提示 | 通过 |

增量验收 JAR SHA-256 为 `78dfe3a7b2e96466aa67c2b08d5a412a38475ae650f6d8ee908e7a8fe1acf691`。

## 迁移验收

PostgreSQL 验收使用本地隔离测试数据库中的随机 `stage2_compat_<uuid>` schema，测试结束仅清理自己创建且通过固定前缀校验的 schema。实际环境为 PostgreSQL 16.9、PostGIS 3.5.2。

| 路径 | 实际结果 | 覆盖重点 |
| --- | --- | --- |
| 空 schema → 最新 | 13 migrations；第二次 migrate 为 0 | 表、类型、索引、空间约束、非法状态、非法哈希、部分唯一键、组织/区域循环 |
| V1/V2 → 最新 | 后续 11 migrations；第二次 migrate 为 0 | 旧 Inbox 基线和完整升级顺序 |
| 已有数据的 V5 → V6 | 后续 2 migrations；第二次 migrate 为 0 | 来源、设备、状态历史、Inbox、目标、轨迹及依赖外键在 `ops_*` 重命名后逐值保留 |

测试覆盖设备越界坐标，设备、目标 latest 和轨迹点的 `POINT EMPTY`，重复非空 `(source_id, external_device_id)`，`ABNORMAL`，无来源告警，以及组织/区域循环。测试完成后确认没有残留 `stage2_compat_*` schema。

## 恢复基线当时的测试与构建证据

| 验收项 | 实际结果 | 结论 |
| --- | --- | --- |
| H2 阶段 2 迁移测试 | 6 tests，0 failures，0 errors，0 skipped | 通过 |
| PostgreSQL/PostGIS 专属测试 | 3 tests，0 failures，0 errors，0 skipped | 通过 |
| 授权服务测试 | 4 tests，0 failures，0 errors，0 skipped | 通过 |
| Seeder 环境门禁测试 | 3 tests，0 failures，0 errors，0 skipped | 通过 |
| 全量 `package` | 59 tests，0 failures，0 errors，0 skipped；JAR 已生成 | 通过 |
| `git diff --check` | 无输出 | 通过 |

恢复基线当时完整测试共 59 项，覆盖原认证、系统管理、审计、设备管理、协议模拟与阶段 2 新增回归。当时的 JAR SHA-256 为 `8127050453316e2794c217422f9398b5a5c77bf52df082ded06840c730216184`；当前增量证据以上文表格为准。

构建运行时为 OpenJDK 21.0.12，Maven 编译沿用项目 `<release>17</release>`；本机没有单独的 Java 17 运行时证据。全局 Tencent Maven 镜像发生 TLS 失败后，验收命令使用仅对本次进程生效的 Maven Central settings；仓库和用户 Maven 配置均未修改。

## 独立审查

第一轮只读审查结论为 `With fixes`，指出生产 Seeder profile 门禁、已有数据的 V5→V6 升级证明及非法样本矩阵不足，并建议补齐哈希格式和身份物理字段。上述项目均已实现并以定向测试通过。

第二轮独立只读复核未发现未关闭的 Critical、Important 或 Minor，建议 **READY**。审查任务没有修改文件、分支或验收结果。

## 基线与上线边界

V1/V2 未修改，当前 SHA-256 为：

- `V1__init.sql`：`a2889567e5c925814b7b18c0301ef42d0b3022286dcd0d8e64b23f09cd6d632c`
- `V2__outbox_inbox.sql`：`4f3ca4794a7a9c7a34cd2fae1396b00b377fcf3e39a334826bff2cfe8e5af87c`

用户未跟踪资料、计划文件和设备资料保持原状，未纳入阶段 2 交付。由于 V6 会重命名设备运维表和 Inbox 旧协议列，上线必须在维护窗口内将迁移与对应 `ops_*` 应用代码作为同一版本部署，不适合让 V5 与 V6 应用节点滚动并存。

## 退出判定

阶段 2 的兼容迁移、动作权限、范围决策、环境隔离、真实 PostgreSQL/PostGIS 升级验证及独立审查均达到退出条件，判定 **READY**。阶段 3 尚未启动。
