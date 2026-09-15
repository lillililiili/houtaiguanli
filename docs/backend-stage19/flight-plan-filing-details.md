# 飞行计划报备详情补充（2026-09-14）

> 2026-09-15 维护归属：本文为 `houtaiguanli` 唯一后端的正式接口说明。`server/` 为本仓库目录，业务前台位于同级 `../dongyiwurenji/dongying-vue/`。正文的2026-09-14部署、数据批次、测试、页面与日志记录均为旧环境历史事实，不能据此认定新库已有该演示批次或当前页面已完成验收。迁移后状态见[2026-09-15核对清单](../旧后端剩余改动迁移清单-20260915.md)。


本次补齐飞行计划右侧“计划详情与风险”的报备信息。选择计划后，可查看计划编号、飞手和报备单位、无人机 SN、起降点与坐标、时间窗口，以及报备航线名称、编号、版本、高度和宽度。原有计划对照、周边设备检查、风险记录继续使用原业务流程。

## 数据和展示契约

原 `GET /api/v1/flight-plans/{id}` 在详情中新增 `filing` 对象；列表不附加详情查询。新增字段均可缺失，JSON 沿用忽略 null 的约定，界面显示“未提供”：

| 字段 | 含义 |
| --- | --- |
| `pilot_name` | 报备飞手姓名 |
| `operator_name` | 报备执行单位，不用所属管理机构替代 |
| `takeoff_site_name` / `landing_site_name` | 报备起飞点、降落点名称 |
| `takeoff_longitude` / `takeoff_latitude` | 报备起飞位置，WGS-84，经纬度 |
| `landing_longitude` / `landing_latitude` | 报备降落位置，WGS-84，经纬度 |

无人机身份复用计划 `uav_sn`；时间窗口复用 epoch 毫秒 `start_at` / `end_at`，前端沿用浏览器本地时区展示。航线资料复用计划关联版本及既有航线版本接口，高度注明 AMSL/AGL 基准，宽度单位为米。数据库约束要求一对坐标同时存在或同时为空，经纬度在有效范围内。

详情查询继续执行 `flight:read` 及计划组织、区域范围限制；航线范围读取沿用 `route:read`。航线读取失败单独显示原因，不清空其他计划事实。真实计划不通过航线端点自动推断起降点，也不通过所属范围推断飞手单位。

“报备航线”表示计划所报航线。合法性结论继续使用既有研判；没有恢复此前 F8 已删除的外部授权文号登记，没有生成飞手执照、实名登记或批准记录。

## 本地演示补充

`LocalFlightPlanDetailsSeeder` 仅在 local 且非 production、开发种子启用并明确传入 `app.dev-seed.flight-enrichment-prefix` 时运行。默认前缀为空，不执行补数；只接受 `seed-refill-六位数字-六位数字` 批次。

当前批次 `seed-refill-260915-005115` 的 7 条模拟计划已补齐。仅更新该批次、指定模拟来源、8 个新字段全部为空的记录，已有无人机 SN 保留。模拟起降坐标明确从模拟航线端点生成。重复启动不会覆盖已有报备数据或重设计划时间；06 号计划原 SN 和轨迹绑定保留。

## 代码变更说明

后端路径相对本仓库根目录，业务前台路径指同级业务仓，仅列本次报备详情的变更。

