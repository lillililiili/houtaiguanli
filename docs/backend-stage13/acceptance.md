# 阶段 13（处置授权域）验收记录

日期：2026-09-08。基线：`main@3a497a1`。计划 `docs/superpowers/plans/2026-09-07-collaborator-b-stage-13-disposal-authorization.md`，契约 `disposal-authorization-api-contract.md` v1.0，决策 13-1…13-26。

## 执行者报告
| 任务 | 会话 | 结果 | 报告 |
| --- | --- | --- | --- |
| 13.1 后端域 | Session 1 | 最终 9 类 82 例（Api 22 · Rules 17 · HandoffApi 16 · Execution 7 · HandoffPunishment 6 · Gateway 5 · Seeder 5 · Numbering 2 · Stage13Contract 2）；迁移 0102 + R__；四分预检在调 A 之前（13-29）；`execution_block_reason` 只在 APPROVED 下推导；TARGET 主体别名解析 + C03 新鲜度门槛（13-24/13-31）；姓名回填（13-26）；种子无设备降 MANUAL（13-27）；处罚交接 409 `HANDOFF_MATERIALS_NOT_DEFINED`（13-25）；未验证 PG（无环境变量，由助手与领导补） | `task-13.1-report.md` |
| 13.2 前端 | Session 2 | 2 新 6 改，四值字典与共享 `disposalStatusText`；KPI 按进行中（13-18）；干扰共用入口（13-19）；联调单人路径 DONE（申请 MANUAL/LINGYUN_B、撤销、两人规则 409、状态机 409、处罚交接 409 文案一致），据联调修四处契约不一致（policies 形状、LINGYUN_B 设备必填、事件流裸数组、event_kind 字典）；审批/执行/四值显示需第二账号（13-33） | `task-13.2-report.md` |
| 13.3 到期任务 + PG + 隔离 | Session 3 | 第四轮 `Stage13PostgresTest` 9/9（PG 16.9：并发审批 APPROVE 事件恰一条、并发申请同主体一成一、编号并发 20 唯一连续、到期多实例每条恰一条 EXPIRE、执行受阻四分各一支且受阻不推进状态）+ `ProductionStage13SeedIsolationTest` 3/3；`DisposalExpiryJob`；发现 0102 JSON 拼接（13-15）与种子无设备崩溃（13-27）；四分用例最易失败方式是"某一支从没被触达却返回另一支合法值"（夹具需 ops 设备 id + `device_business_scope` 行） | `task-13.3-report.md` |

## 领导验收
- 前端（2026-09-08 00:40，E2 冻结后）：`npm run build` 通过（仅既有 chunk >500 kB 提示）；`node tools/scan.cjs` 全部通过；`check-ui-text.sh` 对阶段 13 触及的 6 个文件共 9 处命中，逐条对照 `git diff -U0` 均为既有行（阶段 13 新增的用户可见文案零命中；唯一匹配的新增行是一条代码注释），沿阶段 12 结论留给文案专项。
- 升级路径（2026-09-08 00:24）：新 jar 起在阶段 10 验收库 `uav_stage10_verify`（Flyway 由 202609070010 → 0080–0084（A）→ 0101/0102（B）共 8 个迁移 + `R__stage13_disposal`，131 ms）。首次启动在阶段 8 回放种子处失败：`SOURCE_MESSAGE_CONFLICT: lingyun:radar:S85R1#0 已存在且哈希不同`（`1692e10` 改了 SenseData 的 deviceId，旧构建灌的同键报文哈希不同）。领导修种子"已灌过即跳过"（13-30），重启后种子告警跳过、应用正常；`/actuator/health` 200，未登录读 `/disposal-policies` 401；登录后策略 demo-v1（DEMO）可读，种子 3 条授权可读（9001 因库里无已启用 `ops_device` 按 13-27 降为 MANUAL，且被到期任务按固定种子时钟判为 EXPIRED——种子时钟固定在 2026-09-07，属预期的状态卫生，不改）。
- H2 全量（2026-09-08 00:41，E1/E2/助手全部冻结后，`/private/tmp/dongying-mvn.sh test`）：131 类 / 710 run / 0 fail / 0 err / 83 skip，BUILD SUCCESS；skip 全为 env 门禁的 PG 类（见下行清单）。
- PG 回归（2026-09-08 00:34–00:50，PostgreSQL 16.9 / PostGIS 3.5，隔离库 `stage456_verify_s13` 新建；`TargetReadPostgresApiTest` 按其既有守卫用 `stage2_target_verify_s13`）：首轮 12 套件中 `Stage9PostgresTest` 两个升级用例（只迁到 073.5/074）撞 `R__stage13_disposal.sql`（表未建），修守卫（13-32）后重跑；最终 12 套件 83/83：Stage2Compat 3、TargetRead 2、FlightRead 1、AirspaceRead 3、Stage4 5、Stage5 5、Stage7 8、Stage8 7、Stage85 15、Stage9 24、MqttP1 1、Stage13 9。
- 前端复检（E2 联调修改后，01:1x）：`npm run build` 通过、`scan.cjs` 全部通过、文案检查 8 个文件 9 处命中仍全为既有行。
- 浏览器（新 jar 8081 + Vite 5174，admin1 经 API 登录注入会话，不在表单输口令；Browser 面板未显示故不能截图，用 DOM 文本与 `scrollWidth` 度量）：
  - 告警页：种子事件"告警-0907-013（已核实，待处置）"选中后处置流程条显示"联动反制 待审批 / 信号干扰 待审批"（读到 E2 建的两条 REQUESTED），动作坞出现"发起联动反制"（可用）与"通知处罚部门"（禁用，阶段 4 未接入）；申请弹窗文案：两人规则说明、演示策略说明、时限"联动反制 30 分钟 · 信号干扰 30 分钟"、三通道说明（凌云 B 可自动执行 / 4CH 只能登记人工结果 / 人工执行）、设备编号"经设备执行时必填"。未选中已核实事件时流程条为"尚无授权"。
  - 处罚页：交接清单与详情正常；"反制与公安信号干扰授权记录"区块对 RISK 主体显示"该处置对象尚无授权记录"（正确）；正面渲染需要挂在无人机事件上的交接，而本期处罚交接 409（13-25），故未验到——下一阶段材料包落地后补。
  - 工作台：事项显示"下一步：联动反制"。态势页：驱离入口在地图气泡按钮（`data-tip-act="drive"`），需悬停目标气泡，未在无头模式下触达；`TARGET_NOT_ACTIVE` 以 H2 用例为证据。
  - 三视口 1280/1366/1440：告警/处罚/工作台/态势四页 `scrollWidth == innerWidth`，无横向滚动；控制台无新增错误（仅有 E2 编辑期间的 HMR 旧错误）。
  - 单人可达的联调路径由 E2 跑通（申请 MANUAL 201 / 申请 LINGYUN_B+device 201 / 撤销 200 / 两人规则 409 / 状态机 409 / 处罚交接 409 `HANDOFF_MATERIALS_NOT_DEFINED` 文案一致）。**审批→执行→四值显示的浏览器验证未做**：第二审批账号造不出来（13-33），以 `Stage13PostgresTest` 9/9（含四分、并发审批）与 H2 82 例为证据；用户可执行 `docs/backend-stage13/fixture-approver-role.sql` 建角色后在系统管理页建账号补验。
