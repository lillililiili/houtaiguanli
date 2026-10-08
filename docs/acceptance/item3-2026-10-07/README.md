# 第三条实施与测试记录（2026-10-07）

当前状态：**第三条已按约定范围完成**。代码、相关自动化、实际报表/不反制/全屏/到点检查页面，以及隔离 PostgreSQL 风险解除页面回归均已通过。风险解除正向案例使用独立测试环境，不作为 8081 普通 replay 已支持独立评估时钟或现场真实解除的证明。第一、二条留下的未提交改动保持原样，未混入本轮完成声明。

## 1. 实施范围

| 内容 | 本轮处理 |
| --- | --- |
| 全屏 | 修复外部进入/恢复整页全屏时按钮、Pinia 状态和布局不同步；视频自身全屏不应用整页放大样式；卸载清理样式与监听 |
| 五类报表 | 在现有页面加入仅验收环境可用的模拟来源选择；正式默认 live，模拟仅 mock/replay；权限、范围、周期、明细、趋势与导出保持一致 |
| 导出 | 模拟文件名、摘要、PDF 每页及 Excel 打印页眉明确标识；空模拟报表也保持模拟标识 |
| 页面请求 | 切换来源/类型/周期清除旧结果，迟到响应不能覆盖当前筛选；来源不一致时拒绝展示和导出 |
| 无风险不反制 | 回归已有入口、资格、幂等、并发与新风险重开规则；修复依据弹窗未复用现有中文原因字典的问题，业务规则保持原样 |
| 到点检查、风险排除 | 回归现有时间、位置、状态、来源与证据边界，本轮未增加人工成功入口或降低阈值 |
| 计划及检查时间展示 | 浏览器复核发现详情使用电脑时区，与列表相差 12 小时；计划时间窗口、设备检查、核验及异常通知时间统一按北京时间显示，保存值和到点规则不变 |

未新增数据库迁移、生产依赖、菜单或独立业务流程。没有修改审批、反制、通知观察时长、历史及冻结材料。验收服务保持 `app.dev-seed.enabled=false`。

后端/管理端基线：`b529ff4`；业务前台基线：`221a12f`。两仓原有未提交修改均保留。本轮后端构建产物已加载至 8081，健康检查 UP；管理端 5175、业务前台 5173、模拟器 8766 保持运行，模拟器会话未因本轮后端重启而清除。

## 2. 环境与门禁

- Java 17，项目 Maven wrapper，离线依赖缓存；管理端/前台使用现有 npm lockfile。
- H2 测试使用 test profile；显式测试上下文的夹具/种子仅存在于隔离测试库，不作为验收服务或浏览器演示资料。
- PostgreSQL 16.9 / PostGIS，本次实际映射 `127.0.0.1:5432`。实际容器映射优先于旧文档的历史端口。
- 本轮隔离库分别为 `stage456_verify_item3_5f3ef653ca1d`、`report_test_item3_5f3ef653ca1d`、`stage_flight_verify_item3_5f3ef653ca1d`，没有将套件强行指向业务库。套件保护规则及独立 schema 保留。测试结束并检查无活动连接后，仅这三个本轮测试库已清理；日志及导出证据保留，验收业务库未清理。
- 验收服务 profiles 为 `local,qa`，显式 `APP_QA_REPORTING_ENABLED=true`，开发种子关闭；默认部署开关仍为 false，prod/production 无条件禁用模拟报表。
- 本轮没有调用真实设备动作、向真实联系人发消息，未扩展账号权限。

## 3. 自动化结果

数字为每次实际命令的执行数量；重跑的用例有重叠，不相加为唯一用例数。日志保留在本机 `server/target/item3-20261007/`，不提交运行产物。

