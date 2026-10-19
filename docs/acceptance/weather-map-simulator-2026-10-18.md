# 气象预报范围落图：设备模拟器接入

实现范围：通过设备模拟器提供明确的 WGS84 预报范围和可选降雨强度，业务地图以低对比度云雨纹理和细风向线展示。风险详情地图默认强度18%，滑杆可调8%—30%，云雨和风场可独立开关；道路、航线和风险标记保持可读。只有区域名称的旧预报显示“边界未提供”，不补造行政区范围，不修改历史。

## 使用入口

1. 登录设备模拟器 `http://127.0.0.1:8766`，进入“资料输入 → 气象预报输入”。
2. 填写实际模拟任务所属区域和预报时段，点击“在地图绘制预报范围”，至少设置3个顶点并确认。每个时段可填写降雨强度，空值保持未知，0表示明确无降雨。
3. 提交预报。平台按既有区域、任务时段和风险规则，并检查航线走廊与输入范围的水平交集，生成或关联风险。
4. 在业务前台“飞行任务 → 全部风险事件”选择对应气象风险。查看原始预报范围，切换强度/隐藏，并展开预报详情。

范围为场景明确提交的模拟预报范围，不是行政区边界，也不是逐点实测雨量。没有高度分层时不代表所有高度均受影响。批量场景可明确填写 `fullchain.weather.polygon` 和 `precipitation_rate_mm_h`，旧场景缺范围不会自动推算。

## 代码变更说明

业务前台和模拟器文件位于 `E:\rwurenji\_rong`；Java文件位于本仓库 `server/src/main/java/com/uav/lowaltitude/modules`。

