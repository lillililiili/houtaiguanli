# T02 雷达只读后端阶段 1 独立审查

- 审查轮次：第二轮（最终契约审查）
- 审查日期：2026-09-04
- 代码基线：`origin/main` / `d0a0b4ee366687fdea6e4621f7d16cd31c406476`
- 数据/API 最终提交：`7a384020b72b7a293386c8bd774b979788005e34`
- T02/回放最终提交：`d60232f6f20e61d0d7bc89e76aa005f86657549d`
- 当前结论：`READY`

## 1. 结论和适用边界

本轮直接读取指定 Git 对象中的两份最终文档，并逐项复核第一轮 P0/P1/P2 清单；没有用实现者摘要替代内容审查。最终未发现未解决 P0/P1，第一轮 P1-001 及总协调退回的其余 P1 均有可定位的关闭证据，基线测试证据完整，因此独立审查结论为 `READY`。

该结论只表示阶段 1 契约满足独立审查门槛，不等于数据库、权限、回放或 REST 已经实现，也不单独授权进入阶段 2。只有总协调在退出报告中给出最终 `READY` 后，才能启动阶段 2。

本任务两轮均未读取、复制或修改设备协议原件，未访问现场报文、真实连接信息或凭据，未执行设备联调；未修改 Java、SQL、YAML、前端、设备资料或甲乙文档，也未实现自动判警、`live` 连接、雷达控制或反制能力。

## 2. 审查对象、提交链与范围证据

最终内容通过以下 Git 对象读取：

```bash
git show 7a384020b72b7a293386c8bd774b979788005e34:docs/backend-stage1/data-api-contract.md
git show d60232f6f20e61d0d7bc89e76aa005f86657549d:docs/backend-stage1/t02-replay-contract.md
```

| 产物 | 父链 | 累计范围核验 | 结果 |
| --- | --- | --- | --- |
| 数据/API | `d0a0b4e → 1830ea5 → 7a38402` | `git diff --name-status d0a0b4e 7a38402` | 仅 `A docs/backend-stage1/data-api-contract.md` |
| T02/回放 | `d0a0b4e → bfe0e04 → d60232f` | `git diff --name-status d0a0b4e d60232f` | 仅 `A docs/backend-stage1/t02-replay-contract.md` |

两条提交链的累计 diff 均通过 `--check`。基线、两个最终产物及本审查工作树的 `server` tree 均为 `e90cb38be0795d781ffc932463df3273e864ec1b`，证明 Java、SQL、YAML、测试和其他后端文件未随甲乙提交变化。两个产物均未包含设备资料原件或其他文件。

本审查工作树为 Codex 管理的 linked worktree，第二轮开始时处于 detached HEAD `9556b85f55c1012e5c5d8de7ba997f08a88f22e1`，工作区干净。阶段计划由总协调通过原工作区提供；其文件仍不在 `origin/main`，见 P2-003。

## 3. 基线测试证据

第一轮实际执行：

```bash
cd server
./mvnw test
```

| 项目 | 实际结果 |
| --- | --- |
| 执行结束时间 | 2026-09-03 23:39:48 -04:00 |
| Maven | 3.9.9（仓库 Wrapper） |
| 测试 JVM | Eclipse Adoptium Java 21.0.12，macOS aarch64 |
| 编译目标 | Maven Compiler `release 17` |
| Spring profile / 数据库 | `test` / H2 2.3.232，PostgreSQL compatibility mode |
| Maven 退出结果 | `BUILD SUCCESS`，退出码 0 |
| Surefire 汇总 | 13 tests，0 failures，0 errors，0 skipped |

Surefire 逐类报告：

| 测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `SourceModeGuardTest` | 3 | 0 | 0 | 0 |
| `LoginFailurePersistenceTest` | 2 | 0 | 0 | 0 |
| `AuthApiTest` | 8 | 0 | 0 | 0 |
| **合计** | **13** | **0** | **0** | **0** |

计数来自 `server/target/surefire-reports/TEST-*.xml` 和对应文本报告，不仅依据 Maven 退出码。第二轮未重跑 Maven：本文件第一轮已规定仅在审查基线变化或测试相关文件变化时重跑，而四个待比较的 `server` tree 完全相同；现有 surefire 报告也仍逐类记录 3/2/8 个测试。`target/` 为未提交构建产物。

