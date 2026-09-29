# 本地通知异常场景 Implementation Plan

采用 executing-plans 同步执行，用户已授权补齐并持续测试，不另开代理或提交Git。

Goal: 让固定成功模拟器可精确产生单事件异常和中断，用真实页面验证恢复。
Architecture: LocalAdvisoryScenario只解析受限启动配置，两既有模拟适配调用；领域状态机继续判断结果。
Tech Stack: Java17/Spring现有Environment/JUnit5。

- [x] 新增 LocalAdvisoryScenarioTest，先验证配置已设置但仍固定成功的反例。
- [x] 实现 local+qa/test、非生产、mock/replay、单事件、枚举和延迟门禁；两适配接入。
- [x] 执行受影响短信电话测试及package，更新README和开发基线。
- [x] 重启，正常API/MQTT创建样本，验证SENT、NO_ANSWER、ANSWERED及SENDING服务重启；保存证据、撤销测试配置。

2026-09-29 02:04：主清单74、79完整通过。SENDING强制进程中断后原租约内不重发，到期UNKNOWN且仍1次、0送达记录；页面与API一致。恢复启动已撤销单事件异常配置。60项定向回归及package通过，证据见第十二轮报告。