| 阶段 | 实际数量 | 失败 / 错误 / 跳过 | 结果及日志 |
| --- | ---: | --- | --- |
| 修改前相关后端基线 | 62 | 0 / 0 / 0 | `baseline.log` |
| 新报表复现（修改前） | 9 | 7 / 1 / 0 | `report-red.log`；7 个预期行为失败；另 1 个测试夹具 ID 超长错误随后修正，不能算业务缺陷 |
| 新管理页面复现（修改前） | 7 | 2 / 0 / 0 | `admin-red.log`；新增模拟切换用例失败 |
| 全屏复现（修改前） | 5 | 2 / 0 / 0 | 工具输出；新增恢复整页/视频全屏用例失败 |
| 后端报表及门禁回归 | 56 | 0 / 0 / 0 | `report-green.log` |
| PostgreSQL/PostGIS | 96 | 0 / 0 / 0 | `postgres.log`；实际执行，无条件跳过 |
| 管理端 Vitest 全套 | 261（43 文件） | 0 / 0 / 0 | `admin-tests.log` |
| 前台 Node 契约全套（首次） | 119 | 0 / 0 / 0 | `front-tests.log`，包括全屏 5 例 |
| 不反制原因展示复现（修改前） | 2 | 1 / 0 / 0 | 工具输出；实际原因码 `INSIDE_RESTRICTED_AIRSPACE` 未显示现有中文文案 |
| 不反制原因与相邻告警回归 | 3 | 0 / 0 / 0 | `noCounterBasis.test.cjs` 与 `alarmEscalation.test.cjs` |
| 前台 Node 契约全套（原因修复后） | 121 | 0 / 0 / 0 | `front-tests-final.log` |
| 飞行详情时区复现（修改前） | 8 | 4 / 0 / 0 | `flight-time-red.log`；4 个跨时区用例失败，4 个空值用例通过 |
| 时区与相邻计划筛选回归 | 9 | 0 / 0 / 0 | `flight-time-green.log`；纽约、UTC、上海三个系统时区，含跨日与冬季时间 |
| 前台 Node 契约全套（最终） | 129 | 0 / 0 / 0 | `front-tests-delivery.log` |
| PostgreSQL 风险解除浏览器夹具 | 1 | 0 / 0 / 0 | `risk-browser.log`；同一风险先在范围内，再依据新实测位置持久化解除；保留全部通知与冻结材料，重复调度仅一份解除证据 |

管理端 lint/build、前台 scan/build、后端 package 均成功。最后一处时区修复后，前台 scan/build 再次成功（`front-scan-delivery.log`、`front-build-delivery.log`）；第一次构建因相邻仓库写入权限报 EPERM，授权执行后完成，不隐去该环境失败。前台构建保留既有大 chunk 提示，未为本轮引入依赖或改构建方案。打包使用 `-DskipTests`，测试结果来自上表独立执行，不将打包计作测试。两仓按项目现有 Git 换行配置运行 `git diff --check` 均为 0，两个新增 CJS 文件语法检查通过；收尾后端健康检查为 UP。

实际命令（Maven 仅在 `server/`，管理 npm 仅在 `ruoyi-ui/`，前台命令仅在 `dongying-vue/`）：

```powershell
# 后端基线
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=NoCounterRulesTest,NoCounterApiTest,BusinessReportingApiTest,BusinessReportExportTest,ReportPeriodResolverTest,FormalReportDatasetTest,FlightDevicePreflightTest,FlightVerificationApiTest' test
# 修改后后端
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=AcceptanceReportingApiTest,BusinessReportingApiTest,BusinessReportExportTest,ReportScopePolicyTest,ReportPeriodResolverTest,FormalReportDatasetTest' test
# PostgreSQL：使用不同保护前缀的隔离库，变量由本机运行脚本注入，不含业务库
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=NoCounterPostgresTest,RiskClearancePostgresTest,FlightVerificationPostgresTest,AcceptanceReportingApiTest,BusinessReportingApiTest' test
# 独立 PostgreSQL 页面回归，需按下文检查前后状态后显式结束夹具
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=RiskClearanceBrowserFixtureTest#serveClearanceMatrix' '-Dqa.risk.clearance.browser=true' test
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' -DskipTests package
# 管理端
npm test
npm run lint
npm run build
# 业务前台
node --test tools/*.test.cjs tools/*.test.mjs
node tools/scan.cjs
npm run build
```

PostgreSQL 分项：不反制 12、风险排除 45、飞行核验 5、模拟报表 9、正式报表及门禁 25。`ReportScopePolicyTest` 另覆盖 13 组 profile/开关组合，包括同时存在 test 与 production 时禁止模拟口径。

## 4. 导出验证

正式接口测试实际生成五类 XLSX/PDF，保存于 `target/item3-20261007/exports/`。测试核对每页 PDF 的模拟标识、各 Excel 页签打印页眉、文件名、HTTP 禁缓存、预览/明细来源一致与权限。五类 PDF 首页已渲染并人工视觉检查，标题、警示、图表及页码可读。另用 openpyxl/pypdf 只读复核全部 23 个工作表、15 页 PDF，未发现公式单元格或缺失模拟页眉；结构记录为 `export-verification.json`。openpyxl 对现有 POI 输出提示未设默认命名样式，未影响文件读取，未因此修改原有报表样式。

这些文件来自**隔离自动化测试夹具**，仅证明导出格式和契约，不能宣称为验收业务库的真实运行统计，也不能充当浏览器点击下载证据。实际业务库页面下载另列验收记录。

