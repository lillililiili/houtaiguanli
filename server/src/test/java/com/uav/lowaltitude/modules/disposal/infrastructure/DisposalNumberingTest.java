package com.uav.lowaltitude.modules.disposal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;

/** 发号与策略读取的最小落库验证：先证明 ON CONFLICT 与 FOR UPDATE 在 H2 上成立，再往上盖服务层。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DisposalNumberingTest {

    @Autowired DisposalRepository repository;
    @Autowired DisposalPolicyRepository policies;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void sequenceStartsAtOneAndIncrementsPerDay() {
        // 基线从 disposal_no_counter 读，不假定起点是 1（种子已经把当日推到 16 了），
        // 也不放宽成"大于 0"——那样连"发号根本没自增"都测不出来。断言：取到的号 == 取号前的计数，且严格递增。
        String day = "20260907";
        int baseline = counter(day);
        int first = repository.nextSequence(day);
        assertThat(first).isEqualTo(baseline);
        assertThat(counter(day)).isEqualTo(baseline + 1);
        assertThat(repository.nextSequence(day)).isEqualTo(first + 1);
        assertThat(repository.nextSequence(day)).isEqualTo(first + 2);
        // 每一天各记各的：中间插一次别的日期，不影响本日继续往下发。
        String otherDay = "29991230";
        int otherFirst = repository.nextSequence(otherDay);
        assertThat(repository.nextSequence(day)).isEqualTo(first + 3);
        assertThat(repository.nextSequence(otherDay)).isEqualTo(otherFirst + 1);
        assertThat(DisposalRules.authorizationNo(day, first)).isEqualTo(
                String.format("AUTH-%s-%04d", day, first));
    }

    /** 当日计数行还没建时，下一个号就是 1。 */
    private int counter(String day) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(next_no),1) FROM disposal_no_counter WHERE day_key=?",
                Integer.class, day);
        return next == null ? 1 : next;
    }

    @Test
    void activePolicyIsDemoV1WithAllParamsPresent() {
        var policy = policies.active();
        assertThat(policy.policyCode()).isEqualTo("demo-v1");
        // 客户 Q5 未答复前必须是 DEMO：标成 CONFIRMED 等于替客户认下了授权条件（决策 13-1）。
        assertThat(policy.demo()).isTrue();
        assertThat(policy.approvalRequired()).isTrue();
        assertThat(policy.twoPersonRule()).isTrue();
        assertThat(policy.maxActivePerSubject()).isEqualTo(1);
        assertThat(policy.timeLimitMinutes("COUNTERMEASURE")).isEqualTo(30);
        assertThat(policy.timeLimitMinutes("DISPERSAL")).isEqualTo(15);
        assertThat(policy.requiresConfirmedEvent("COUNTERMEASURE")).isTrue();
        assertThat(policy.requiresConfirmedEvent("DISPERSAL")).isFalse();
        for (String action : DisposalRules.ACTION_TYPES) {
            assertThat(policy.command(action)).as(action).isNotNull();
        }
    }
}
