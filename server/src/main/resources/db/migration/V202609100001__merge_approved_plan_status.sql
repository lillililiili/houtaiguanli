-- 计划状态里没有"已批准"这一档：平台没有审批动作，也没有审批单位与审批时间字段，
-- 报备来源送来的计划一律视为待执行；执行中 / 已完成由 FlightPlanStatusAdvanceJob 按计划时段推进。
UPDATE flight_plan SET status_code = 'PENDING' WHERE status_code = 'APPROVED';