### 实际浏览器下载

2026-10-07 使用现有授权账号在管理端真实页面点击下载：五类模拟月报共 10 个 XLSX/PDF，以及正式事件处置周报 XLSX/PDF 共 2 个文件。文件来自浏览器默认下载目录，并复制到本机 `target/item3-20261007/browser/downloads/` 留证；没有用接口测试导出替代点击下载。下载事件工具曾等待超时，但磁盘实际文件已落地，随后按文件内容核验，不将工具超时记录为业务下载失败。

- 五类模拟 PDF 共 23 页，逐页有模拟验收标识；Excel 共 23 个工作表，打印页眉标识完整。
- 页面与导出抽查计数一致：综合运行目标 36、告警 15、风险 210、计划 24、事件 15；设备 16、运维 2；处置授权 11、移送 3。此快照在下面“不反制”新批次之前，后续计数可正常增长，不以旧数字作为固定验收断言。
- 正式事件处置周报为零记录，标题及文件内容为正式真实来源口径，没有混入模拟记录。
- 日/周/月切换、快速切换正式/模拟及刷新后默认正式口径已在页面实测。
- 文件核对记录：`browser/download-verification.json`；页面截图：`overview-simulated.png`、`disposal-simulated.png`、`formal-after-refresh.png`。

## 5. 已保留的业务边界

- “确认无风险并不反制”是有权限人员对当前事件的决定，不将非法研判改为合法，不删除告警和已有通知。不具备当前充分依据、存在有效授权或未完成停机核查时继续阻断。新可靠风险可以重开，旧决定只读保留。
- 到点设备检查区分正常、异常、未知；没有观测不能确定未起飞。到点状态推进不等于自动完成核验或自动通知。模拟来源桥接仅按原本地规则生效。
- C04 风险排除要求持久化、唯一来源的有效实测位置及原有空间/时间证据。replay 缺少独立评估时钟时仍阻断；停止上报、预测点、未知位置不能判为排除。气象窗口结束是 EXPIRED，不能改成 CLEARED；送达/签收也不是风险排除。
- 本轮模拟报表只按来源及现有时间/权限查询，不新增批次查询或将“不同业务对象数量”相加成事件总数。使用已有业务资料时，页面计数可能包含同周期此前模拟批次，演示时需说明。

## 6. 浏览器验收与隔离页面回归结果

| 路径 | 2026-10-07 实际结果 |
| --- | --- |
| 五类报表、来源和周期切换 | 已通过；汇总、明细、标识及实际导出内容一致，刷新默认正式 |
| 浏览器拒绝自动进入全屏 | 正确保留普通布局并提示失败，没有假切换按钮状态 |
| 人工点击进入全屏 | 用户操作后回读按钮为“退出全屏”、整页布局标记生效；已保存截图 |
| 全屏内业务页切换、大屏返回 | 空域→飞行计划保留整页状态；进入独立大屏时无遗留整页布局标记，返回系统恢复正确 |
| 点击退出全屏 | 实际退出后按钮恢复“全屏”，整页布局标记清除；已保存截图 |
| Esc 退出 | 工具按键未有效触发浏览器退出；随后用户在新标签人工点击全屏并按 Esc，明确回复“已验证，Esc 正常退出”。页面回读按钮为“全屏”，按人工辅助实测通过记录，不将先前工具按键计为成功 |
| 不反制依据已更新 | 提交旧依据被明确拒绝，没有保存旧决定或伪造成功 |
| 不反制当前依据提交 | 已通过；显示“已决定不反制／本次处置已结束”、真实操作者及时间，原告警与通知历史保留，自动移送为“不需要自动移送” |
| 不反制原因文案 | 实测发现英文代码，先补失败用例后复用共享字典；重新打开弹窗已显示“进入禁飞/限制空域” |
| 不反制刷新持久结果 | 旧标签控制连接中断后，已在新标签重新登录并点击刷新；同一决定、人员、时间和模拟通知历史完整保留，后续同风险观测未错误重开，仍无关联设备指令 |
| 到点设备检查页面 | SIM-000005 未匹配到目标时实际读取周边 11 台离线设备，点击重新检查后仍显示异常 11／正常 0／待核查 0，并提示“是否起飞待报送单位确认”，没有把未匹配当作已确认未起飞 |
| 计划与检查时间页面 | SIM-000025 详情时间窗口从电脑时区 10 月 6 日 12:00–10 月 7 日 11:59 修复为与列表一致的北京时间 10 月 7 日 00:00–23:59；设备上报和检查时间也按北京时间显示 |
| 风险排除页面边界 | 风险-1006-207 基于预测或插值位置，实际显示“状态待确认”，不计入当前风险，历史保留；人工核验弹窗仅检查入口后取消，未无依据地提交排除或确认 |
| 到点设备未知分支 | 实际 8081 / 5173，SIM-000027 周边一台新 replay 雷达；MQTT 在线但没有健康事实时，正常 0／异常 0／待核查 1，不能排除设备异常 |
| 到点设备正常分支 | 通过已有授权 `device-status` 入口提交显式 `LOCAL_QA_STATUS`，正常 1／异常 0／待核查 0；实际点击重新检查，仍为“疑似未按计划起飞，待报送单位确认”，未替代人工核验 |
| 风险解除正向页面回归 | 独立随机端口后端 + 5178 前台 + 独立 PostgreSQL schema；同一风险由 CURRENT → 新实测点 → C04 持久化证据 → CLEARED，刷新后显示“已确认解除”；通知与回执页仍为“已送达／已回执”，原历史与冻结材料逐字段相同 |
| 过期观测页面回归 | 同一隔离环境另一风险保持 UNKNOWN，页面明确“没有风险发生后时效内的新实测位置”，不因已通知或测试推进而解除 |
| 视频实际解码与原生全屏 | 实际 8081 / 5173，960×540 HLS 动态测试画面，readyState=4；播放时间 27.390 → 52.576 → 117.626 秒持续增长。原生全屏时视频占据 1920×919，顶栏仍为“全屏”，body 未加整页放大类 |
| 视频退出、收起与结束 | 原生按钮退出后播放器恢复约 614×345，普通布局保持；收起后 video 元素数量为 0，跟踪仍继续；“暂停并结束”后平台显示自动追踪已暂停，模拟端跟踪及推流均 STOPPED |

