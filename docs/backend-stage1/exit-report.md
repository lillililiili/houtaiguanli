# T02 雷达只读后端阶段 1 退出报告

- 报告日期：2026-09-04
- 代码基线：`origin/main` / `d0a0b4ee366687fdea6e4621f7d16cd31c406476`
- 最终决定：`READY`
- 阶段边界：本报告只判定阶段 1 契约是否具备退出条件；未实现或启动阶段 2 的 Java、SQL、YAML、正式测试、前端或设备接入工作。

## 1. 计划版本与监督范围

本轮依据以下计划文件执行：

```text
/Users/frank/Desktop/dongyiwurenji/docs/superpowers/plans/2026-09-03-stage-1-t02-contract-and-isolation.md
SHA-256: 39f5c026dc68a26fc134e7faa61212f48c647dd128dbee7883d93af38a15c026
size: 12247 bytes
```

该计划在退出时仍不属于 `origin/main` 的受跟踪文件；本报告用绝对来源路径、字节数和内容哈希固定本轮实际执行版本。总协调只监督、纠偏、交叉核对和判定退出，未修改甲乙契约、独立审查、生产代码、历史迁移、前端或设备资料原件。

## 2. 三项任务、提交与改动文件

| 任务 | Codex 任务 ID | 提交链与最终内容 | 累计改动文件 | 结果 |
| --- | --- | --- | --- | --- |
| 后端阶段1 实现甲｜数据与API契约 | `01a06a7f-3ce3-75c0-8acf-28847ce54ffd` | `d0a0b4e → 1830ea588d5f0da422da1e559168686fb7bf7708 → 7a384020b72b7a293386c8bd774b979788005e34`；最终内容取 `7a38402` | 仅新增 `docs/backend-stage1/data-api-contract.md` | 完成；两轮协调修订关闭状态字典和 latest 时间语义冲突 |
| 后端阶段1 实现乙｜T02协议与回放契约 | `01a06a7f-3ce3-75c0-8acf-287b7234ff64` | `d0a0b4e → bfe0e045ab98962d398413110ff61c9cec904425 → d60232f6f20e61d0d7bc89e76aa005f86657549d`；最终内容取 `d60232f` | 仅新增 `docs/backend-stage1/t02-replay-contract.md` | 完成；协调修订关闭 Inbox、来源长度和业务标识映射缺口 |
| 后端阶段1 独立审查｜安全与可执行性 | `01a06a7f-3ce3-75c0-8acf-2857b7d0b0be` | `d0a0b4e → 9556b85f55c1012e5c5d8de7ba997f08a88f22e1 → 4d3969b0a36a9e6807f340c4d7534240f6c5f2a1`；最终内容取 `4d3969b` | 仅新增 `docs/backend-stage1/independent-review.md` | 第二轮结论 `READY`；未解决 P0 = 0、P1 = 0、P2 = 5 |

最终证据可直接读取：

```bash
git show 7a384020b72b7a293386c8bd774b979788005e34:docs/backend-stage1/data-api-contract.md
git show d60232f6f20e61d0d7bc89e76aa005f86657549d:docs/backend-stage1/t02-replay-contract.md
git show 4d3969b0a36a9e6807f340c4d7534240f6c5f2a1:docs/backend-stage1/independent-review.md
```

累计范围核验显示甲乙各自只新增负责的契约文档；审查任务两轮只新增并更新自己的审查文档。基线、甲最终提交、乙最终提交和审查工作树的 `server` tree 均为 `e90cb38be0795d781ffc932463df3273e864ec1b`，未夹带 Java、SQL、YAML、测试或其他后端变更。

## 3. 基线测试证据

独立审查第一轮在 `server/` 实际执行：

```bash
./mvnw test
```