本次 H2 测试不证明 PostgreSQL 16/PostGIS 3.5 的迁移、约束、锁、索引或空间行为，也没有执行 `package`、前端构建或设备联调；这些项目未被声称通过。

## 4. 不变的代码基线事实

| 事实 | 最终契约处理结果 |
| --- | --- |
| V1/V2 仅有六张表且属于历史迁移 | 数据契约明确只能以两个后续顺序批次新增/扩展，不修改、重命名或重排 V1/V2 |
| 设备/告警 Controller 当前只验证登录、固定空分页并夹取非法页码 | 数据契约把真实权限/范围查询及 `INVALID_PAGE` 明确标为阶段 2 目标行为和有意兼容收紧 |
| `AuthUser` 不含权限、范围或权限版本 | 数据契约定义三权限码、默认拒绝、角色/范围解析及 `permission_version` 失效语义，没有把已登录误写成已授权 |
| V2 Inbox 只有 `source/source_msg_id/received_at` 和复合唯一键 | 两份契约共同固定增量列、JSONB 原始信封、四态状态、来源列映射和同键异 hash 冲突语义 |
| `AdapterPort` 当前只有 `mode()`，只有 mock 实现 | 回放契约把扩展签名标为后续唯一目标类型，固定 `SourceMode.replay`，且不允许 mock/live 回退 |
| 当前不存在目标、轨迹、设备状态、告警业务表或真实查询 | 数据契约只固化后续表、DTO 和 REST；没有把文档描述成已实现能力 |

## 5. 分级规则与最终统计

- **P0**：越权、来源混淆、原始事实覆盖、未知事实伪造、受控动作启用、敏感资料进入 Git 或突破阶段 1 禁止范围。
- **P1**：会导致悬空外键、重复建模、接口不稳定、幂等失效、时间覆盖错误或实现任务继续猜测的契约冲突/缺口。
- **P2**：不阻断契约固化，但影响可追溯性、环境一致性或后续验收质量，必须有明确归属。

最终统计：未解决 P0 = 0；未解决 P1 = 0；P2 = 5，均已指定后续归属。

## 6. P1 关闭复核

以下行号均指最终 Git blob 的 1-based 行号。

| ID | 状态 | 原问题 | 独立关闭证据 |
| --- | --- | --- | --- |
| P1-001 | CLOSED | 计划 `DEGRADED` 与设计稿 `ABNORMAL` 冲突 | 数据契约 `7a38402:L213` 明确以 `ONLINE/OFFLINE/DEGRADED/UNKNOWN` 替代旧稿，数据库 CHECK、筛选和 DTO 禁止 `ABNORMAL`；无确认映射时使用有原因的 `UNKNOWN` |
| P1-002 | CLOSED | `UnsupportedMessage` 是否形成第五种 Inbox 状态不清 | 回放契约 `d60232f:L268-L287` 固定四态 `RECEIVED/PROCESSING/DONE/FAILED`，明确不存在 `UNSUPPORTED` 状态；不支持消息返回 `UnsupportedMessage` 且 Inbox 为 `FAILED/UNSUPPORTED_MESSAGE` |
| P1-003 | CLOSED | Inbox JSONB 信封及列映射缺失 | 数据契约 `7a38402:L174-L187` 固定既有列、增量列和四态；回放契约 `d60232f:L209-L227` 逐列映射 `source/source_msg_id/source_id/received_at/payload_hash/payload`，并固定四字段 JSONB 信封 |
| P1-004 | CLOSED | 同键异 hash 与既有唯一键的“隔离”语义冲突 | 回放契约 `d60232f:L229-L233,L278` 明确复用 `UNIQUE(source,source_msg_id)`：同键同 hash 幂等，同键异 hash 不插入、不更新原 Inbox，只形成脱敏运行诊断并禁止业务写入；数据契约 `7a38402:L187` 保持原行且禁止覆盖 |
| P1-005 | CLOSED | 合法片段组合可能超过 Inbox `varchar(128)` | 回放契约 `d60232f:L196-L205` 在构造 frame/查询 Inbox 前同时校验字符与 UTF-8 字节 `<=128`，超限为 `REPLAY_LINE_INVALID`、不建 Inbox且不得截断；与数据契约 `7a38402:L174` 的现有列长度一致 |
| P1-006 | CLOSED | T02 到 target/link/track/point 标识缺失，点迹可能猜造稳定 ID | 回放契约 `d60232f:L239-L250` 固定 `dataset_id → source_session_key`、无符号 `targetId → external_target_id/external_track_id`、`record_no → point_seq`；重复 ID 整帧失败；`UPLOAD_TARGET_V3` 无稳定 ID 时只留 Inbox，禁止按下标、位置或 hash 猜造业务对象 |
| P1-007 | CLOSED | latest 更新把接收时间当事件时间，且相同事件时间可被后到消息覆盖 | 数据契约 `7a38402:L55-L56,L222-L230,L291-L296` 要求可信非空 `observed_at`，仅严格更晚才创建/覆盖，相等、更早、未知只追加历史或保留 Inbox；DTO 与查询在 `L424,L492-L495,L583-L592` 明确 `COALESCE` 只用于历史展示。该语义与回放契约 `d60232f:L311-L319` 一致 |

