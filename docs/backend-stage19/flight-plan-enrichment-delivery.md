# 飞行计划数据补充交付（2026-09-14）

> 2026-09-15 维护归属：本文为 `houtaiguanli` 唯一后端的正式接口说明。`server/` 为本仓库目录，业务前台位于同级 `../dongyiwurenji/dongying-vue/`。正文的2026-09-14部署、数据批次、测试、页面与日志记录均为旧环境历史事实，不能据此认定新库已有该演示批次或当前页面已完成验收。迁移后状态见[2026-09-15核对清单](../旧后端剩余改动迁移清单-20260915.md)。


2026-09-14旧环境记录：localhost:5173 当时已生效。批次 `seed-refill-260915-005115` 保持 5 条待执行、2 条执行中：

- 06 号执行中计划：21 个模拟轨迹点，页面显示绿色范围内轨迹、完全匹配、实际海拔 50 米。是保存的模拟片段，不是持续实时飞行。
- 01、04 号待执行计划：大风；02、05 号：雷雨；03 号：低能见度。共 5 条模拟气象风险，均待核验，关联对应计划时段与模拟范围。
- 待执行详情新增“起飞前 · 周边设备检查”：航线周边 5 公里、正常/异常/待核查数量、设备状态、距航线距离、最近上报与告警。复用独立 MQTT 模拟设备，正常 RemoteID、故障雷达与间歇离线 TDOA；地图突出异常设备。
- 旧 36 条计划完整行聚合校验值更新前后均为 `c055912d119f82acac0e616e9dcb00d4`，没有重新移动旧计划时间或重置状态。

## 代码变更说明

| 改动文件/类 | 方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| ../dongyiwurenji/dongying-vue/src/pages/flights/components/PlanVerificationPanel.vue | preflight、模板 | 判断起飞前显示条件 | 待执行详情展示只读设备检查，核实通知仍沿用原守卫 |
| ../dongyiwurenji/dongying-vue/src/pages/flights/components/PlanDeviceCheck.vue | normalCount、模板 | 汇总和展示已检查设备 | 展示正常、异常和待核查设备，区分状态颜色 |
| ../dongyiwurenji/dongying-vue/src/pages/flights/planDeviceCheck.js | deviceCheckStatus、checkPlanDevices | 设备状态文案与位置补充 | 正常设备显示“正常”，所有行可读取位置 |
| server/.../flight/application/FlightDeviceCheckService.java | read、inspect | 范围筛选及设备事实检查 | 起飞前读取当前状态和未关闭告警，避免生成未起飞结论 |
| server/.../flight/application/FlightVerificationService.java | deviceCheck | 只读接口适用性校验 | 放行未来待执行计划的设备读取，保留写接口禁止提前核实 |
| server/.../integration/mock/LocalFlightPlanEnrichmentSeeder.java | run、plan、seedRisk、seedTrajectory、ts | 指定批次追加模拟数据 | 创建 5 条气象风险与一条轨迹，通过既有风险入库服务和规则引擎生成结果，重复运行不重写历史 |
| server/.../integration/mock/LocalStage3PlanningSeeder.java | run | 初始化基础计划 | 指定保留参数时跳过旧计划时段刷新 |
| server/.../integration/mock/LocalPendingPlanDemoSeeder.java | run | 初始化待执行样例 | 指定保留参数时跳过旧计划重置 |
| server/.../integration/mock/LocalFlightPathDemoSeeder.java | run | 初始化原轨迹场景 | 指定保留参数时跳过旧场景补种 |
| server/src/test/.../flight/application/FlightDevicePreflightTest.java | setup、state、4 个测试方法 | 设备检查回归 | 正常、故障、未知、已到时分支及历史关闭告警过滤 |
| server/src/test/.../flight/api/FlightEnrichmentPostgresTest.java | database、enrichmentPersistsFivePreflightRisksAndOneReadableTrajectoryWithoutResettingHistory | 隔离数据库回归 | 5 条风险及气象范围、21 点真实查询链、禁止提前通知、重复执行与旧计划保护 |

文档同步：`docs/flow-map.md`、`docs/backend-stage19/flight-plan-automatic-device-check.md`、`docs/后端开发基线.md`、`server/README.md`。无具体方法，描述新入口、只读结论和部署参数。

## 历史验证与边界（2026-09-14）

- 后端受影响测试 5/5 通过：4 个单元测试、1 个 PostgreSQL/PostGIS 综合回归，使用独立 `stage_flight_verify_20260914` 库。`package` 成功；实际运行 JDK 21，源码目标仍沿用工程 Java 17，未调整依赖。
- 首次 Mockito 动态附加受运行环境限制，改用已安装 Mockito 的显式 Java agent 后通过；未新增依赖。
- 前端 build、scan 与 git diff --check 通过。构建沿用本项目 Vite，通过临时 shell 包装规避现有 node_modules/.bin 外部路径，构建产物位于 /tmp。
- 运行服务已更新并启动，数据库核对新增 5 条风险、21 点轨迹与原计划状态。Chrome 新登录、执行中深链刷新与待执行深链已读取到数据；1744×1027 画面无横向溢出，只存在一个底图 canvas 和一个叠加 canvas。
- 浏览器看到绿色轨迹与待执行设备正常/故障/离线、关联雷雨风险；没有执行通知、核验保存或设备控制。未重跑全仓历史测试，也未做真实设备和真实气象联调。

后端包摘要：`e0e8a961b3a9991c01cc0b52f83385b5044f09d5ebaeec931a0f6be059fe1651`；运行日志 `/private/tmp/dongying-backend-runtime/backend-flight-enrichment.log`。更新参数为 `--preserve-existing-flight-demos --app.dev-seed.flight-enrichment-prefix=seed-refill-260915-005115`。计划仍按原时间自然进入执行中、已完成，未将状态永久锁定。

## 迁入唯一后端（2026-09-15）

新后端现行端口8081，默认开发库为houtaiguanli；不迁入旧运行数据。事件急停使用新增迁移202609150002、PostgreSQL保护202609150003；报备字段使用202609150004。旧140004/140005/140006对应内容通过新编号承接，已应用历史脚本保持不变。现行代码与87项不同环境用例验证记录见[完整迁移清单](../旧后端剩余改动迁移清单-20260915.md)；本轮未做真实设备物理停机或新页面浏览器验收。
