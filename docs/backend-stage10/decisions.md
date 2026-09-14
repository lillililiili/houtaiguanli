# 阶段 10（B 线收尾与联调准备）自动决策记录

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| 10-1 | `fusion_event` payload 只放最新状态摘要，不放原始观测；`altitude_datum` 固定 `UNCONFIRMED` 直到客户答复高度基准 | 阶段 8 原则；A 的光电跟踪触发只需位置/速度/高度原始值 |
| 10-2 | kind_code CHECK 收紧放迁移 074，H2/PG 同一句，先 UPDATE 归一再重建约束 | PG 上 065 已归一存量行，再 UPDATE 一次幂等；H2 没有存量行 |
| 10-3 | 未映射路径返回 404 `NOT_FOUND`，不回显路径 | 避免暴露探测信息；先鉴权语义不变（未登录仍 401） |
| 10-4 | 凌云 MQTT 回放导出文件放 `docs/直连接入计划/`，由生成器可重复产出，不入 `resources` | 给 A 的 P1/P3 联调输入物，不是运行期资产 |
| 10-5 | 迁移 074 只做 `DROP CONSTRAINT` + 重建五值 CHECK，**不含 UPDATE**；若库里仍有旧值行，`ADD CONSTRAINT` 以 PostgreSQL 自带的"violated by some row"明确失败，运维按 065 的方式归一后重跑 | 审查 10 第 1 轮 P2-1：PG 上接替式触发器会拒绝任何非 0 行 UPDATE；H2 只在测试用且迁移先于种子，没有旧值行；本地/生产都是 PG，065 已归一存量，种子在 10.2 改为五值后不再产生旧值 |
| 10-6 | 凌云 MQTT 导出器保持 `@Profile("local")` + `app.replay.export.enabled` 双门禁，运行需要一个 PostgreSQL（临时库跑完即删），不做独立 main | 导出是一次性联调输入物，独立 main 或无数据源 profile 的维护成本不值；生成物随阶段 10 提交入库供 A 使用 |
| 10-7 | 导出文件的 `payload` 是字符串而非内嵌对象，A 必须原样发布不再序列化 | 任何一方重新序列化都会改变键序/空白，`payload_hash` 就对不上 |
| 10-8 | 导出行保留第六个键 `source`（该报文按契约 §2 应落成的 inbox `source`），契约 §8 补写其用途 | 审查 10 第 2 轮 P2-1：它让 A 能核对自己适配器写出的信封是否与 B 期望一致；不发布、不进 payload |
| 10-9 | 加一条用例把仓库里的 `stage85-lingyun-demo.mqtt.ndjson` 与导出器 `lines()` 逐行比对，数据集变更必须同时重新导出并提交 | A 手里的文件必须与库里的哈希对得上；"导出器与 inbox 一致"保证不了"仓库文件是最新导出" |
| 10-10（修订） | `out-of-order` **只在 local profile** 放开（保持阶段 9 的做法），主配置/生产维持 Flyway 缺省严格 | 审查 10 第 6 轮 P2-1：需要乱序的只有已升级过的开发/验收库；全新生产库有序应用两批迁移不需要放开，而对生产放开会让漏发迁移事后静默补上。验收 jar 以 local profile 启动，已覆盖 |
| 10-11 | `fusion_event` payload 的 `class_code` 在融合选源无类别时回退到 `target.object_type_code`；两者都无则不出键 | demo-v1 权重下 TDOA/AOA/DCD/RID 单源目标永远没有融合类别，A 的光电跟踪触发需要页面看到的同一类别 |
| 10-12 | PostgreSQL 上 074 遇旧值行失败时 Flyway 不留失败记录（事务 DDL 整体回滚），以 SQLSTATE 23514 + 约束名为准；用例按此断言 | 助手实测 Flyway 10.20 在 PG 上回滚不写历史行，"记录失败"的说法只对非事务 DDL 成立 |
| 10-13 | 新增 PG 专属迁移 `db/postgresql/V202609050073_5__stage10_airspace_kind_code_renormalize.sql`（与 065 同法归一旧值），版本夹在 073 与 074 之间 | 领导用新 jar 启动验收库 `uav_stage85_verify` 时 074 以 "violated by some row" 失败：065 之后旧版种子又写入了旧值，所有已有库都会如此；一次性订正、注释禁止复制此写法。10-5 的"明确失败"行为与 10-10 的乱序迁移都在真实库上得到印证 |
| 10-14 | 自阶段 10 起，B 线新迁移改按实际日期编号（`V2026MMDDnnnn`，与 A 一致），不再沿用 `20260905` 固定前缀 | 根因是 B 的固定前缀永远小于 A 的日期编号；改为真实日期后顺序单调，生产无需乱序 |
| 10-15 | B 线每阶段的计划文档允许提交到 `docs/superpowers/plans/`（不再列入排除清单）；`.superpowers/`、`.planning/`、`.claude/`、`.worktrees/`、根目录 docx、`设备资料/` 仍排除 | 用户要求每阶段计划落盘可事后审阅（记忆 feedback-plan-mode-per-stage）；该目录已有 12 份计划随合并入库，口径统一为"计划入库、过程台账不入库" |
| 10-16 | 协议 A `objects: []` 视为合法"无观测帧"：映射返回空帧、inbox 记 DONE，不再当错误帧（与协议 C 心跳同一处理） | 协作者 A 确认 P1 会写入空列表；原实现在 `LingyunSenseDataMapper` 对空 objects 抛错会把 inbox 刷成 FAILED 并累计 `fusion_attempts` |
| 10-17 | 073.5/074 不重新编号；生产首次部署必须从全新库开始（按版本序无乱序问题）；若确需在"已迁到 202609070001 之后"的非 local 库上升级，一次性使用 `spring.flyway.ignore-migration-patterns=*:ignored` 或临时 `out-of-order`，并记入部署文档 | 助手复现：非 local 库会在 Flyway 校验阶段直接失败（"resolved migration not applied"），不是静默跳过；重新编号会让已应用这两支的开发/验收库反向失败；当前没有生产库，10-14 已从根因消除后续风险；`Stage9PostgresTest` 用例 24 钉住"要么补上要么响亮失败，不得静默跳过" |
