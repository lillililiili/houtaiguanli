# 阶段 8 数据大屏快照接口契约

> 状态：本仓库实现稿。大屏只聚合已落地领域数据，不新建业务表，不回退 `mock.js`。融合感知与证据管理不在本切片。

## 交付边界

`GET /api/v1/dashboard/snapshot` 给 `#/bigscreen` 一次读取 KPI、趋势、设备健康、飞行计划计数、告警列表和地图叠加。天气、统一检索、证据文件、真实光电流、处罚案件、联动反制完成事实均不在本接口。

趋势图与运行统计页同源，读取 `GET /stats/operations` 的样本事实表 `days`，带 `simulated`/`source_mode`；不得把它解释成目标/告警领域表的官方运行指标。

统计口径（ZT-17；2026-10-07 用户决定设备模拟器的数据也计入，取代 10-06 的“只计 live”）：大屏上所有计数——`kpis.*`、`closure.pending_verification`/`closure.confirmed_blocked`、`target_risk`、`flights.*`、`devices.*`——与运行统计同一口径，**计 `source_mode` 为 `live`（真实设备）或 `replay`（设备模拟器）的数据**，不计建库时系统自带的演示样例（`mock`）。只有允许模拟的环境（local+qa、test）把 `replay` 算进来，正式环境只计 `live`，库里留有历史模拟记录也不进统计。口径定义在 `platform/query/StatisticsScope`，改口径只改那里。其中来自设备模拟器的条数放在 `simulated_included` 里，页面必须写明，免得被当成现场真实数据。`alarms.items`/`alarms.total` 和 `map.*` 不是计数，仍按**全部来源**给。导出的业务报表仍只计 live。

## 通用约定

沿用既有信封 `{ok,data}` / `{ok:false,error:{code,message}}`、snake_case、字符串 ID、epoch 毫秒、`AppClock`。无 query；未知或重复参数 400 `VALIDATION_ERROR`。鉴权先于参数解析。

「今日」为 `Asia/Shanghai` 日历日半开区间 `[today, tomorrow)`。`as_of` 为本次读取的 `AppClock` 毫秒。

## 权限

| 门槛 | 权限 |
| --- | --- |
| 打开快照 | 菜单型 `dashboard.read`（`app_permission.permission_code=dashboard`）且数据范围不是 `NONE` |
| 目标 KPI/地图点 | `target:read` |
| 告警 KPI/列表/地图点 | `alarm:read` |
| 待研判 / 风险分档 | `assessment:read` |
| 交接待办 | `handoff:read` |
| 设备健康/地图设备 | `device:read` **且** `monitoring.read` |
| 飞行计划计数 | `flight:read` |
| 地图空域 | `airspace:read` |
| 近 7 日趋势 | `statistics.read` |

缺某一源权限时：该块 `availability` 为 `FORBIDDEN`，计数字段显式 `null`，列表为空；整页仍 200。不得用 0 冒充「没有数据」。设备归属未配置时设备块可为 `UNCONFIGURED`（与工作台设备异常同一语义）。越权对象不出现在列表或地图中。

## 响应

`data` 固定字段：`as_of`、`availability`、`kpis`、`simulated_included`、`statistics_source_modes`、`trend`、`target_risk`、`closure`、`devices`、`flights`、`alarms`、`map`。

`availability` 键：`targets,alarms,assessments,handoffs,devices,flights,airspaces,stats`，值 `AVAILABLE|FORBIDDEN|UNCONFIGURED`。

| 块 | 有权限时 | 无权限时 |
| --- | --- | --- |
| `kpis.sensed_today` | 今日 `last_seen` 窗口内、统计口径（live + replay）的目标总数 | `null` |
| `kpis.alarms_today` | 今日 `occurred` 窗口内、统计口径的告警总数 | `null` |
| `kpis.pending_assessment` | `latest_only=true` 且 `review_state=PENDING_REVIEW`、统计口径的研判总数 | `null` |
| `kpis.pending_handoffs` | `delivery_status=PENDING_DELIVERY`、统计口径的交接总数 | `null` |
| `trend` | 近 7 日（含今日）`days[{date,md,total,illegal}]`，以及 `simulated`/`source_mode` | 整块 `null` |
| `statistics_source_modes` | 计数计入的来源：允许模拟的环境为 `["live","replay"]`，正式环境为 `["live"]`；页面据此写口径说明 | 同左（与权限无关） |
| `simulated_included` | 统计卡里来自设备模拟器的条数：`sensed_today`、`alarms_today`、`flights_today`（`replay` 计划）、`devices`（统计口径台数减正式接入台数） | 对应字段 `null` |
| `target_risk` | 最新研判 `grade`：`high/medium/low/ungraded`，按统计口径逐个来源各取 latest_only 研判（每个来源抽样上限 100 条，任一来源到上限时 `truncated=true`） | 整块 `null` |
| `closure.pending_verification` | 告警状态 `PENDING_VERIFICATION` 计数（统计口径，不限今日） | `null` |
| `closure.confirmed_blocked` | 告警状态 `CONFIRMED` 计数（统计口径，反制未接入，只计数） | `null` |
| `closure.pending_handoffs` | 同 KPI | `null` |
| `closure.evidence` | 恒为 `{status:"NOT_BUILT"}`，本切片不建设证据库 | 同左 |
| `devices` | `GET /device-monitor/overview?statistics_scope=true` 的 total/online/offline/abnormal/alarm/online_rate；统计口径内的设备：正式接入设备（`live` 且非模拟设备），允许模拟的环境再加设备模拟器的设备（`replay`）；`live` 但标了 `simulated` 的本机模拟设备与设备列表一样当作演示样例，不计。`source_mode` 为 `live`（没有模拟设备或一台都没有）、`replay` 或 `mixed`，`simulated` 只在全部是模拟设备时为真 | 整块 `null` |
| `flights.today` / `flights.executing` / `flights.completed` | 今日窗口内统计口径的计划总数、其中 `status_code=EXECUTING` 数、`status_code=COMPLETED` 数 | 整块 `null` |
| `alarms.items` / `alarms.total` | 今日告警最多 8 条（全部来源，按 `received_at DESC`）与同口径总数 | `[]` / `null` |
| `map.targets` | 有 WGS-84 位置的目标（最多 100，按 `last_seen` 倒序，不按今日窗口过滤） | `[]` |
| `map.devices` | 有经纬度的启用设备（最多 46）；非 WGS-84 不画 | `[]` |
| `map.airspaces` | 当前有效版本且含边界的空域（最多 40） | `[]` |
| `map.alarms` | 今日告警中能关联到已返回目标位置的点（最多 8） | `[]` |

地图目标缺位置则省略，不写 0 坐标。告警地图点禁止按行政区名落到区中心。高度字段为 AMSL，单位米。

告警列表项：`alarm_id,alarm_no,alarm_type,severity,state,received_at,occurred_at?`。`target_id` 仅在同时具备 `target:read` 且目标仍在同一范围时出现。

## 不做

天气、检索、证据文件哈希/保管、`/eo/stream`、处罚案件、未匹配航线、计划偏离距离、分路雷达/光电/TDOA 置信度。融合感知页（`#/situation`）与证据管理页（`#/evidence`）由其他开发者承接；本接口只给大屏聚合已有领域表，`closure.evidence` 恒为 `NOT_BUILT`。