## 7. P0/P1/P2 最终审查清单

### 7.1 P0：安全与阶段边界

| 检查项 | 结果 | 证据摘要 |
| --- | --- | --- |
| P0-C01 提交范围 | PASS | 两条累计 diff 各自只新增约定文档；无 Java、SQL、YAML、前端、设备原件、现场报文、真实 IP、账号或凭据 |
| P0-C02 历史迁移 | PASS | 数据契约第 1.2、3 节禁止修改 V1/V2，并要求执行时取连续两个后续版本 |
| P0-C03 权限默认拒绝 | PASS | 数据契约第 3.1、4 节固定三权限码；角色/映射/范围缺失拒绝，未知归属不可见，列表/详情/历史/`total` 共用范围，无权对象 404 |
| P0-C04 未知事实 | PASS | 数据契约第 2.2、5.3 节及回放契约第 8 节保持未知时间、位置、高度、状态和归属，不以 0、在线、成功或全域代替 |
| P0-C05 来源隔离与去重 | PASS | 回放契约第 5.2–5.3 节固定 `replay:<source_code>:<dataset_id>`、128 上限、record_no 消息 ID、SHA-256、幂等与冲突不覆盖；数据契约第 2、3.2 节禁止跨模式关联 |
| P0-C06 解析失败副作用 | PASS | 回放契约第 6–7 节规定长度、CRC、字段和不支持消息失败只更新 Inbox/运行诊断，不写目标、轨迹、设备成功状态或告警 |
| P0-C07 禁止能力 | PASS | 两份契约第 1 节均明确无自动判警、无真实联网或 `live` 回退、无雷达控制/反制；`REQUEST_RTK` 只识别不发送 |
| P0-C08 坐标与高度 | PASS | 回放契约第 8.1–8.2 节要求批准的 RTK 原点/航向及确认后的高度基准；不足时不生成 WGS-84/AGL/AMSL，原始值只保留 Inbox |

### 7.2 P1：一致性与可实现性

