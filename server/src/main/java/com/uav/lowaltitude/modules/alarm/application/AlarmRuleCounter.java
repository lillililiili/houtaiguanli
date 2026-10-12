package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.stereotype.Component;

/** 旧调度调用的兼容入口。2026-10-11：规则只判断资格，所有反制必须由有权限的操作员发起。 */
@Component
public class AlarmRuleCounter {
    /** 保留旧调用兼容性，不创建授权、不选择设备、不重试或发送旧自动授权。 */
    public void launchIfPassed(String eventId, String runId) { }
}