- `git diff --check` 通过。（申请→审批→执行→处罚页记录→交接 409 文案）/ 三视口

## 未接入 / 已知限制
- 处置动作权限（`disposal:*` ACTION 码）没有角色矩阵授予入口，只有超级管理员持有，且超级管理员只能一个 → 两人规则在产品内暂无法由两个真实账号满足（13-33，既有缺口，列入小接线）。
- 授权的 `device_id` 是 A 的 ops 设备 id、无外键（13-12 id 空间不同）；申请时不校验设备存在，执行时由四道预检兜住。
- 处罚页授权记录区块要有挂在无人机事件上的交接才会正面渲染；本期处罚交接恒 409（13-25），下一阶段补。
- 阶段 13 种子时钟固定在 2026-09-07，本地开到期任务后样例 9001 会立即变 EXPIRED（预期）。
- 本期经协议 B 的自动执行全部被 A 拒（指令码族未开放，等厂家确认设备类型缩写）；`execution_block_reason=PROTOCOL_NOT_OPENED`；A 开通后不改代码生效。
- 急停：设备协议未提供，`device_stop_result=EXECUTED` 不可达（13-23）。
- 处罚交接材料包未定义（13-25），归下一阶段。
- 策略 demo-v1 全部 DEMO（Q5）。
- 后续：`services/workbenchEvents.js` 的兼容导出（`advanceUav/verifyUav/actRisk/openDeviceReboot/verifyDeviceRecovery`）全仓无调用点，下次清理一并删除（E2 报备，本阶段不动）。

## 跟进（提交 `cbf2a3e` 之后）
- 审查第 13 轮 P1-1 / E1：`R__stage13_disposal.sql` 的 `to_regclass` 守卫在"先停在中间版本再前进"的库上会静默不建触发器（R__ 被记为已应用后不再重跑）。修法：触发器与 CHECK 移入版本化 `db/postgresql/V202609070103__stage13_disposal_pg.sql`，R__ 只留函数（13-32 修订）。
- 证据：PG `Stage13PostgresTest` 9/9、`Stage9PostgresTest` 24/24、`Stage5PostgresTest` 5/5（`stage456_verify_s13`）；升级路径：新 jar 再次起在 `uav_stage10_verify`，Flyway 应用 2 个迁移（R__ 校验和变化重跑 + 0103），`flyway_schema_history` 有 `202609070103 stage13 disposal pg` success，`pg_trigger` 存在 `trg_stage13_disposal_event_append_only`，`/actuator/health` 200。
- 助手补"074 → 最新 → UPDATE/DELETE 报 23514"的分两步升级用例（见 13.3 报告第五轮）。
- 助手第五轮：`Stage13PostgresTest` 10/10（分两步升级用例断言落在行为上：UPDATE/DELETE 报 23514，不查 `pg_trigger` 同名对象）；R__ 依赖扫描：被引用最晚的表建于 064，加载 `db/postgresql` 的部分迁移停点最早为 073，当前无暴露面但属巧合——判据：R__ 只能引用不晚于最早停点所建的表；根治是 13-32 修订的规则（依赖具体表的 DDL 一律版本化）。