| 检查项 | 结果 | 证据摘要 |
| --- | --- | --- |
| P1-C01 表和迁移批次 | PASS | 数据契约第 3 节精确定义两批、依赖顺序、计划要求的全部表/增量列、键、归属、来源、时间和索引；`alarm` 无 `uav_event_id` |
| P1-C02 权限模型 | PASS | 唯一权限码为 `device:read/target:read/alarm:read`；生产默认拒绝，只有 local/test 可显式合成赋权 |
| P1-C03 REST 完整性 | PASS | 数据契约第 5–7 节定义九个 GET 的参数、DTO、固定排序、分页、状态和错误；未新增业务写接口 |
| P1-C04 当前兼容差异 | PASS | 数据契约第 1.2、5.1、8 节区分当前空分页/静默夹取与阶段 2 的真实查询/`INVALID_PAGE` 目标行为 |
| P1-C05 名称和单位 | PASS | 表、DTO、ID、状态和错误码均唯一；REST 为 epoch 毫秒，新业务时间存为 `timestamptz`/Java `Instant`；协议微秒向下整除 1000、协议毫秒直接形成事件毫秒；latest 时间语义见 P1-007 |
| P1-C06 现有 Inbox 映射 | PASS | 见 P1-003–P1-005；无第二套 Inbox/去重表，`source/source_msg_id` 的 128 长度与协议硬校验一致 |
| P1-C07 适配类型 | PASS | 回放契约第 3 节唯一扩展 `AdapterPort`，定义 `InboundFrameSink/InboundFrame`、五类解析消息和 `UnsupportedMessage`，名称无平行版本 |
| P1-C08 回放格式和流式语义 | PASS | 回放契约第 5–7 节覆盖 UTF-8 NDJSON、生命周期、拆包、粘包、噪声、长度、CRC、帧 ID 回绕、EOF、损坏和重复启动 |
| P1-C09 迟到与历史 | PASS | 两份契约统一为可信事件时间严格更晚才能覆盖 latest；相等/更早/未知只追加历史或留 Inbox，历史排序才使用接收时间兜底 |
| P1-C10 测试向量 | PASS | 回放契约第 9 节提供 V01–V20 合成向量，覆盖计划要求及后续修订；独立按文档 CRC 算法重算 V01、V03-login、V06，三者十六进制均与文档一致 |
| P1-C11 文件所有权与重复劳动 | PASS | 数据契约独占迁移/表/权限/REST/DTO，回放契约独占适配接口/帧/消息/回放；交叉只共享已固定的来源、标识、Inbox 和时间语义，无同文件或平行类型冲突 |
| P1-C12 文档收口 | PASS | 对两个最终 blob 搜索 `TBD/TODO` 均无匹配；累计 diff `--check` 无输出；排除能力只作为禁止范围或协议事实出现 |

### 7.3 P2：非阻断后续项及归属

| ID | 状态 | 后续项 | 归属 |
| --- | --- | --- | --- |
| P2-001 | DEFERRED | `pom.xml` 目标为 Java 17，本次基线测试 JVM 为 Java 21.0.12；需补 Java 17 运行时证据 | 阶段 2 后端实现/CI 负责人 |
| P2-002 | DEFERRED | Flyway 警告 H2 2.3.232 高于其已测试支持的 H2 2.2.224；不得在阶段 1 擅自升级依赖 | 后端构建基线负责人 |
| P2-003 | DEFERRED | 阶段计划不在 `origin/main`，仅靠 Git 尚不能还原本轮计划版本 | 总协调在退出报告中固定计划版本或可追溯提交 |
| P2-004 | DEFERRED | PostgreSQL/PostGIS 迁移、CHECK/FK/唯一键、表达式/GiST 索引和空间行为尚未实测；两份契约已明确 H2 不可替代 | 阶段 2 数据库实现负责人在隔离 PostgreSQL/PostGIS 验证 |
| P2-005 | DEFERRED | 真实设备台账及归属、source 到 device 唯一映射、批准的 RTK 原点/航向、垂直基准和脱敏现场差异仍需资料 | 总协调跟踪设备方/数据提供方；资料不足时保持未知并阻塞对应真实能力 |

## 8. 阶段退出判断

| 退出条件 | 最终判断 | 证据 |
| --- | --- | --- |
| 两份契约无 TBD/TODO，名称与时间单位一致 | 满足 | P1-C05、P1-C12；两份最终 blob 已完整交叉核对 |
| 无生产代码、历史迁移、前端或设备原件改动 | 满足 | 两条累计 diff 各自只有约定文档，`server` tree 与基线一致 |
| 无自动判警、真实联网、控制或反制 | 满足 | P0-C07 |
| 权限默认拒绝，回放不可冒充 live | 满足 | P0-C03、P0-C05 |
| 独立审查无未解决 P0/P1且有实际测试数 | 满足 | 未解决 P0/P1 均为 0；Surefire 实测 13/0/0/0 |

**第二轮独立审查判断：`READY`。P2 均有明确归属，不阻断阶段 1 契约退出；是否允许进入阶段 2，仍以总协调的最终退出报告为准。**