| 项目 | 实际结果 |
| --- | --- |
| 执行完成时间 | 2026-09-03 23:39:48 -04:00 |
| Maven | Wrapper 3.9.9 |
| 测试 JVM | Eclipse Adoptium Java 21.0.12，macOS aarch64 |
| 编译目标 | Maven Compiler `release 17` |
| Profile / 数据库 | `test` / H2 2.3.232，PostgreSQL compatibility mode |
| Maven 结果 | `BUILD SUCCESS`，退出码 0 |
| Surefire 实际汇总 | **13 tests，0 failures，0 errors，0 skipped** |

逐类计数为 `SourceModeGuardTest` 3、`LoginFailurePersistenceTest` 2、`AuthApiTest` 8。计数来自 `server/target/surefire-reports/TEST-*.xml` 及文本报告，不只依据退出码。

第二轮未重复运行 Maven，因为代码基线、甲乙最终提交和审查工作树的 `server` tree 完全相同，且没有测试文件变化；第二轮保留上述实际执行证据，不把文档检查表述为重新构建或重新测试。

## 4. 交叉契约核对

| 核对项 | 最终统一结论 |
| --- | --- |
| 来源键 | `source_namespace = replay:<source_code>:<dataset_id>`；生成后字符数和 UTF-8 字节数均须 `<=128`；`source_message_id` 为 `record_no` 的规范十进制字符串；分别映射到 Inbox `source/source_msg_id` |
| 幂等与冲突 | `payload_hash = SHA-256(raw frame bytes)`；同键同 hash 幂等，同键异 hash 返回 `SOURCE_MESSAGE_CONFLICT`，不插入第二条正常 Inbox、不更新原行、不产生业务写入 |
| 来源模式 | 公共枚举为小写 `mock/replay/live`，T02 首批固定 `SourceMode.replay` / `source_mode=replay`；无 mock/live 降级或冒充 |
| 时间 | 新业务数据库时间为 `timestamptz` / Java `Instant`；REST 为 epoch 毫秒；协议微秒经校验后向下整除 1000，协议毫秒直接使用；接收时间不冒充事件时间 |
| latest 与历史 | 仅可信、非空事件时间严格晚于现有值时创建或覆盖 latest；相等、更早或未知只追加历史或保留 Inbox；`COALESCE(observed_at,received_at)` 只用于历史展示与稳定排序 |
| 表与标识 | 两个顺序迁移批次和计划指定表名唯一；`dataset_id → source_session_key`，T02 无符号 `targetId → external_target_id/external_track_id`，`record_no → point_seq`；无稳定 ID 的 `UPLOAD_TARGET_V3` 不猜造 target/link/track |
| DTO | REST DTO 名和字段由数据/API 契约唯一确定；协议类型固定为 `InboundFrameSink`、`InboundFrame`、五类解析消息及显式 `UnsupportedMessage`，不建立平行类型 |
| 权限 | 唯一动作权限码为 `device:read`、`target:read`、`alarm:read`；无角色映射、无有效组织/区域范围或未知归属时默认拒绝；列表、详情、历史和 `total` 共用范围谓词，无权对象统一 404 |
| 错误与 Inbox 状态 | REST 稳定错误码和摄取错误码互不混用；Inbox 只允许 `RECEIVED/PROCESSING/DONE/FAILED`，不支持或解析失败统一为 `FAILED` 并记录稳定 `last_error` |
| 未知坐标/高度 | 缺少批准的 RTK 原点、航向或转换依据时不生成 WGS-84；协议 Z 只保留相对雷达高度，不写 AGL/AMSL；`RtkUpload.altitudeRaw` 当前无效，不当安装高程；未知值不写 0 |
| 禁止能力 | T02 点迹/航迹不自动生成告警；无真实联网、无 `live` 自动回退、无登录/RTK 请求下发、无雷达控制或反制能力 |

## 5. P0、P1 与 P2

### P0

未解决 P0：**0**。独立审查确认提交范围、权限默认拒绝、来源隔离、未知事实、解析失败副作用以及禁止能力均满足退出条件。

### P1

未解决 P1：**0**。本轮关闭的 P1 为：

