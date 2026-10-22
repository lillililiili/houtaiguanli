# WGS-84 坐标统一修复与验证

用户确认修复此前审查的四处问题。审查记录：[WGS-84 坐标审查](../reviews/wgs84-coordinate-audit-2026-10-20.md)。本次只修坐标契约、缺失值和名称一致性，不改变地理位置、不批量转换或重写历史。水平基准与 AGL/AMSL 高度基准独立。

## 修复结果

1. 设备新建/编辑拒绝 GCJ02、BD09、CGCS2000 等非 WGS-84 声明；WGS84/WGS-84（含首尾空格、大小写差异）规范保存为 WGS-84。未填声明保留原设备 API 的 WGS-84 默认约定。前台其他基准或未知坐标设备保留在列表，但不画为可信位置。
2. GeoJSON 的 FeatureCollection、Feature、geometry 各层检查声明；拒绝空声明、畸形声明、其他基准以及仅后缀相似的伪造名称。无声明沿用 GeoJSON 的默认 WGS-84，经度在前、纬度在后。模拟计划内嵌航线及独立航线复用同一规则。
3. 事项地图、设备位置和航线点位拒绝 null、空字符串、空白、布尔值、非有限数和越界值。真实 0° 坐标保持有效。
4. 飞行任务设备覆盖检查与前后台地图统一识别 WGS84/WGS-84 等价名称，避免同一台设备在地图可见、任务检查却提示缺位置。

## 代码变更说明

后端与管理端路径以 `E:\houtaiguanlii` 为根；业务前台以 `E:\rwurenji\_rong\dongying-vue` 为根。

| 文件 | 方法/函数 | 职责与改动 |
| --- | --- | --- |
| `server/src/main/java/com/uav/lowaltitude/platform/geo/Wgs84.java` | `isWgs84`、`supportsGeoJson`、`normalized` | 新增共用名称和显式声明校验，不进行坐标转换 |
| `server/src/main/java/com/uav/lowaltitude/modules/device/application/DeviceService.java` | `validate`、`values` | 拒绝其他基准；规范保存设备坐标系 |
| `server/src/main/java/com/uav/lowaltitude/modules/airspace/domain/GeoJsonParser.java` | `parse`、`feature`、`geometry`、`ring`；删除原 `wgs84` | 检查各层声明，移除模糊后缀判断，拒绝非有限坐标 |
| `server/src/main/java/com/uav/lowaltitude/modules/flight/application/LocalRouteInputService.java` | `lineString` | 模拟航线接入检查显式坐标系 |
| `server/src/main/java/com/uav/lowaltitude/modules/flight/application/FlightDeviceCheckService.java` | `positionKnown` | 兼容等价 WGS-84 名称，保持范围与来源判断 |
| `ruoyi-ui/src/services/deviceLocationMap.js` | `devicePosition` | 统一名称的空白和大小写识别；原地图投影纬度限制保持 |
| `dongying-vue/src/services/wgs84.js` | `isWgs84`、`decimal`、`wgs84Position` | 新增严格经纬度校验，区分缺失和真实零值 |
| `dongying-vue/src/services/situationData.js` | `toDevices` | 非可信位置不画图，列表仍保留设备 |
| `dongying-vue/src/services/positionMap.js` | `coordOf`、`trustedCenterline`、`loadDevicePosition` | 共用严格坐标校验，不混拼不同层级的坐标字段 |
| `GeoJsonParserTest`、`DeviceOperationsApiTest`、`FlightDevicePreflightTest`、`LocalInterfaceSimulatorApiTest` | 新增坐标契约回归用例 | 覆盖非 WGS 基准、嵌套/畸形声明、规范写入、拒绝后数据不变、不同坐标及来源、真实零值 |
| `ruoyi-ui/tests/deviceLocationMap.test.js`、`dongying-vue/tools/situationData.test.cjs` | 坐标与装配用例 | 覆盖前后台等价名称、空值/类型/范围、保留列表与不画图、设备详情和航线点 |
| `docs/后端开发基线.md`、本文件 | 无具体方法：契约和验收说明 | 记录用户确认的标准、实现范围和验证边界 |

