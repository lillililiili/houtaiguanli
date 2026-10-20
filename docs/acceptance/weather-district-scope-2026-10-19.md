# 东营区预报范围修正

用户确认：模拟器上报“东营区预报”时，范围应为东营区，不是航线附近的小矩形；后续下发预报也应携带范围。

流程 6 模板随预报提交明确的 `polygon`。区域名称不推算边界，自定义预报仍可在模拟器地图绘制范围或编辑接口报文。旧接口未带范围时保留兼容行为，地图如实显示边界未提供。正式上游气象接口需提供经确认的 WGS84 范围，本次不宣称真实气象服务已接入。

## 范围来源

- 数据：[DataV.GeoAtlas 东营区 370502](https://geo.datav.aliyun.com/areas_v3/bound/370502.json)，2026-10-19读取，一份多边形外环、659个边界点。
- [提供方坐标说明](https://help.aliyun.com/en/datav/datav-7-0/user-guide/map-data-format-1)说明其地图数据采用GCJ-02；本次使用coordtransform 2.1.2并迭代反算到WGS84，保留全部顶点，不做简化、凸包或矩形替代。转换前后回算最大残差小于1e-8度，该数值只验证转换自洽，不代表数据源的测绘精度。
- 模板 `polygon_source` 保存行政代码、原文件SHA-256、来源及转换说明。[提供方使用说明](https://help.aliyun.com/zh/datav/datav-7-0/user-guide/datav-geoatlas-widgets)限定学习交流，本数据用于当前本地模拟演示。

## 代码变更说明

业务前台和模拟器路径相对 `E:\rwurenji\_rong`；后端路径相对本仓库。

| 文件/类 | 方法/函数 | 方法作用和本次变化 |
| --- | --- | --- |
| `server/.../risk/domain/WeatherFootprint.java` | `validate`、`MAX_VERTICES` | 将有效边界上限统一为1000个顶点，保留坐标、闭合、自交、退化及跨日界线校验 |
| `server/.../integrationconfig/api/LocalInterfaceDtos.java` | `WeatherInput` | 请求DTO使用同一上限，支持完整区界进入原预报链路 |
| `tools/device-simulator/web/weather-form.js` | `validatePolygon`、`coverage` | 表单同步1000点上限；范围说明改为以报文边界为准，兼容行政区和自定义范围 |
| `tools/device-simulator/web/external.html`、`web/index.html`、`web/workspace.js` | 无具体业务方法；资源版本和内嵌表单地址 | 更新表单与工作区缓存版本，避免浏览器继续用100点旧限制 |
| `tools/device-simulator/scenarios/acceptance-flows-1-7-20261008/flow-6-weather-risk.json` | 无具体方法；`fullchain.weather`配置 | 保存完整东营区边界和来源，每次载入流程6均随预报发送 |
| `dongying-vue/src/pages/flights/components/WeatherMapInfo.vue` | 无具体方法；预报详情文案 | 说明范围以提供方边界为准，不再一概称其不同于行政边界 |
| `WeatherFootprintTest`、模拟器`test_weather_form.cjs`、`test_plan_weather.py` | 大边界、大小限制、几何与批量传递用例 | 覆盖659/1000点接受、1001点拒绝、自交拒绝、不同位置/日期/来源、完整区界和重试消息稳定性 |
| 模拟器`README.md`、`docs/飞行计划天气接口配置.md` | 无具体方法 | 补充范围来源、契约上限、后续提交方式与历史保留 |

本轮保留其他会话同期产生的设备运维、任务待办修改；它们不属于本次范围修正。

## 验证

- 后端 `WeatherFootprintTest`、`LocalInterfaceSimulatorApiTest`、`LocalForecastReadServiceTest` 共61项通过；后端打包通过。
- 模拟器Python预报/流程入口20项、Node表单/输入会话/地图绘制15项通过；缓存地址更新后输入会话5项再次通过。
- 业务前台 `weatherRiskMarkers.test.mjs` 12项、`flightMapRedraw.test.cjs`及生产构建通过。源码扫描通过，保留原 `riskNoticeState.js` 一条既有待办。两仓库 `git diff --check` 通过。
- 自动启动检查7项通过，报告时间 `2026-10-19T08:46:03.359Z`，退出0。运行后端保持 `app.dev-seed.enabled=false`。
- 实际登录的Edge模拟器提交消息 `sim-plan-weather-98ba0bf7b0d26822db161a00fedaa06fd393200b`，平台受理659点整区预报，按区域、范围和时间关联当前模拟任务。`SIM-000143`关联的新风险为 `9ffaa063-1d54-47f3-9af9-8f3e8281029f`（1019-003），保存的全部坐标与提交报文逐项相同。
- 上一轮误配小矩形的模拟风险 `f6a6c022-7ac1-47f9-8242-e152a76163fe`（1019-002）经页面人工核验排除，记录范围配置错误原因；原预报、旧通知、历史核验均保留。原无范围预报不回填。
- 浏览器实际打开风险地图和关联任务地图；显示整区云雨和风向，地图仅一个宿主、三张canvas；新预报仍待核验，没有提交核验通过或对外通知。

范围验证属于本地模拟链路，不替代气象提供方的正式区域资料确认。
