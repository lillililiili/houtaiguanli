package com.uav.lowaltitude.platform.audit;

import java.util.Locale;
import java.util.Map;

final class AuditLabels {

    // Map.of 最多 10 对；模块目录已超过上限，统一用 Map.ofEntries 承载。
    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("authentication", "认证登录"),
            Map.entry("users", "用户管理"),
            Map.entry("roles", "角色管理"),
            Map.entry("audit", "审计日志"),
            Map.entry("devices", "设备管理"),
            Map.entry("alarms", "告警事件"),
            // 阶段 4/5：风险、交接、工作台是独立模块，失败审计与列表展示都按各自模块归档。
            Map.entry("risk", "飞行风险"),
            // 导出走的是复数 risks、告警核实走的是单数 alarm——同一业务域在代码里有两个写法，
            // 少一条字典就会有英文码直接出现在审计日志里。两个都收下，别让读日志的人去猜。
            Map.entry("risks", "风险事件"),
            Map.entry("alarm", "告警事件"),
            Map.entry("handoff", "业务交接"),
            Map.entry("workbench", "工作台"),
            // 阶段 7：研判与规则引擎分开归档；飞行监管只读接口也有自己的模块名。
            Map.entry("assessment", "合法性研判"),
            Map.entry("rules", "规则引擎"),
            Map.entry("flights", "飞行计划"),
            Map.entry("airspace", "空域规则"),
            Map.entry("statistics", "运行统计"),
            Map.entry("fusion", "融合感知"),
            Map.entry("disposal", "处置授权"),
            Map.entry("punishment", "处罚案件"),
            Map.entry("evidence", "证据管理"),
            Map.entry("airport", "机场基础数据"),
            Map.entry("maps", "地图管理"),
            Map.entry("system", "系统"));

    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("integration_source_create", "新增接入来源"),
            Map.entry("integration_source_update", "修改接入来源"),
            Map.entry("integration_source_enable", "启用或停用接入来源"),
            Map.entry("device_create", "新增设备"),
            Map.entry("device_update", "修改设备"),
            Map.entry("device_reboot_requested", "远程重启设备"),
            Map.entry("commission_create", "新建接入调测任务"),
            Map.entry("commission_start", "开始调测"),
            Map.entry("commission_connect", "调测建立连接"),
            // 这一步做的是"保存连接参数快照"，不是核对参数——按服务里的原话来。
            Map.entry("commission_configuration", "保存调测连接参数"),
            Map.entry("commission_cancel", "取消调测任务"),
            Map.entry("lingyun_control_requested", "下发设备控制指令"),
            Map.entry("device_incident_reboot_requested", "请求设备重启"),
            Map.entry("device_incident_recovery_checked", "恢复校验"),
            Map.entry("login_success", "登录成功"),
            Map.entry("login_fail", "登录失败"),
            Map.entry("logout", "退出登录"),
            Map.entry("password_changed", "修改密码"),
            Map.entry("user_created", "创建用户"),
            Map.entry("user_deleted", "删除用户"),
            Map.entry("user_profile_updated", "更新用户资料"),
            Map.entry("user_status_changed", "变更用户状态"),
            Map.entry("user_password_reset", "重置用户密码"),
            Map.entry("user_access_updated", "调整用户角色"),
            Map.entry("user_creation_requested", "申请创建用户"),
            Map.entry("user_access_requested", "申请调整用户权限"),
            Map.entry("organization_created", "创建组织"),
            Map.entry("organization_updated", "更新组织"),
            Map.entry("organization_status_changed", "变更组织状态"),
            Map.entry("organization_deleted", "删除组织"),
            Map.entry("district_created", "创建区域"),
            Map.entry("district_updated", "更新区域"),
            Map.entry("district_status_changed", "变更区域状态"),
            Map.entry("role_created", "创建角色"),
            Map.entry("role_description_updated", "更新角色说明"),
            Map.entry("role_permissions_updated", "更新角色权限"),
            Map.entry("role_deleted", "删除角色"),
            Map.entry("role_access_requested", "申请调整角色权限"),
            Map.entry("role_deletion_requested", "申请删除角色"),
            Map.entry("access_change_approved", "批准权限变更"),
            Map.entry("access_change_rejected", "驳回权限变更"),
            Map.entry("audit_export_requested", "导出审计日志"),
            Map.entry("stats_export_requested", "导出运行报表"),
            Map.entry("super_admin_recovered", "恢复超级管理员"),
            Map.entry("uav_event_verified", "核实无人机事件"),
            Map.entry("risk_verified", "核验飞行风险"),
            Map.entry("risk_notified", "提交风险通知"),
            Map.entry("risk_acknowledged", "确认风险回执"),
            Map.entry("handoff_created", "提交业务交接"),
            Map.entry("legality_evaluation_revised", "复核合法性研判"),
            Map.entry("legality_evaluation_recomputed", "重新研判"),
            Map.entry("legality_evaluation_escalated", "研判转告警"),
            Map.entry("legality_evaluation_triggered", "手动触发研判"),
            Map.entry("rule_set_activated", "激活规则集版本"),
            Map.entry("rule_set_rolled_back", "回滚规则集版本"),
            Map.entry("rule_set_shadow_changed", "调整规则集阴影版本"),
            Map.entry("target_class_revised", "修订目标类别"),
            Map.entry("targets_merged", "合并目标"),
            Map.entry("target_split", "分裂目标"),
            Map.entry("fusion_config_activated", "激活融合参数版本"),
            Map.entry("evidence_ingested", "入库证据文件"),
            Map.entry("evidence_linked", "关联证据对象"),
            Map.entry("evidence_hold_placed", "冻结证据"),
            Map.entry("evidence_hold_released", "解除证据冻结"),
            Map.entry("evidence_verified", "校验证据文件"),
            Map.entry("evidence_downloaded", "下载证据文件"),
            Map.entry("evidence_exported", "导出证据台账"),
            Map.entry("evidence_destroyed", "销毁证据文件"),
            Map.entry("alarms_exported", "导出告警列表"),
            Map.entry("risks_exported", "导出风险列表"),
            Map.entry("disposal_requested", "申请处置授权"),
            Map.entry("disposal_approved", "批准处置授权"),
            Map.entry("disposal_rejected", "驳回处置授权"),
            Map.entry("disposal_executed", "执行处置"),
            Map.entry("disposal_stopped", "停止处置"),
            Map.entry("emergency_stop_requested", "急停本次处置"),
            Map.entry("emergency_stop_note_added", "补充急停原因"),
            Map.entry("emergency_stop_retried", "重试设备停止"),
            Map.entry("emergency_stop_manually_confirmed", "登记急停现场核查"),
            Map.entry("disposal_cancelled", "撤回处置申请"),
            Map.entry("disposal_manual_result", "登记人工处置结果"),
            Map.entry("punishment_case_filed", "立案"),
            Map.entry("punishment_case_assigned", "指派承办人"),
            Map.entry("punishment_lead_added", "登记待补充线索"),
            Map.entry("punishment_lead_resolved", "线索已补充"),
            Map.entry("punishment_discretion_drafted", "拟定裁量"),
            Map.entry("punishment_discretion_confirmed", "确认裁量"),
            Map.entry("punishment_reviewed", "复核定性依据"),
            Map.entry("punishment_decision_issued", "出具处罚决定书"),
            Map.entry("punishment_decision_revoked", "作废处罚决定书"),
            Map.entry("punishment_case_closed", "结案"),
            Map.entry("punishment_case_withdrawn", "撤案"),
            Map.entry("airspace_created", "新建空域"),
            Map.entry("airspace_version_created", "新增空域版本"),
            Map.entry("airspace_import_staged", "暂存空域导入"),
            Map.entry("airspace_import_confirmed", "确认空域导入"),
            Map.entry("airspace_import_discarded", "放弃空域导入"),
            Map.entry("airport_created", "新建机场"),
            Map.entry("airport_runway_created", "新增跑道"),
            Map.entry("airport_procedure_route_created", "新增进离场航线"),
            Map.entry("airport_protected_target_created", "新增保护目标"),
            Map.entry("airport_notification_target_created", "新增通报对象"),
            Map.entry("rule_evaluation_triggered", "手动触发空间风险评估"),
            Map.entry("eo_track_requested", "下发光电跟踪"),
            Map.entry("eo_track_ended", "停止光电跟踪"),
            Map.entry("map_package_uploaded", "上传离线地图包"),
            Map.entry("map_package_activated", "启用离线地图"),
            Map.entry("map_package_rolled_back", "回滚离线地图"),
            Map.entry("map_package_deleted", "删除离线地图包"));

    private static final Map<String, String> METHODS = Map.of(
            "GET", "查询", "POST", "提交", "PUT", "更新", "PATCH", "更新", "DELETE", "删除");

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("/organizations", "组织"),
            Map.entry("/districts", "区域"),
            Map.entry("/users", "用户"),
            Map.entry("/roles", "角色"),
            Map.entry("/permissions", "权限"),
            Map.entry("/audit-logs", "审计日志"),
            Map.entry("/auth", "认证"),
            Map.entry("/devices", "设备"),
            Map.entry("/commission", "设备调测"),
            Map.entry("/alarms", "告警"),
            Map.entry("/uav-events", "无人机事件"),
            Map.entry("/risks", "飞行风险"),
            Map.entry("/handoff-recipients", "交接接收方"),
            Map.entry("/handoffs", "业务交接"),
            Map.entry("/punishment-cases", "处罚案件"),
            Map.entry("/penalty-rules", "罚则档位"),
            Map.entry("/decision-documents", "处罚决定书"),
            Map.entry("/workbench", "工作台"),
            Map.entry("/legality-evaluations", "合法性研判"),
            Map.entry("/legality-assessments", "合法性研判"),
            Map.entry("/rule-effects", "规则效果"),
            Map.entry("/rule-set-versions", "规则集版本"),
            Map.entry("/rule-sets", "规则集"),
            Map.entry("/rule-runs", "规则运行"),
            Map.entry("/flight-plans", "飞行计划"),
            Map.entry("/airspace", "空域"),
            Map.entry("/stats", "运行统计"),
            Map.entry("/fusion", "融合引擎"),
            Map.entry("/targets", "目标"),
            Map.entry("/eo-tracking-tasks", "光电跟踪"),
            Map.entry("/tracks", "轨迹"),
            Map.entry("/evidence-files", "证据文件"),
            Map.entry("/evidence-chains", "证据链"),
            Map.entry("/airports", "机场"),
            Map.entry("/space-risks", "空间安全风险"),
            Map.entry("/space-object-subtypes", "异物细类"),
            Map.entry("/map-packages", "离线地图包"),
            Map.entry("/rule-evaluations", "风险规则评估"));

    private AuditLabels() {
    }

    static String role(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) return "";
        if ("ROLE-ADMIN".equals(roleCode)) return "超级管理员";
        return roleCode;
    }

    static String module(String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) return "";
        return MODULES.getOrDefault(moduleCode, moduleCode);
    }

    static String action(String action) {
        if (action == null || action.isBlank()) return "";
        String mapped = ACTIONS.get(action);
        if (mapped != null) return mapped;
        String[] parts = action.split("\\s+", 2);
        if (parts.length != 2) return action;
        String method = METHODS.get(parts[0].toUpperCase(Locale.ROOT));
        if (method == null) return action;
        for (Map.Entry<String, String> path : PATHS.entrySet()) {
            if (parts[1].contains(path.getKey())) return method + path.getValue();
        }
        return method + "接口";
    }

    static String result(String result) {
        if ("SUCCESS".equals(result)) return "成功";
        if ("FAILURE".equals(result)) return "失败";
        return result == null ? "" : result;
    }
}
