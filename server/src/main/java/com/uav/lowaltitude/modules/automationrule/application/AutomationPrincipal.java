package com.uav.lowaltitude.modules.automationrule.application;

/** 自动规则的真实发起人。账号停用且无操作权限，只用于外键和审计，不能登录，也不充当审批人。 */
public final class AutomationPrincipal {
    public static final String USER_ID = "automation-rule-runner";
    public static final String ACCOUNT = "automation-rule";
    public static final String ROLE = "ROLE-AUTOMATION";

    private AutomationPrincipal() { }
}
