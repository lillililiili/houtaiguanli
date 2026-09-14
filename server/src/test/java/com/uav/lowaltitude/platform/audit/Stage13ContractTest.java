package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

/**
 * 阶段 13 契约基线：五个处置授权权限码已登记为枚举；审计字典能翻译处置模块与七个动作。
 * 目录行由 E1 的迁移 0101 插入并在 DisposalAuthorizationApiTest 断言"只登记不授权"。
 */
class Stage13ContractTest {

    private static final List<String> STAGE13_CODES = List.of("disposal:read", "disposal:request", "disposal:approve", "disposal:execute", "disposal:stop");

    @Test
    void permissionCodesAreRegistered() {
        List<String> values = Arrays.stream(PermissionCode.values()).map(PermissionCode::value).toList();
        assertThat(values).containsAll(STAGE13_CODES);
    }

    @Test
    void auditLabelsCoverDisposalModuleAndActions() {
        assertThat(AuditLabels.module("disposal")).isEqualTo("处置授权");
        for (String action : List.of("disposal_requested", "disposal_approved", "disposal_rejected", "disposal_executed",
                "disposal_stopped", "disposal_cancelled", "disposal_manual_result")) {
            assertThat(AuditLabels.action(action)).as(action).doesNotContain("_");
        }
    }
}
