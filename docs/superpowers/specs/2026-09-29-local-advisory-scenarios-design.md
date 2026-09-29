# 本地通知异常场景设计

用户已授权补齐缺少条件并持续测试。现有本地适配固定返回成功，无法实际验收发送未知、未接通、未播完及处理中重启。

选择：在既有模拟适配层增加可选启动配置，绑定单一事件及稳定 provider key，仅在 local+qa 或 test 且非 prod/production、mock/replay 来源生效。默认关闭，不开放HTTP写结果接口，不修改业务数据库或通知状态机。相比只做隔离单元测试，本方案能覆盖真实页面与进程恢复；相比添加供应商回调协议，本方案不猜造尚缺的对接契约。

配置 app.qa.advisory-scenario.enabled/event-id/sms-status/voice-status/delay-ms。短信支持 SIMULATED_DELIVERED、SENT、FAILED、UNKNOWN；电话支持 SIMULATED_PLAYED、NO_ANSWER、ANSWERED、FAILED、UNKNOWN。只作用于匹配通道的 auto-advisory:<event> 或 auto-advisory-voice:<event>。延迟0至60000毫秒，模拟外部调用等待，业务事务已经提交且释放。缺少配置或无效值必须拒绝该测试通道调用，不回退成功。

SENT只返回已提交，业务层保持UNKNOWN无观察；ANSWERED只带接通时间不带播完时间；不改变当前明确结果校验。服务被中断后使用原持久化任务与60秒租约转UNKNOWN，禁止自动重发。未配置的通道保持既有行为。真实通知、正式结论、跨事件回调和实际硬件不在本能力内。

验收：配置关闭/环境/来源/事件隔离、枚举/延迟边界、既有短信电话回归；真实模拟样本、页面未知提示、60秒租约恢复且尝试数不增长。无新依赖/迁移，Java17，API契约不变。