| 改动文件/类 | 改动方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| `risk/domain/WeatherFootprint.java` | `validate`、`intersects` | 验证范围并计算水平交集 | 拒绝无效、自交、退化及跨日界线范围；计入航线走廊宽度 |
| `integrationconfig/api/LocalInterfaceDtos.java` | `WeatherInput`、`Period` | 接收模拟预报 | 增加可选范围、降雨强度；兼容旧构造入口 |
| `integrationconfig/application/LocalInterfaceSimulatorService.java` | `weather` | 校验、保存模拟预报 | 拒绝无效几何和非有限降雨数值 |
| `integrationconfig/application/WeatherForecastRiskService.java` | `evaluate`、`sourceKey` | 匹配任务与生成风险 | 范围外任务不关联；新范围保留独立原始事实 |
| `integrationconfig/application/LocalForecastReadService.java` | `read` | 读取计划对应预报 | 后到但范围不符的预报不覆盖原本匹配的预报 |
| `flight/application/FlightReadService.java` | `flightRouteVersion` | 读取已授权任务的航线版本 | 沿用任务读取权限与数据范围，不扩大航线管理权限 |
| `risk/api/WeatherRiskDto.java` | DTO、兼容构造方法 | 返回地图事实 | 增加降雨、摘要和模拟标识 |
| `risk/infrastructure/WeatherRiskRepository.java` | `forecastMap`、`number` | 按原消息读取冻结事实 | 校对发布时间、时段和来源，读取原范围与实际风向 |
| `risk/application/RiskReadService.java` | `weatherFact` | 组装已授权风险的气象事实 | 区分有坐标范围和只有区域名称的预报 |
| `dongying-vue/src/services/weatherRiskLayer.js` | `weatherTexturePixels`、`cloudTexture`、`drawWeatherArea`、`polygonPath`、天气类型/色值函数 | 绘制气象范围 | 缓存轻薄云雨材质和细风向线，所有效果裁剪到预报范围内；纹理只作示意，不表示逐点雨量分布，不额外涂粗航线 |
| `dongying-vue/src/pages/flights/components/WeatherMapInfo.vue` | `hasRange`、`hasWind`、`windName`、`progress`、时间格式函数；模板和样式 | 显示气象控制与信息 | 独立云雨/风场开关、8%—30%滑杆、紧凑预报数值卡和有效期时间条；保留来源与边界缺失提示 |
| `dongying-vue/src/pages/FlightsPage.vue` | `simulatedWeather`、`resetRiskWeather`、图层watch、`renderRiskMap`、`renderRouteMap`；模板和样式 | 连接风险详情与地图 | 默认18%，独立图层调整立即重绘；完整预报范围与航线共同定位，收紧气象地图留白，相同事实刷新不重置视角 |
| `dongying-vue/src/components/WeatherRiskMarkers.vue` | `draw`、`legendKinds`、图层watch；模板 | 共用气象标记与控制 | 同步淡色强度、模拟标识和完整天气图例 |
| `dongying-vue/src/ui/weatherRiskIcon.js` | `weatherRiskIcon` | 风险类型图标 | 多项天气使用气象图标 |
| `tools/device-simulator/web/weather-form.js` | `control`、`validate`、`validatePolygon`、`coverage` | 预报表单与验证 | 可选雨量；明确范围与区域名称区别 |
| `tools/device-simulator/web/external.js` | `weatherPreview`、`buildWeatherInput`、表单及消息事件 | 编辑和提交预报 | 绘制/清除范围、接收地图坐标；无效草稿不进入预览 |
| `tools/device-simulator/web/app.js` | `renderMap`、`setDraw`、`finishDraw` | 地图绘制 | 独立天气绘制模式，不创建空域、不修改航线 |
| `tools/device-simulator/web/workspace.js` | `syncFrame`、消息事件 | 连接地图和资料表单 | 同源转发范围；离开天气页清除预览；更新内嵌表单资源版本 |
| `tools/device-simulator/web/index.html`、`external.html` | 无具体方法；脚本资源入口 | 加载地图桥接和气象表单 | 更新资源版本，避免浏览器继续使用缺少气象绘制事件的旧缓存 |
| `tools/device-simulator/map-entry.js` | `projectCoordinates` | 坐标投影 | 将实际WGS84范围定位到共用地图 |
| `tools/device-simulator/plan_weather.py` | `plan_forecast` | 生成批量模拟预报 | 保留明确配置的范围和雨量，不从航线推算 |
| `docs/飞行计划天气接口配置.md`、模拟器`README.md` | 无具体方法 | 契约与使用说明 | 说明字段、范围语义、入口和历史保留 |
| 后端`LocalInterfaceSimulatorApiTest`、`WeatherForecastMapPostgresTest`、`LocalForecastReadServiceTest`、`WeatherFootprintTest` | 范围/来源/时段/雨量测试用例 | 验证输入与匹配边界 | 覆盖不同位置和来源、非法范围、范围外任务和冻结事实 |
| 模拟器`test_airspace_drawing.cjs`、`test_external_session.cjs`、`test_weather_form.cjs`、`test_plan_weather.py` | 气象输入与消息测试用例 | 验证绘图、校验与提交 | 验证天气范围独立于空域并贯穿报文 |
| `dongying-vue/tools/flightMapRedraw.test.cjs` | `main`中的地图验收用例 | 运行页面实际地图绘制函数 | 验证航线/范围两种返回顺序都包含完整几何，重复读取不重置视角 |
| `dongying-vue/tools/weatherRiskMarkers.test.mjs` | 云雨材质、范围裁剪及图层组合用例 | 验证共用气象绘制行为 | 覆盖可重复的半透明纹理、两组位置和四种开关组合；关闭后不残留气象绘制 |

## 验证证据

- 后端 `LocalInterfaceSimulatorApiTest` 新增不同位置/来源的范围、雨量、非法输入、范围外任务和冻结快照用例；`WeatherForecastMapPostgresTest` 在独立 PostgreSQL 数据库执行42项，全部通过。`LocalForecastReadServiceTest`14项与`WeatherFootprintTest`2项通过。
- 模拟器 `test_airspace_drawing.cjs`、`test_weather_form.cjs`、`test_external_session.cjs`，以及业务前台既有 `weatherRiskMarkers.test.mjs` 合计24项通过。`test_plan_weather.py`5项与既有外部输入Python测试8项通过。
- Java17后端打包、业务前台生产构建通过。前后台和模拟器自动启动检查通过；演示种子保持关闭。
- 2026-10-18已在Edge登录后的业务前台5173和模拟器8766完成下述实际操作。补修浏览器旧脚本缓存与异步范围定位后，`flightMapRedraw.test.cjs`、业务前台生产构建、`tools/scan.cjs`及两仓库`git diff --check`通过。构建保留原有大分包提示，无构建错误。
- 本次没有提交GitHub，没有修改已应用迁移或回填历史气象范围。