1. `DEGRADED` 与旧设计稿 `ABNORMAL` 状态字典冲突。
2. `UnsupportedMessage` 可能引入未定义的第五种 Inbox 状态。
3. Inbox JSONB 原始信封及 `InboundFrame` 到持久化列映射缺失。
4. 同键异 hash 的“隔离”与既有唯一键语义不明确。
5. 来源命名空间最大 200 字符与 `inbox_message.source varchar(128)` 冲突。
6. T02 到 target/link/track/point 的标识映射缺失，以及无稳定 ID 点迹可能被猜造。
7. latest 曾使用接收时间兜底并允许相同事件时间被后到消息覆盖。

关闭证据由独立审查提交 `4d3969b` 第 6 节逐项定位到甲乙最终 Git blob。

### P2

独立审查登记 P2 共 **5** 项，均不阻断本次契约退出：

| ID | 状态 | 后续事项 | 归属 |
| --- | --- | --- | --- |
| P2-001 | DEFERRED | 在 Java 17 运行时复跑后端测试；当前实际测试 JVM 为 Java 21.0.12 | 阶段 2 后端实现/CI 负责人 |
| P2-002 | DEFERRED | 决定接受并记录 Flyway 对 H2 2.3.232 的支持范围警告，或在另行获准的依赖基线任务中处理 | 后端构建基线负责人 |
| P2-003 | MITIGATED | 计划不在 `origin/main`，Git 不能单独还原 | 本报告已固定绝对路径、12247 字节和 SHA-256；后续文档集成负责人仍应把计划纳入可追溯版本管理 |
| P2-004 | DEFERRED | 在隔离 PostgreSQL 16/PostGIS 3.5 验证迁移、CHECK/FK/唯一键、表达式/GiST 索引和空间行为 | 阶段 2 数据库实现负责人 |
| P2-005 | DEFERRED | 补齐真实设备、来源、坐标、高度和脱敏现场差异资料；资料不足时保持未知并阻塞对应真实能力 | 总协调后续跟踪设备方/数据提供方 |

## 6. 待补资料

- 真实设备台账，以及每台设备的 `owner_org_id/district_id` 归属。
- 获准的 `integration_source.source_code` 范围和 source 到 device 的唯一映射。
- 生产角色、三项读取权限和用户组织/区域范围的显式映射资料。
- 经批准的 RTK 原点、雷达朝向/正北参照、坐标转换关系和适用范围。
- 雷达安装高度、垂直基准、地面高程来源及 AGL/AMSL 换算依据。
- 设备时钟/时间基准、现场字段差异，以及不含真实 IP、账号、凭据和敏感载荷的脱敏合成证据。

上述资料缺失不影响阶段 1 契约退出，但不得用默认值补齐，也不得据此启用真实连接、位置/高度推导或扩大权限。

## 7. 未执行项

- 未在 Java 17 运行时复跑 `./mvnw test`。
- 未执行 `./mvnw package`。
- 未执行 PostgreSQL 16/PostGIS 3.5 迁移、约束、并发、索引或空间行为验证。
- 未执行前端安装、构建或消费者联调。
- 未执行设备联调、真实网络连接、现场报文回放或 live 模式验证。
- 未创建或修改阶段 2 的 Java、SQL、YAML、正式测试、前端或设备资料。

## 8. 阶段退出决定

三项证据已齐全：甲乙最终契约提交可精确读取且累计范围互不重叠；独立审查第二轮无未解决 P0/P1；基线 Maven 测试有 13/0/0/0 的实际 Surefire 计数。两份契约无 `TBD/TODO`，并已统一来源键、模式、时间、表、DTO、权限、错误、未知值和禁止能力。

**最终决定：`READY`。阶段 1 可以退出，允许后续单独启动阶段 2；本任务本身未进入阶段 2。P2 与待补资料必须按上表归属继续跟踪，且不得被解释为已经完成的数据库、权限、回放、REST 或设备联调能力。**
