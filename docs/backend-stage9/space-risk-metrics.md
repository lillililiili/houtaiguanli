# 阶段 9 空间安全风险汇总口径（`GET /api/v1/space-risks/summary`）

> 状态：助手起草（2026-09-07），与 `airspace-spacerisk-api-contract.md` v1.0 配套。汇总只是对已保存事实的计数，不重新判定风险、不改变任何风险状态。分母为 0 时不返回 0，而是明确的不可用标记。

## 事实来源与范围

- 计数对象是阶段 4 的 `flight_risk` 中 `risk_type='SPACE_OBJECT'` 的行，按 `risk_id` 内联 `space_risk_fact`（一对一，只增）。没有 `space_risk_fact` 的风险不属于本汇总。
- 范围谓词与阶段 4 风险读取一致：`ASSIGNED` 必须命中同一有效 `(owner_org_id, district_id)` 授权元组；`ALL` 仍要求归属组织与区域目录存在且启用。归属取 `flight_risk` 自身的两列，不取目标或计划的。
- 时间窗 `from/to` 为 epoch 毫秒、半开区间 `[from, to)`，`from < to`，按 `flight_risk.received_at` 过滤；`from/to` 必填。
- 权限：`risk:read`（先鉴权再解析参数）。缺权限 403，先于任何参数错误。

## 分组与字段

| 字段 | 含义 | 分组键来源 |
| --- | --- | --- |
| `by_subtype[]` | 按异物细类计数 | `space_risk_fact.subtype_code` + `space_object_subtype.display_name` |
| `by_severity[]` | 按风险等级计数 | `flight_risk.severity`（LOW/MEDIUM/HIGH/CRITICAL） |
| `by_state[]` | 按核验状态计数 | `flight_risk.state`（待核验/待通知/已通知/已排除） |
| `by_altitude_band[]` | 按高度带计数 | `space_risk_fact.altitude_band`（CLIMB/APPROACH/CRUISE/UNKNOWN） |
| `trend_buckets[]` | 时间分桶计数 `{from,to,count}` | 按 `received_at` 均分窗口 |
| `routes_involved` | 去重航线数 | `flight_risk.route_version_id` 去重计数 |
| `rule_version` | 参数出处 | `SPACE-RISK-DEMO` 的 `rule_set_code / version_no / param_status` |

每个分组数组只列**出现过的键**，不为零值补行：页面据此显示"当期无该细类"，而不是显示一个可能被误读为"已确认为零"的 0。

## 不可用与空数据

- `rule_version.param_status` 固定为 `DEMO`（决策 9-7）：页面必须把 C04/C05 的结论标注为演示阈值，不能呈现为已确认口径。
- 窗口内没有任何空间风险时，各分组为空数组、`routes_involved=0`，`rule_version` 仍返回（它描述的是当前参数版本，与有没有数据无关）。
- 若 `SPACE-RISK-DEMO` 规则集尚未落库（迁移 062 未应用），`rule_version` 返回 `{"availability":"UNAVAILABLE"}` 而不是猜一个版本号。
- 高度带 `UNKNOWN` 是真实结论而不是缺失：AGL 与 AMSL 不互比，基准缺失时 C04 只能给 UNKNOWN（契约 C04 决策表）。它必须单独成组显示，不能并入任何一个已知带。
- `corridor_relation=UNKNOWN` 同理：表示无法判定与走廊的关系，不是"在走廊外"。

## 评估运行的可用性（`POST /api/v1/rule-evaluations`）

C04/C05 依赖 PostGIS 的米制几何。在 H2 或未装 PostGIS 的库上，评估返回 `202` 且 `status=UNAVAILABLE` 并写一条 `rule_evaluation_run(status='UNAVAILABLE', message=...)`，**不是 500**，也不写任何风险。`GET /api/v1/rule-evaluations` 的历史里因此可能出现 UNAVAILABLE 行；它表示"这次没有算"，与 `SUCCESS` 且 `risks_created=0`（算了但没有命中）是两件事，页面不得合并显示。

`targets_seen` 与 `risks_created`/`risks_deduplicated` 的差额有明确含义：无活动计划的目标只计入 `targets_seen`（决策 9-5，`flight_risk.plan_id` 非空，没有计划就没有可关联的飞行活动），不产生风险。

## 尚未接入

真实鸟类识别与驱鸟处置、机场通报通道、C05 的真实机场运行数据。所有阈值来自 `rule_param`（`SPACE-RISK-DEMO` v1，全部 `param_status='DEMO'`），代码中不得出现裸阈值。