## 自动验证

- 红灯验证：原后端四项回归失败、原前台十四条断言失败；补查航线的有效请求在修复前错误返回 200（预期 400），管理端名称变体测试亦在修复前失败。
- 后端：Java 17，`mvnw.cmd -Dtest=GeoJsonParserTest,FlightDevicePreflightTest,DeviceOperationsApiTest,LocalInterfaceSimulatorApiTest,UpstreamAirspaceApiTest,LocalAirspaceApiTest package`；139 项、0 失败、0 错误、0 跳过，打包成功。使用隔离 test/H2 上下文；其测试夹具未带入运行服务。
- 管理端：`npm.cmd run lint`、`npm.cmd test`、`npm.cmd run build` 通过；58 个测试文件、431 项测试全部通过。
- 业务前台：`node tools/situationData.test.cjs` 315 条断言通过；`flightMapRedraw.test.cjs` 和 `mapOverlayFrame.test.cjs` 通过；`node tools/scan.cjs` 通过（已有 riskNoticeState.js 一条待办仍保留）；`npm.cmd run build` 通过。
- 两仓库 `git diff --check` 通过。测试和构建日志位于后台 `artifacts/local-runtime/wgs84-*`。
- 已用本次打包产物重启本仓库后端：`server/target/wgs84-runtime-20261020-224201/low-altitude-server-0.1.0-SNAPSHOT.jar`；profiles=local,qa，`app.dev-seed.enabled=false`，正常 main 运行包，无 test classpath。沿用本机 25432/houtaiguanli 数据库；Flyway 验证 203 条历史通过，未运行 repair、未修改迁移历史。
- 强制启动检查 `node scripts/check-local-startup.mjs --business-dir E:\rwurenji\_rong\dongying-vue` 退出 0，7 项通过。快照 `artifacts/local-runtime/wgs84-startup-check.json` 的 `checked_at=2026-10-21T02:43:27.066Z`、`passed=true`。该自动检查与下面的真实登录分别记录。

## 真实浏览器与运行库核查

2026-10-21 北京时间约 10:43—10:48，在 Edge 中使用用户实际登录的账号操作：

1. 管理后台设备管理打开“模拟 流程验收 5G-A”（展示编号 5G-A-4FNGK）；清空经度保留纬度，点击保存，页面明确提示“经度和纬度必须同时填写”，未保存非法位置。
2. 恢复原经度 118.614、纬度 37.464，点击保存，看到“设备已更新”；关闭编辑后点击“刷新信息”，详情回读相同数值、WGS-84，地图正常显示。仅同值保存此模拟设备，未修改真实设备位置。
3. 业务融合感知读取 9 台设备，与后台当前列表一致；展开 5G-A，选中同一设备，地图定位和详情正常，2.5 km 已配置范围保留，离线状态如实提示当前不可用。
4. 飞行任务打开 SIM-000151，地图绘制任务航线，起点 118.614000/37.468800、终点 118.617600/37.470000 明确显示 WGS-84。当前无今日执行中任务，因此未声称在浏览器重现当前任务设备覆盖；WGS84/WGS-84 等价名称的覆盖结果由后端参数化回归验证。
5. 空域管理打开既有“东营模拟适飞区”，点击“定位”，地图正确显示该区域边界及详情，保留上级下发只读约束。
6. 运行库只读核查：171 条有位置的设备全部声明 WGS-84；不完整或越界设备位置为 0；124 条空域版本和 86 条航线版本的几何均为 SRID 4326。这证明当前标签和几何 SRID 一致，不代表独立测量验证了设备厂商提供的原始坐标基准。

截图：`artifacts/local-runtime/wgs84-device-browser.png`、`wgs84-business-browser.png`、`wgs84-flight-browser.png`、`wgs84-airspace-browser.png`。其他基准及畸形报文的拒绝在隔离接口回归中验证，未向业务运行库灌入非法坐标或改写历史来制造验收场景。

未提交、推送或部署服务器。工作区另有空域维护、反制方式等并行任务改动，本报告仅声明上述坐标相关代码与验证结果。
