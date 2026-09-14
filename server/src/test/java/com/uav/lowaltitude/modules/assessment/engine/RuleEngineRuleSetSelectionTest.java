package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;

/**
 * 决策 9-28 的回归：合法性引擎每个 tick 只取"成员含 C03"的规则集。test profile 下阶段 7 种子把 LEGALITY-DEMO 置 ACTIVE、
 * 阶段 9 种子把 SPACE-RISK-DEMO（成员只有 C04/C05）置 ACTIVE；失败模式是静默的（引擎悄悄跳过或整轮因缺 C03 参数失败），所以必须钉住。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RuleEngineRuleSetSelectionTest {

    @Autowired RuleEngineRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void workerOnlyPicksLegalityRuleSetsEvenWhenSpaceRiskSetIsActive() {
        // 前提：两个规则集都处于生效状态（阶段 9 种子在 PostGIS 缺席时也会激活规则集）。
        Long activeSpaceRisk = jdbc.queryForObject(
                "select count(*) from rule_set where rule_set_code='SPACE-RISK-DEMO' and active_version_id is not null", Long.class);
        assertThat(activeSpaceRisk).as("SPACE-RISK-DEMO 应由阶段 9 种子激活").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from rule_set where rule_set_code='LEGALITY-DEMO' and active_version_id is not null", Long.class)).isEqualTo(1L);

        List<String> codes = repository.ruleSetsWithVersions().stream().map(RuleSetRow::ruleSetCode).toList();
        assertThat(codes).contains("LEGALITY-DEMO").doesNotContain("SPACE-RISK-DEMO");
    }

    @Test
    void legalitySetIsStillPickedWhenOnlyItsShadowVersionIsSet() {
        // 影子版本也算"有版本"：把 LEGALITY-DEMO 改成只有影子版本，仍应被取到；SPACE-RISK-DEMO 依旧不取。
        jdbc.update("update rule_set set shadow_version_id=active_version_id, active_version_id=null where rule_set_code='LEGALITY-DEMO'");
        List<String> codes = repository.ruleSetsWithVersions().stream().map(RuleSetRow::ruleSetCode).toList();
        assertThat(codes).contains("LEGALITY-DEMO").doesNotContain("SPACE-RISK-DEMO");
    }
}
