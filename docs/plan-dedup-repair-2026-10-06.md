# 设备管理器计划重复下达修复

## 规则与范围

- 同一操作者、同一来源模式、无人机、航线和飞行时段，且申报内容相同的计划，重复提交复用原计划。消息编号与由时钟推导的 PENDING / EXECUTING / COMPLETED 不构成新的飞行任务；取消状态仍是独立业务事实。
- 不按计划名称、当前 UUID 或固定时间判断重复。新无人机、新时段、新航线、新来源或申报内容变化保持原有新计划路径；相同消息编号改变业务内容仍冲突。
- 模拟器按稳定内容生成提交编号，并从已受理记录中选择最早的等价计划。上下文只返回最近 100 条时，后端仍从持久化记录核查，不依赖页面缓存。
- 后端复用仍校验原对象权限。每个新消息编号保存独立接收回执与审计，原计划、状态及旧回执不覆盖；同一操作者的事务锁串行化并发提交。真实 live 输入仍不允许通过模拟入口创建。

## 数据修复

追加 `flight_plan_duplicate` 表，记录重复计划与原计划的关系。迁移只识别本地设备模拟器的受理记录，并同时比较原始申报内容、来源/范围和当前计划字段。生命周期状态差异可归并；来源不同、字段发生变化、取消计划及重复项已有独立风险/研判/证据/核实/回告/运维记录的情况不自动归并。

列表、总数、报表、单位计划计数、计划选择器、自动研判候选、气象与空间风险候选以及状态推进排除重复项。旧计划详情和数据库原始记录仍可读取。重放旧重复消息会返回保留的原计划。

本次本机处理两组：

| 重复项 | 保留的原计划 |
| --- | --- |
| 771cbc3c-75cd-4a6a-9ce7-d480bc0d3c96 | 30507eba-b927-46d6-9d50-a39a066b0981 |
| 1c2ada8f-e749-49c0-a8e4-adc1e7ead098 | a94603ca-c5bc-47c4-a4cc-d8b29abb7a0e |

原计划各自关联的风险保留。没有删除、改写计划行或原始消息。修复前完整数据库备份为 `output/plan-dedup-20261006/before-repair.dump`（不提交）。

迁移顺序：`202610060002` 建立归并表；`202610060003` 在开发环境编译联动期间已登记为空迁移，遵循已应用迁移不修改的约束保留；`202610060005` 执行有条件归并。`202610060004` 属于同时进行的计划编号工作，未修改。

## 验证

- 修复前：设备管理器跨状态重启测试会重复 POST，消息编号随状态生成 3 个值；列表回归返回两条；迁移回归未找到归并关系。
- 设备管理器 Python 全量 192 项通过；涵盖原始接收报文、生命周期变化、多个旧重复项、上下文记录缺失、变更日期及航线窗口。
- 后端接口 H2 20 项通过；隔离 PostgreSQL/PostGIS schema 上相同 20 项通过（含实际修复代码、再次执行的幂等性、原记录与回执保持不变、模拟来源与 live 拒绝）。
- 扩展回归共发现 143 项，97 项执行通过、0 失败，46 项因相应独立 PostgreSQL 环境变量未配置跳过。覆盖飞行计划、目录、规则匹配、空间风险、预报。`mvnw.cmd -DskipTests package` 打包通过；不将打包跳过测试当作测试通过。
- 实际运行接口：每组分别重放旧重复消息、以新消息编号提交 PENDING、以另一个新编号提交 COMPLETED，共 6 次均返回原计划；每组物理计划行数均保持 2（原计划加只读保留的重复项），没有新增计划。证据：`output/plan-dedup-20261006/live-check.json`。
- 真实浏览器点击飞行计划列表、打开原计划，已确认截图中的当日“巡检计划 1”只显示一条，详情路由对应保留的原计划。
- 后端已加载修复。设备管理器 Python 服务重启被自动审批拦截（仅返回 `blocked by policy`），因此不宣称该进程已加载 Python 修改；用户手动重启 8766 后继续验收。当前旧进程的重复请求已受到后端防重保护。
- 本次不启用开发种子；验收配置 `app.dev-seed.enabled=false`。

## 代码变更说明

| 文件/类 | 方法/函数 | 方法作用及本次修改 |
| --- | --- | --- |
| `E:/rwurenji/_rong/tools/device-simulator/fullchain.py` | `plan_identity`、`message_matches_payload`、`FullChain.prepare` | 稳定计划身份、查找受理记录；去除消息编号和时钟状态影响，优先复用最早等价记录 |
| `LocalPlanIdentity` | `businessPayload`、`same` | 比较模拟申报业务内容，保持来源与取消状态边界 |
| `LocalInterfaceSimulatorService` | `plan` | 入库前查重；复用计划、记录独立消息、重放历史重复项指向原计划 |
| `LocalInterfaceRepository` | `planCandidates`、`canonicalPlanReceipt` | 按操作者和无人机时段读取持久化候选，读取归并后的原受理记录 |
| `FlightReadRepository` | `excludeDuplicates`、`listPlans`、`countPlans`、`reportDataset` | 统一列表、统计与报表的去重范围，保留历史详情 |
| `RuleEngineRepository` | `candidatePlans`、`planSubject` | 自动规则的计划候选排除重复项 |
| `WeatherForecastRiskRepository`、`PostgisSpaceRiskSpatialAdapter` | `plans`、`observations`、`airportProximity` | 气象与空间风险查询排除重复项 |
| `FlightPlanStatusAdvanceJob` | `advanceOnce` | 仅推进有效原计划，保留重复行的历史状态 |
| `DirectoryRepository` | `ORG` 查询、`options` | 单位计划计数与选择器排除重复项 |
| `V202610060002`、`V202610060003`、`V202610060005` | SQL 无具体方法；Java `migrate`、`hasIndependentHistory` | 追加归并结构、保留已应用迁移、谨慎归并无独立业务历史的等价记录 |
| `test_fullchain.py`、`LocalInterfaceSimulatorApiTest`、`LocalPlanDedupPostgresTest`、`FlightPlanStatusAdvanceConfigTest` | 新增回归测试及隔离数据库配置 | 验证重试不增计划、来源边界、归并不改历史、查询一致和归并项不推进 |

本次不改前端业务组件，不修改并行进行的计划编号及天气风险功能。
