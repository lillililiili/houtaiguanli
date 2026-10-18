package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 审计日志里不许出现英文码（阶段 18）。
 *
 * <p>逐个补中文是补不完的：阶段 15 的风险导出写的是复数 `risks`、告警核实写的是单数 `alarm`，
 * 两个都没进字典，于是审计日志上直接显示英文。所以这里不是再列一遍清单，而是**从源码里把真正写进审计的码扫出来**，
 * 挨个要求字典有中文——以后谁新增一个动作码而忘了配中文，这条就红。
 */
class AuditLabelsCoverageTest {

    @Test
    void conditionalDeviceAndBrokerEnablementActionsHaveChineseLabels() {
        for (String code : List.of("device_enable", "device_disable", "device_delete",
                "mqtt_broker_create", "mqtt_broker_update", "mqtt_broker_enable", "mqtt_broker_disable")) {
            assertThat(AuditLabels.action(code)).as("设备运维动作 %s 的中文标签", code)
                    .isNotEqualTo(code).matches(".*[\\p{IsHan}].*");
        }
    }

    /** 问题清单 #20：自动核验、模拟接入、自动通知等由变量或拼接写入的动作码，字典里都要有中文。 */
    @Test
    void automaticAndComposedActionsSeenInAcceptanceHaveChineseLabels() {
        for (String code : List.of("device_maintenance_auto_check", "device_maintenance_auto_recovered",
                "device_maintenance_recovery_invalidated", "device_maintenance_created", "device_maintenance_confirm_complete",
                "device_incident_auto_recovered", "mqtt_device_register", "mqtt_device_disable", "auto_sms_delivered",
                "auto_sms_failed", "auto_voice_played", "auto_voice_unknown", "notification_departure_recorded",
                "handoff_followup_created", "handoff_notification_decided", "eo_auto_paused", "integration_source_disable",
                "local_normalized_observation", "local_qa_device_status", "device_planned_offline")) {
            assertThat(AuditLabels.hasActionLabel(code)).as(code).isTrue();
            assertThat(AuditLabels.action(code)).as(code).matches(".*[\\p{IsHan}].*").doesNotContain("_");
        }
        for (String code : AuditLabels.AUTOMATIC_ACTIONS) assertThat(AuditLabels.hasActionLabel(code)).as(code).isTrue();
    }

    /** 未配中文的码不直接显示：动作写“其他操作”、结果写“其他结果”、对象写“其他对象”，原代码由详情保留。 */
    @Test
    void unknownCodesFallBackToChineseWithoutHidingKnownOnes() {
        assertThat(AuditLabels.action("brand_new_action")).isEqualTo("其他操作");
        assertThat(AuditLabels.action("COUNTERMEASURE_SET_OK")).isEqualTo("其他操作");
        assertThat(AuditLabels.action("GET /api/v1/handoffs")).isEqualTo("查询业务交接");
        assertThat(AuditLabels.action("自定义中文动作")).isEqualTo("自定义中文动作");
        assertThat(AuditLabels.result("FAILED")).isEqualTo("失败");
        assertThat(AuditLabels.result("FAILURE")).isEqualTo("失败");
        assertThat(AuditLabels.result("SUCCESS")).isEqualTo("成功");
        assertThat(AuditLabels.result("PARTIAL_X")).isEqualTo("其他结果");
        assertThat(AuditLabels.objectType("device_maintenance_task")).isEqualTo("设备异常处置单");
        assertThat(AuditLabels.objectType("brand_new_object")).isEqualTo("其他对象");
        assertThat(AuditLabels.module("brand_new_module")).isEqualTo("其他");
        assertThat(AuditLabels.automatic("local_normalized_observation")).isTrue();
        assertThat(AuditLabels.automatic("login_success")).isFalse();
    }

    @Test
    void conditionalEvidencePreviewActionsHaveChineseLabels() {
        assertThat(AuditLabels.action("evidence_preview")).isEqualTo("预览证据文件");
        assertThat(AuditLabels.action("evidence_thumbnail")).isEqualTo("读取证据缩略图");
    }

    @Test
    void dynamicallyNamedMaintenanceWorkflowActionsHaveChineseLabels() {
        for (String action : List.of("START", "SAVE_PROGRESS", "SUBMIT_VERIFICATION", "VERIFY_RECOVERY",
                "COMPLETE", "RESUME", "LINK_COMMISSION", "WORKFLOW_REJECTED")) {
            String code = "device_maintenance_" + action.toLowerCase(java.util.Locale.ROOT);
            assertThat(AuditLabels.action(code)).as("运维动作 %s 的中文标签", code)
                    .isNotEqualTo(code).matches(".*[\\p{IsHan}].*");
        }
        assertThat(AuditLabels.module("devices")).isEqualTo("设备管理");
    }

    /**
     * `record` 有两个重载，字面量的位置不同，混在一起扫会把动作码当成模块码：
     * 十一参的形状是 `record(id, account, roleCode, "模块", "动作", ...)`；
     * 七参的形状是 `record(id, account, "动作", "对象类型", ...)`——**没有模块码**。
     * 用第三个参数是不是字符串字面量把两者分开。
     */
    private static final Pattern MODULE_CONSTANT = Pattern.compile("MODULE\\s*=\\s*\"([a-z_]+)\"");
    private static final Pattern WITH_CONSTANT = Pattern.compile("MODULE\\s*,\\s*\"([a-z_]+)\"");
    private static final Pattern ELEVEN_ARGS =
            Pattern.compile("record\\([^;\"]*?roleCode\\(\\)\\s*,\\s*\"([a-z_]+)\"\\s*,\\s*\"([a-z_]+)\"", Pattern.DOTALL);
    private static final Pattern SEVEN_ARGS =
            Pattern.compile("record\\([^;\"]*?account\\(\\)\\s*,\\s*\"([a-z_]+)\"\\s*,", Pattern.DOTALL);

    @Test
    void everyModuleAndActionCodeWrittenToTheAuditLogHasChinese() throws IOException {
        Set<String> modules = new LinkedHashSet<>(), actions = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("audit.record(") && !source.contains("MODULE =")) continue;
                Matcher constant = MODULE_CONSTANT.matcher(source);
                if (constant.find()) modules.add(constant.group(1));
                Matcher withConstant = WITH_CONSTANT.matcher(source);
                while (withConstant.find()) actions.add(withConstant.group(1));
                Matcher eleven = ELEVEN_ARGS.matcher(source);
                while (eleven.find()) { modules.add(eleven.group(1)); actions.add(eleven.group(2)); }
                Matcher seven = SEVEN_ARGS.matcher(source);
                while (seven.find()) actions.add(seven.group(1));
            }
        }
        assertThat(modules).as("扫到的模块码").isNotEmpty();

        List<String> missingModules = modules.stream()
                .filter(code -> !AuditLabels.hasModuleLabel(code)).sorted().toList();
        List<String> missingActions = actions.stream()
                .filter(code -> !AuditLabels.hasActionLabel(code)).sorted().toList();

        assertThat(missingModules).as("这些模块码在审计日志里会显示成英文").isEmpty();
        assertThat(missingActions).as("这些动作码在审计日志里会显示成英文").isEmpty();
    }
}
