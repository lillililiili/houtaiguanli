package com.uav.lowaltitude.modules.identity.domain;

public enum PermissionCode {
    DEVICE_READ("device:read"),
    TARGET_READ("target:read"),
    ALARM_READ("alarm:read"),
    // 读和核实分权：拥有核实动作不应绕过来源告警的可见范围。
    ALARM_VERIFY("alarm:verify"),
    // 阶段 3 将计划、航线、空域和研判拆成独立动作，双权限接口不得用任一单项权限替代。
    FLIGHT_READ("flight:read"),
    FLIGHT_VERIFY("flight:verify"),
    ROUTE_READ("route:read"),
    AIRSPACE_READ("airspace:read"),
    ASSESSMENT_READ("assessment:read"),
    // 飞行风险拥有独立状态机，读取和核验不能借用计划或研判权限。
    RISK_READ("risk:read"),
    RISK_VERIFY("risk:verify"),
    // 工作台只聚合三类源事项，仍需逐项校验源模块读权限；workbench:read 不能扩大任何源的可见范围。
    WORKBENCH_READ("workbench:read"),
    // 交接是独立于源状态的记录：读交接、发起交接分权，发起还必须同时具备源对象读权限。
    HANDOFF_READ("handoff:read"),
    HANDOFF_CREATE("handoff:create"),
    // 阶段 7：规则集读取/激活分权；引擎评估、人工复核、转告警是三种不同动作，缺任一不能借研判读权限替代。
    RULE_READ("rule:read"),
    RULE_MANAGE("rule:manage"),
    ASSESSMENT_EVALUATE("assessment:evaluate"),
    ASSESSMENT_REVISE("assessment:revise"),
    ASSESSMENT_ESCALATE("assessment:escalate"),
    // 阶段 8：融合配置/血缘读取、人工修订与合并分裂、配置激活分权；读目标仍用 target:read，融合动作不附带目标可见范围。
    FUSION_READ("fusion:read"),
    FUSION_REVISE("fusion:revise"),
    FUSION_MANAGE("fusion:manage"),
    // 证据文件底座：读、入库、下载、关联、冻结、销毁分权；菜单 evidence 不能替代其中任一动作。
    EVIDENCE_READ("evidence:read"),
    EVIDENCE_INGEST("evidence:ingest"),
    EVIDENCE_DOWNLOAD("evidence:download"),
    EVIDENCE_LINK("evidence:link"),
    EVIDENCE_HOLD("evidence:hold"),
    EVIDENCE_DESTROY("evidence:destroy"),
    // 阶段 9：空域写/导入、机场基础数据、空间风险评估触发。飞行计划外部授权登记（flight:authorize）已按 F8 裁定撤除。
    AIRSPACE_MANAGE("airspace:manage"),
    AIRPORT_READ("airport:read"),
    AIRPORT_MANAGE("airport:manage"),
    RISK_EVALUATE("risk:evaluate"),
    // 阶段 13：处置授权域（反制/干扰/驱离/诱骗的申请、审批、执行、停止）。执行还需协作者 A 的 devices.op（设备控制面）。
    DISPOSAL_READ("disposal:read"),
    DISPOSAL_REQUEST("disposal:request"),
    DISPOSAL_APPROVE("disposal:approve"),
    DISPOSAL_EXECUTE("disposal:execute"),
    DISPOSAL_STOP("disposal:stop"),
    // 阶段 14：处罚案件域（立案/指派/线索、裁量与决定书、复核、结案）。目录行由迁移 V202609080101 先落。
    PUNISHMENT_READ("punishment:read"),
    PUNISHMENT_FILE("punishment:file"),
    PUNISHMENT_DECIDE("punishment:decide"),
    PUNISHMENT_REVIEW("punishment:review"),
    PUNISHMENT_CLOSE("punishment:close"),
    // 离线底图包：查看、上传、全局启用和删除互相独立；启用会影响全部业务前台。
    MAP_READ("map:read"),
    MAP_UPLOAD("map:upload"),
    MAP_ACTIVATE("map:activate"),
    MAP_DELETE("map:delete");

    private final String value;

    PermissionCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
