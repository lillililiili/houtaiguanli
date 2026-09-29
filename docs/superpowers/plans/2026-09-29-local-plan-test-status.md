# 本地计划模拟状态输入 Implementation Plan

> For agentic workers: 使用 executing-plans 在当前线程执行，不委派代理。

**Goal:** 让受控模拟计划正常输入能够覆盖执行中、完成、取消的前端分支。
**Architecture:** PlanInput追加可选状态；LocalFlightPlanInputService校验枚举和时间；Repository参数化保存。保留默认PENDING和所有既有环境权限边界。
**Tech Stack:** Java17 / Spring Boot / JdbcTemplate / PostgreSQL。

## Global Constraints
不写业务库测试夹具，不改已应用迁移，不增加依赖，不启动全库状态推进，不覆盖旧计划，不把模拟状态当实际飞行结果。

## 实施与验证
- [x] 在LocalInterfaceSimulatorApiTest增加状态及非法时间、幂等反例并确认红灯。
- [x] 修改LocalInterfaceDtos.PlanInput、LocalInterfaceSimulatorService.plan、LocalFlightPlanInputService.create、LocalFlightPlanInputRepository.insert，旧调用兼容。
- [x] 定向回归、隔离PostgreSQL验证、package，检查生产环境模拟隔离。39 项通过、零失败零跳过。
- [x] 维护接口说明、重启并验证8081；通过正常API创建执行中、完成、取消新样本，继续轨迹和同名计划测试。轨迹涵盖红绿灰、停留与33.365秒缺口；同名计划跳转筛选缺陷已修复并实测。
