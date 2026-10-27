# 态势风险只读列表

`GET /api/v1/situation/risk-items` 使用现有 Bearer 会话与 `{ok,data,error}`、snake_case 契约。只读查询不调度光电、不核实、不推进通知或设备状态。

参数：`kind=ALL|UAV|SPACE_OBJECT|WEATHER`（默认 ALL），`view=ACTIVE|PROCESSING`（默认 ACTIVE），`page>=1`，`size=1..100`（默认 20）。未知、重复、空白参数拒绝。

data 返回 `items,page,size,total,counts,availability,as_of`。`counts={all,uav,space_object,weather}` 始终统计 ACTIVE、在分类及分页前去重；无权分类为 null，其 availability 为 UNAVAILABLE；all 仅在三个分类均可读时有值。读取失败正常报错，不当成零。

每项字段：`key,kind,risk_case_id,risk_case_version,event_id,event_version,alarm_id,risk_id,target_id,target_no,plan_id,plan_no,route_version_id,area_id,area_name,severity,reason_code,reason_text,occurred_at,updated_at,stage,state,source_mode,simulated,position,current_status,presence_reason`。时间为 epoch 毫秒。未知引用和位置返回 null，不从当前目标拼造历史事实。position 预留 `{longitude,latitude,observed_at,basis}`，本次无已验证位置投影时为 null。

key：统一无人机为 `risk:<risk_case_id>`，无 case 的兼容事件为 `event:<event_id>`，连事件也没有时为 `alarm:<alarm_id>`；鸟/异物为 `space:<risk_id>`，气象为 `weather:<risk_id>`。同 case 的预测及实测只占一项，同目标不同区域保留；鸟/气象每个 risk_id（含不同任务）分别保留。排序为等级 CRITICAL/HIGH/MEDIUM/LOW 降序、发生时间降序、key 升序。

UAV ACTIVE 必须为有效 PREDICTED/ENTERED，或具有当前观测且仍属当前关注的 OBSERVED；不将 UNKNOWN/已结束纳入。PROCESSING 保留当前及非当前待办、尚未结束的设备工作、真实通知待投递/待回执，供详情/停止入口使用；不因进入此查询而增加 active counts。鸟/异物及气象 ACTIVE 必须同时为后端 CURRENT 且 eligible；UNKNOWN、停报待确认、EXCLUDED/CLEARED/EXPIRED/NOT_STARTED 均退出实时列表与计数。UNKNOWN 不代表离开或解除，未完办理继续保留 PROCESSING，焦点详情仍显示“当前影响待确认”。风险处置状态和当前影响是独立字段；本收紧只适用新态势接口，不改变 RiskPresenceService 其他消费者。

查询先用同权限 SQL 选取可能当前与未完成工作的并集，再执行当前性判断，不能截取前 N 条。已排除、已保存解除及气象有效时段已结束且无未完成工作的历史不进入轮询候选。鸟/异物依据仍须按权威当前性判断，不能仅按风险发生年龄猜测是否当前；UNKNOWN 不进入 active，仅保留未完办理及详情提示。分页后才检查关联目标/航线引用的独立读权限及范围，最多处理本页 100 项，不按全历史展开详情。

真实 handoff 使用同风险、同单位区域、同来源的最新 attempt；实际 ACKNOWLEDGED，或无需回执且已 DELIVERED 才结束相应等待。SUBMITTED/FAILED/超时、无投递记录或 NOTIFIED 缺少通知证据仍保持未完。人工/系统急停后即使 case 已 CLEARED/ENDED、授权已 STOPPED，未收到真实 SUCCEEDED 全关回执的停止指令仍留 PROCESSING；本只读投影不改变启动守卫，也不恢复旧现场登记要求。

告警读取继承 ALARM_READ 同元组范围，飞行风险读取继承 RISK_READ+FLIGHT_READ。来源模拟标识沿既有可追溯协议关联，不按名称、编号或 replay 一律推断。

`GET /api/v1/situation/risk-items/{key}`（URL 编码 key）返回同一 normalized Item，data 直接为该对象。焦点可独立每 5 秒读取，不依赖父列表分页或是否仍在当前队列。UAV 在同一 scoped projection 先按唯一 key 精确限定，飞行风险用 scoped risk ID 精确查询，不扫描完整列表。已结束或单位不再当前有效但历史仍可读时，`current_status=NOT_CURRENT`；stage 保留 CLEARED/ENDED/EXPIRED 等原阶段。eligible UNKNOWN 返回 UNKNOWN，当前有效返回 CURRENT。每次整项替换，无权关联字段显式为 null。无类别读权限为 403，不存在/越范围/错误分类的事项为 404；无 action 或状态写入。

# 事件固定研判引用

`GET /api/v1/alarms/{alarm_id}/evaluation-refs` 返回 `{status:AVAILABLE|PARTIAL|UNKNOWN,items:[{evaluation_id,observed_at,evaluated_at,relation:FIRST|ESCALATION,occurrence}]}`。

只从告警首次冻结的 `detail.evaluation_id` 与真实 `alarm_escalation.evaluation_id` 读取，不读取目标最新研判或合并窗口内普通后续研判。每条引用经既有研判服务鉴权，再核对告警 ID、目标、单位、区域、来源和 ACTIVE 模式。需 ALARM_READ、ASSESSMENT_READ、TARGET_READ；不额外要求空域权限。无空间命中的偏航、高度、夜间、起降或无授权规则同样可定位真实证据。缺失、引用不可读或不匹配明确 UNKNOWN/PARTIAL；系统读取错误继续报错。

风险详情继续使用既有 `/uav-risk-cases/{id}`、告警 airspace-hits、具体研判、风险 space-fact/weather-fact 接口。本次无迁移、种子、规则、权限目录或动作授权变更。

# 事件执行事实依据

`GET /api/v1/legality-evaluations/{evaluation_id}/execution-evidence` 返回 `{status:AVAILABLE|PARTIAL|UNKNOWN,items:[{evaluation_id,rule_code,dimension,comparison,planned,actual,planned_availability,actual_availability,reason}]}`。维度为 `takeoff_point/landing_point/pilot/reporting_unit`，availability 为 AVAILABLE 或 UNKNOWN。

planned/actual 包含 `longitude,latitude,coordinate_system,occurred_at,accuracy_m,name,name_basis,plan_version,fact_version,valid_from,valid_to,source_mode,source_name`，不适用或未记录的字段为 null。按 hit.evidence 保存的唯一 flight_execution_fact ID 精确读取原始 payload，并校验目标、轨迹、单位、区域、来源及事实版本；不调用 latest/comparison 重新选取。计划只允许原 hit 保存的 plan_id+plan_version 与可读当前版本完全相同；已改版且历史 filing 未留存时明确 UNKNOWN。

旧执行事实 payload 未持久化坐标系声明，原始坐标仍可供文字比对，`coordinate_system=null` 并说明不得用于地图定位。计划有明确 WGS84 声明才可绘制。实际身份只返回同单位可读目录名称并标 `name_basis=CURRENT_DIRECTORY`，计划已存 pilot_name 标 PLAN_FILING；不输出联系人 ID、电话、邮箱、报送绑定 ID、原始消息或执行标识。当前目录名称不冒充当时冻结的名称。访问继承研判 detail 的 ASSESSMENT_READ 与范围控制，另需 TARGET_READ 和 FLIGHT_READ。
