package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

/**
 * 阶段 14 契约基线：五个处罚案件权限码已登记为枚举；审计字典能翻译处罚模块与十一个动作。目录行由领导迁移 V202609080101 插入。
 */
class Stage14ContractTest {

    private static final List<String> STAGE14_CODES = List.of("punishment:read", "punishment:file", "punishment:decide", "punishment:review", "punishment:close");

    @Test
    void permissionCodesAreRegistered() {
        List<String> values = Arrays.stream(PermissionCode.values()).map(PermissionCode::value).toList();
        assertThat(values).containsAll(STAGE14_CODES);
    }

    @Test
    void auditLabelsCoverPunishmentModuleAndActions() {
        assertThat(AuditLabels.module("punishment")).isEqualTo("处罚案件");
        for (String action : List.of("punishment_case_filed", "punishment_case_assigned", "punishment_lead_added", "punishment_lead_resolved",
                "punishment_discretion_drafted", "punishment_discretion_confirmed", "punishment_reviewed", "punishment_decision_issued",
                "punishment_decision_revoked", "punishment_case_closed", "punishment_case_withdrawn")) {
            assertThat(AuditLabels.action(action)).as(action).doesNotContain("_");
        }
    }
}