全屏证据：`browser/fullscreen-entered.png`、`browser/fullscreen-exited.png`。不反制证据：`browser/no-counter-basis-before.png`（修复前）、`browser/no-counter-saved.png`（成功后）、`browser/no-counter-relogin.png`、`browser/no-counter-refreshed.png` 及同名文本。计划检查与风险证据：`browser/flight-device-check.png`、`browser/flight-device-check.txt`、`browser/risk-unknown.png`、`browser/risk-unknown.txt`。新标签复核的控制台错误/警告查询为空。工具连接问题不会回写业务状态，也不作为页面通过证明。

### 不反制可重复场景

模板为同目录 [no-counter-scene.json](no-counter-scene.json)，通过现有模拟器导入。运行前读取已有授权资料，将 `fullchain.filing` 配成当前有效的 `source_id`、`source_binding_id`、`operator_org_id`、`pilot_contact_id`；空模板不会伪造飞手核验或单位绑定。保留模板的模拟标识和独立批次，不复用其他目标的事件。

本次实际批次 `sim-1007015950-833b`，使用既有 `item2-tdoa` 回放设备、标准化观测入口和本批次计划/空域。目标 `cbac603d-9d1d-4a33-9414-c93543c75443`，告警 `09bee92a-e3f6-463d-a613-f267b562901c`（告警-1007-006）。观测实际得到 ILLEGAL、MEDIUM、进入限制空域的当前充分依据；人工核实后提交不反制。该输入不作为协议 A 已提供 SN/高度基准的证明。

运行步骤：

1. 确认原场景和收发模块均未运行，保存当前收发配置；本轮设 `continuous=false`、`countermeasure_enabled=false`，不启动受控设备动作。
2. 导入完整配套场景并启动，等待平台真实观测、研判、告警及当前充分依据；在告警页核实该事件。
3. 打开“不反制”，核对中文依据及当前观测。如果研判已更新，按提示重新打开当前依据；本次额外验证了旧依据拒绝分支。
4. 本次提交前短暂暂停生成，仍在既有新鲜度窗口内提交当前依据，提交后恢复；未放宽新鲜度，也未允许旧依据。运行持续观测时同样受既有版本冲突检查约束。
5. 观察保存后的状态与历史；刷新页面核对持久结果。不要把“不反制”当作合法性改判、飞离或物理风险排除。
6. 场景最多 8 分钟，结束后停止收发，恢复保存的配置，不删除业务历史或自动重启原场景。

本次场景实际自然结束于 480 秒，累计发送 423 次，无发送错误，实时收发为 STOPPED；原配置已回读比对恢复。记录见 `no-counter-stopped.json`。服务、模拟器登录及历史记录保留。

### 补充场景、证据和停止步骤