| 改动文件/类 | 改动方法/函数 | 方法作用及本次代码作用 |
| --- | --- | --- |
| `../dongyiwurenji/dongying-vue/src/pages/flights/components/PlanFilingDetails.vue` | `value`、`time`、`position`、`filing`、`altitude`；模板/CSS 无具体方法 | 统一缺失值、时间、坐标、高度展示；分组呈现报备事实并保留关联风险入口 |
| `../dongyiwurenji/dongying-vue/src/pages/FlightsPage.vue` | 无具体方法：详情模板与组件导入 | 用新组件替换原三行计划信息，传入已选计划和航线读取状态 |
| `server/src/main/java/com/uav/lowaltitude/modules/flight/api/FlightDtos.java` | `FlightPlanDto`、`PlanFilingDto` 数据定义 | 定义可缺失的报备详情响应 |
| `server/src/main/java/com/uav/lowaltitude/modules/flight/application/FlightReadService.java` | `flightPlan`、`plan` | 在已授权详情查询中组装报备数据，列表保持原查询开销 |
| `server/src/main/java/com/uav/lowaltitude/modules/flight/infrastructure/FlightReadRepository.java` | `findFiling`、`FilingRow` | 按现有完整数据范围读取 8 个报备字段 |
| `server/src/main/java/com/uav/lowaltitude/integration/mock/LocalFlightPlanDetailsSeeder.java` | 构造器、`run` | 在明确指定的本地模拟批次中幂等补齐详情 |
| `server/src/main/resources/db/migration/V202609150004__flight_plan_filing_details.sql` | 无具体方法：字段和 CHECK 约束 | 持久化报备信息并校验坐标；迁移已应用，不再修改该版本 |
| `server/src/test/java/com/uav/lowaltitude/modules/flight/api/FlightEnrichmentPostgresTest.java` | `planDetailsExposeFilingFactsWithoutInventingMissingFields`、`enrichmentPersistsFivePreflightRisksAndOneReadableTrajectoryWithoutResettingHistory` | 验证详情、缺失值、无登录/不存在、重复补数、原 SN 绑定与历史数据保护 |

同步文档：`docs/flow-map.md`、`docs/后端开发基线.md`、`server/README.md` 和本文。

## 历史验证与生效范围（2026-09-14）

- 隔离 PostgreSQL/PostGIS 库 `stage_flight_verify_20260914` 应用迁移并运行 `FlightEnrichmentPostgresTest` 2 项、`FlightDevicePreflightTest` 4 项，通过且无失败/错误/跳过；同次 Maven `package` 成功。
- `FlightReadApiTest` 5 项通过，无失败/错误/跳过。合计本次相关测试 11 项；未宣称全量测试通过。执行使用本机 JDK 21，项目 Java 17 编译基线未改变。
- 前端 `npm --script-shell=/tmp/countermeasure-code-check/npm-shell run build -- --configLoader runner --outDir /tmp/flight-detail-dist --emptyOutDir` 成功；保留既有大包体提示。临时 shell 用于适配当前环境依赖路径，不新增仓库依赖。
- 前端源码扫描通过；`git diff --check` 通过。
- 当前本地后端已加载新 JAR，迁移 `V202609140006` 和 7 条演示补数已生效；健康接口返回 UP，数据库容器健康。
- 浏览器已实际看到 05 号计划的飞手、单位、SN、起降点坐标、时间及航线范围，同时看到原设备检查和风险记录。随后补充了标题行按钮对齐 CSS 和“航线名称”标签，最新构建通过；浏览器自动化连接在最后复查时提示 `Debugger unattached`/超时，这两处最后的视觉复查未完成。
- 其余 36 条计划排除新增空字段后的整行摘要在更新前后一致：`7d6c2266d0f6c57fb9b36c5b81820c25`。当前批次时间窗口和 06 号轨迹绑定保留。未发送通知或操作真实设备。

后续接入真实报备来源时应提供上述字段；本次没有新增报备编辑或审批接口。

## 迁入唯一后端（2026-09-15）

新后端现行端口8081，默认开发库为houtaiguanli；不迁入旧运行数据。事件急停使用新增迁移202609150002、PostgreSQL保护202609150003；报备字段使用202609150004。旧140004/140005/140006对应内容通过新编号承接，已应用历史脚本保持不变。现行代码与87项不同环境用例验证记录见[完整迁移清单](../旧后端剩余改动迁移清单-20260915.md)；本轮未做真实设备物理停机或新页面浏览器验收。