## 真实浏览器验收（北京时间2026-10-18 14:15—14:27）

- 模拟器“资料输入 → 气象预报输入”实际绘制6个顶点并确认，收到闭合WGS84范围；清除按钮可以清除范围。全过程空域边界数量保持0，没有将气象范围登记为空域。
- 通过模拟器正常接口创建当前时段的模拟任务，计划ID为`29860604-ba2f-4a8c-b4da-f4466c390af7`；提交带范围的“小雨伴雷暴”预报，平台消息ID为`971f694f-7aae-44d4-bd69-1b1c9079b85d`，接收时间14:15:28。初次任务报文中的可选电话空字符串被校验拒绝，省略该字段后成功，未伪造电话号码。
- 预报时段14:10:53—15:10:53，降雨2.1 mm/h，风速12 m/s，315°来风；生成模拟气象风险`1018-001`（`a03038a8-89b3-4344-a08a-94e17163ded2`），按任务时段及范围关联航线。地图显示的六边范围来自这次绘制，与行政区边界明确区分。
- 实际点击12%、18%、26%和隐藏/显示，图层即时变化。截图像素对照确认范围内颜色随档位变化、范围外底图像素不变；关闭气象后航线保留。详情展示原雨量、风速、来风方向、来源、发布时间和有效时段。
- 切换旧风险`1017-001`后显示“边界未提供”，显示气象按钮禁用，地图没有残留新范围。返回新风险并刷新仍恢复同一条记录、预报事实和范围；地图缩放、平移与重新定位可用。
- 地图首次定位同时包含完整预报范围和关联航线。连续切换后DOM仅一个`rkMap`宿主，含一张底图canvas、一张动画canvas和一张overlay canvas；页面控制台未捕获警告或错误。
- 截图视口1846×996，文件在本机`artifacts/weather-map-20261018/`：`forecast-default-18.jpg`（刷新后的默认结果）、`forecast-12.jpg`、`forecast-26.jpg`、`forecast-details.jpg`、`history-without-boundary.jpg`。图片为实际浏览器截图，无后期叠图。
- 验收新增上述1条模拟任务和1条模拟预报，未启动设备飞行场景，未提交人工核验或通知。真实上级气象接口和真实气象设备尚未接入，本次验证的是用户指定的设备模拟器链路。

## 参考图风格修订（北京时间2026-10-18 14:36—14:49）

本段替代上方首轮验收中的均匀填充与12%/18%/26%按钮描述。此次只修改业务前台5个文件及本文档，沿用已提交的模拟预报和航线，没有新增业务数据，后端、模拟器输入契约及历史记录未调整。

- 按用户最新参考图核对云雨纹理、细风向线、顶部独立开关与强度滑杆、紧凑天气数值卡、底部图例和时段。默认18%，云雨颜色为淡青灰，地图道路与关联航线仍清晰。
- 云雨材质是固定、缓存的示意纹理，显示“纹理示意 · 非逐点雨量”；它不产生气象事实。范围只来自模拟器明确提交的坐标，不扩散到未提交区域。315°来风转换为向东南的箭头，未合成局部风向差异。
- 与参考图的有意差异：本次是小范围模拟预报，按真实提交范围自动定位，不能为视觉效果改成全市云带；接口未提供高度层、气象站或未来逐帧数据，不显示参考图中的100m AGL、站点和预报播放按钮。底部时间条只表示这条预报的有效时段。
- Edge实际点击独立云雨和风场开关，关闭两者后底图与航线完整保留；滑杆实际调整到12%、18%、20%、22%并回读。刷新后仍展示原预报事实和18%默认强度。
- 切换历史风险1017-001，云雨与风场入口均禁用，显示“边界未提供”，地图无新范围残留；切回1018-001恢复同一原始预报区域。14:48:46最终截图显示降雨2.1mm/h、西北风12m/s；控制台警告/错误读取为空。
- `node --test tools/weatherRiskMarkers.test.mjs`：12项通过；`node tools/flightMapRedraw.test.cjs`、`node tools/scan.cjs`、`npm.cmd run build`和`git diff --check`通过。构建仅保留既有大分包提示。
- 仅重启已确认归属的业务前台进程，并执行强制启动检查。`artifacts/local-runtime/startup-check.json`时间为2026-10-18T06:42:19.959Z，7项全部PASS，脚本退出0；脚本不验证登录，实际登录后的页面交互证据单独记录在上文。
- 实际截图1846×996保存在`artifacts/weather-map-20261018/style-revision/`：`weather-page.jpg`（最终18%）、`weather-12.jpg`、`weather-hidden.jpg`、`history-without-boundary.jpg`。`reference.png`为用户参考图原文件副本，`comparison.html`提供等宽局部对照；产品页面没有使用参考截图作底图或覆盖层。