可导入模板：[video-device-check-scene.json](video-device-check-scene.json)。与不反制模板相同，先通过现有授权资料补齐 `fullchain.filing`，保存原收发配置，停止其他批次后再启动。它沿用本机光电/TDOA，增加远离旧设备组的独立模拟雷达；没有反制指令。固定设备编号允许重复运行复用登记，目标与证据仍按新批次处理。

本次批次 `sim-1007024626-b303`。视频目标 `cb22a22a-7ec2-4001-96fc-efcd506e76d7`，计划 SIM-000026，光电任务 `3579b328-4ea5-4c37-be19-2ecbd9198225`。通过原视频控件播放、进入/退出全屏、收起，然后通过原“暂停并结束”入口结束任务；没有把跟踪受理或视频注册当作播放成功。原生视频 Esc 自动按键没有触发退出，实际退出由原生全屏按钮完成，未将工具按键尝试计为成功；整页 Esc 另有前述用户实测。

到点检查另外通过既有上级模拟计划入口创建 SIM-000027，起止点为同一独立雷达附近，使用未观测的模拟 SN、AMSL 高度、当前时间窗口、既有单位/区域和模拟飞手。先读取未知健康结果，再提交当前来源匹配、非未来时间的独立模拟健康事实；这不是协议 A 提供健康字段的证明，也不代替运维异常恢复。输入/回执见 `device-check-request.json`、`device-check-created.json`、`device-health-request.json`、`device-health-receipt.json`。

停止时先核对本批次光电跟踪与推流为 STOPPED，再停止全部收发、恢复并回读原配置。实际在 614.6 秒停止，累计 1776 次发送，无错误，原配置恢复一致。`final-stopped.json` 记录最终状态；不删除业务记录，不自动重新启动旧场景。8081、5173、5175、8766 服务保持运行。

补充页面证据在同一 `target/item3-20261007/browser/`：`flight-check-unknown.png`、`flight-check-normal.png`、`flight-check-after-good.txt`、`risk-isolated-before.png`、`risk-isolated-cleared.png`、`risk-isolated-notice-preserved.png`、`risk-isolated-unknown.png`、`risk-isolated-manifest.json`、`video-playing-a.png`、`video-fullscreen.png`、`video-exited.png`、`video-decoding.json`、`video-stopped.txt`。设备检查位于详情滚动区域，整页截图未展示全部检查内容，正常结果以 DOM 文本及刷新回读核对；点击瞬间“更新中”不计为最终结果。视频全屏以实际画面、尺寸和连续解码证明，未依赖工具未暴露的 `fullscreenElement` 值。

### 隔离风险回归复现与保留边界

1. 使用全新 `stage456_verify_item3browser_<随机值>` PostgreSQL/PostGIS 库，注入 `POSTGRES_TEST_URL/USER/PASSWORD` 后执行上表夹具命令。仅 `test,postgres-test` 测试上下文使用测试夹具，8081 验收服务的 seed 开关始终关闭。
2. 从 `target/risk-clearance-browser/manifest.json` 读取随机后端端口；前台用 `APP_API_PROXY_TARGET` 指向该端口并在独立 5178 启动。通过正常登录页进入隔离测试账号，不复制验收会话。
3. 检查 `advance_risk_id` 当前仍存在且已回执；创建夹具的 `advance` 信号文件，由测试输入新的实测点并调用原 C04 处理。页面刷新后应已解除，通知与回执页保留已送达和已回执；另一个过期观测风险仍未知。
4. 创建 `stop` 信号文件，测试自动核对各状态、单份解除证据及全部冻结历史不变。确认 Maven 实际 1 项通过、0 跳过后关闭测试页面/前台，检查无活动连接并只删除本轮随机测试库。此次环境已清理，证据与日志保留。

Chrome 新建隔离标签曾出现自动点击不生效；切换内置浏览器正常点击后完成通知页核对。原 Chrome 验收标签随后可正常操作，到点检查及视频均在该标签实测。连接故障不计作业务通过，也没有为此修改权限或业务代码。

第三条没有剩余必测项。普通 replay 无独立评估时钟的风险解除限制、真实上级接口缺少文档/联调资料、真实设备执行及现场视频仍按原边界保留；本轮完成的是约定的功能修复和模拟/隔离验证，不宣称这些外部能力已完成真实接入。

最终收尾：补充场景经原模拟器 `compile_scene` 校验为 3 台设备、1 个目标，无忽略设备；文档链接有效，两仓 `git diff --check` 均为 0。8081 健康 UP，5173/5175/8766 实际 HTTP 均为 200。补充验证没有修改生产代码，因此未重复运行此前已通过的完整构建。浏览器控制台本轮视频及设备检查未捕获 error/warn。
