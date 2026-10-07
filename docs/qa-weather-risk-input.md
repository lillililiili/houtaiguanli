# 本地 QA 气象风险输入与验收

2026-10-06：此入口仅用于显式启用的隔离测试，`local,qa` 默认关闭（`APP_WEATHER_RISK_QA_ENABLED=false`）。设备模拟器只提交气象观测和天气预报，不直接生成预设风险；业务风险读取的 `exclude_demo_samples=true` 同时排除 `QA_WEATHER_RISK_INPUT` 来源，保留实际模拟/真实输入及预报规则派生风险。历史直接 QA 输入、核验和审计记录保留，可按原详情追溯。

日期：2026-09-29。用户要求补齐缺失测试条件后继续联测。本入口解决气象风险无法通过正常输入形成独立时效样本的问题。

## 设计与范围

选择受控模拟接口，复用 RiskIngestionService、计划数据范围、消息幂等和审计。启动种子会混入无关场景；直接写业务库会绕过来源和权限，均不采用。预报接口 `/weather` 保持原功能，预报不会自动成为风险。本入口不实现供应商协议，不计算气象等级或飞行安全阈值，不关联虚构目标。

仅 local+qa 或 test 且没有 prod/production 时允许；必须显式设置 `app.weather-risk.qa.enabled=true`，默认关闭。正式环境即使设置开关也不注册入口。要求 interfaces.op、risk:read、flight:read 和当前计划可见；仅接收 mock/replay 计划。产生风险固定 mock，来源为“本地QA气象风险输入”，原因带模拟标识。

## 接口

`POST /api/v1/local-interface-simulator/weather-risks`，Bearer 会话，snake_case JSON：

- message_id：1–64位字母、数字、下划线或横线。同操作者、同编号、同内容返回原结果；内容变化409。
- plan_id：已有可见模拟/回放计划ID；范围与航线版本由计划读取。
- reason_code：WEATHER_STRONG_WIND、WEATHER_THUNDERSTORM 或 WEATHER_LOW_VISIBILITY。
- severity：LOW/MEDIUM/HIGH/CRITICAL，仅表示显式模拟输入；reason_text 为1–900字说明。
- published_at/valid_from/valid_to：毫秒时间；发布时间不得在未来，published_at <= valid_from < valid_to，结束不得超过发布后7天。
- polygon：4–101个 WGS84 [longitude,latitude] 点，闭合、非零面积、无自交，无高度推断。
- 可选 wind_speed_mps（0–150）、wind_from_degrees（0–360）、visibility_m（0–1000000）。

响应 data 包含 risk_id/plan_id/source_mode=mock/state=ACCEPTED。接受输入不等于核验、通知、签收或解除。无目标、无轨迹，不产生光电任务。风险与范围快照、输入消息和审计同事务保存；无效输入不留下部分记录。重试重新检查原计划权限。

## 验证计划

- [x] 入口缺失反例确认失败；实现最小适配器。
- [x] H2 验证正常/幂等/冲突、时效、无效范围、live隔离、认证权限。
- [x] 环境开关、原接口回归；独立 PostgreSQL/PostGIS 验证相同HTTP断言。
- [x] 补验重试时计划范围变化；package，重启8081并验证健康接口及页面200。
- [x] 正常接口输入当前、未到期、到期气象风险；核对纯气象无光电入口、当前数量与待确认分组、历史与通知事实保留。

当前风险语义沿用既有 `/risks/current`：有效期内 CURRENT，未开始不列入（NOT_STARTED）。2026-10-06（ZT-47）起有效时段结束的气象风险投影为 EXPIRED 并移出 `/risks/current`——“到期还挂在当前风险里显示状态待确认”是缺陷：依据已经失效，不是位置未知。EXPIRED 不等于已解除：核验状态、通知与回执历史、详情与列表都原样保留，也不写解除依据。非气象缺少持续/解除契约时仍保持 UNKNOWN。