## 首轮运行恢复记录

Docker因遗留socket无法启动，保留原临时目录并重建同名临时目录；未删除数据卷或业务数据。原目录备份为 `%LOCALAPPDATA%\Docker\run-stale-20261018-013154`、`%LOCALAPPDATA%\Docker\run-stale-20261018-013442`、`%LOCALAPPDATA%\docker-secrets-engine-stale-20261018-013442`（最后一项原目录仅含socket文件）。

后端构建触发热重启导致迁移资源加载失败，通过停止已确认属于本项目的进程并重新启动恢复；未执行Flyway repair或更改历史。前端热更新资源时间参数触发启动检查拒绝，通过重启本项目前端恢复。独立验证数据库 `weather_risk_verify_20261018_0136` 保留，测试schema已由测试清理。

## 后续补充上传检查（2026-10-18）

用户要求将两仓库其他未提交改动一并上传 GitHub，包含本文档、天气设计参考、自动反制等待规则、任务跳转及告警交互。上文“本次没有提交GitHub”记录的是此前天气验收轮次。

- 本轮后端11个相关测试类共272项通过，无失败、错误或跳过；自动反制与天气范围分别使用独立 `stage456_verify_*`、`weather_risk_verify_*` 数据库中的临时schema验证。
- 管理端规则相关33项测试及生产构建通过；业务前台飞行任务和天气地图15项、模拟器Node测试32项、Python测试13项通过。此前合并后告警、处置与筛选53项及业务前台构建通过，相关业务实现本轮未再修改。
- 最新启动检查：后端就绪、业务地图、管理地图代理、地图配置一致性及模拟器通过；前后台页面两项因当前跨源API配置不满足检查器要求的同源代理而失败。本轮不将启动检查记为全部通过，也未重新进行完整浏览器验收。
- 源码、测试、说明和设计参考纳入提交；本地环境文件、凭据、日志及构建产物不纳入。仅整理新增文档与测试文件末尾空行，不修改已应用迁移或运行数据。

## 启动检查误判修正（北京时间2026-10-19）

复查更正上一节对两项失败的根因判断：实际业务 API 为同源 `/api/v1`，管理端为同源 `/dev-api/v1`，均正确转发到现有后端。旧检查器共用的 URL 校验拒绝了 JS 资源中的 Vite 热更新时间戳 `?t=...`，却报为“同源地址”错误；无需修改代理环境或重启服务。

- `scripts/check-local-startup.mjs` 的 `sameOriginUrl`、`checkFrontend` 仅允许静态资源携带查询参数，按完整 URL 读取；API、地图地址、同源要求、鉴权响应以及预览产物内容检查保持。
- 检查脚本21项测试通过，包含不同缓存参数、开发/预览、旧产物、真实错误代理、跨源资源和错误API元信息。管理端代理、审计头和登录提交11项测试通过。本轮只改检查脚本、测试及文档，前后台应用源码和构建配置未变。
- 完整自动检查7项通过、退出码0，报告时间 `2026-10-19T01:00:32.019Z`；本机报告为 `artifacts/local-runtime/api-proxy-fix-after.json`。真实账号验证独立记录，自动报告的 `account_login` 保持 `NOT_TESTED`。
- 用户重新登录后，Edge实际操作业务前台“查询”和管理端“刷新”：研判查询返回正常空态，用户管理显示5个单位、11个账号，无服务不可用提示。模拟器实际回读发现旧会话失效，用户重新登录后再次回读成功，显示4台设备、0个目标、0个轨迹点，无读取错误。
- 模拟器接收端仍保留上次会话失效后的停止状态；本轮未启动接收端、重跑场景或重新提交业务数据。运行环境的 `app.dev-seed.enabled=false` 保持。
