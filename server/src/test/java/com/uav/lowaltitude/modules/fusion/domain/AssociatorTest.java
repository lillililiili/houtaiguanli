package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Candidate;
import com.uav.lowaltitude.modules.fusion.domain.Associator.Result;

/** 同帧同源观测与目标的匈牙利匹配：门限外不匹配、总代价最小、歧义进待定、跨分区永不匹配。 */
class AssociatorTest {
    private static final Instant T0 = AssociationCostTest.T0;
    private final Associator associator = new Associator(new AssociationCost(DemoFusionParams.demoV1()));

    @Test
    void twoObservationsTwoTargetsTakeMinimumTotalCost() {
        // 经度 0.0001° ≈ 8.9 m。obs A 在 T1 旁 9 m、离 T2 27 m；obs B 在 T2 旁 9 m。贪心按 A→T1 也对，但总代价最小同样是 A→T1、B→T2。
        SourceObservation a = AssociationCostTest.observation(118.6001, 37.4, null, null, null, null, null, T0);
        SourceObservation b = AssociationCostTest.observation(118.6005, 37.4, null, null, null, null, null, T0);
        Candidate t1 = AssociationCostTest.candidate("t1", 118.6000, 37.4, null, null, null, null, null, T0, 5, 0);
        Candidate t2 = AssociationCostTest.candidate("t2", 118.6004, 37.4, null, null, null, null, null, T0, 5, 0);
        Result result = associator.associate(List.of(a, b), List.of(15.0, 15.0), List.of(t1, t2));
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches()).anySatisfy(m -> { assertThat(m.observationIndex()).isEqualTo(0); assertThat(m.targetId()).isEqualTo("t1"); });
        assertThat(result.matches()).anySatisfy(m -> { assertThat(m.observationIndex()).isEqualTo(1); assertThat(m.targetId()).isEqualTo("t2"); });
        assertThat(result.unmatchedObservations()).isEmpty();
        assertThat(result.unmatchedTargets()).isEmpty();
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void observationOutsideEveryGateStaysUnmatched() {
        SourceObservation far = AssociationCostTest.observation(118.61, 37.41, null, null, null, null, null, T0);
        Candidate t1 = AssociationCostTest.candidate("t1", 118.6, 37.4, null, null, null, null, null, T0, 5, 0);
        Result result = associator.associate(List.of(far), List.of(15.0), List.of(t1));
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedObservations()).containsExactly(0);
        assertThat(result.unmatchedTargets()).containsExactly("t1");
    }

    @Test
    void nearlyEqualCostsBecomeAmbiguityButKeepBestMatch() {
        // 两目标相距 2 m，观测在两者中间：代价差远小于 1.0 → GATE_AMBIGUOUS，最优匹配仍保留。
        SourceObservation between = AssociationCostTest.observation(118.60001, 37.4, null, null, null, null, null, T0);
        Candidate t1 = AssociationCostTest.candidate("t1", 118.60000, 37.4, null, null, null, null, null, T0, 5, 0);
        Candidate t2 = AssociationCostTest.candidate("t2", 118.60002, 37.4, null, null, null, null, null, T0, 5, 0);
        Result result = associator.associate(List.of(between), List.of(15.0), List.of(t1, t2));
        assertThat(result.matches()).hasSize(1);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.ambiguities().get(0).observationIndex()).isEqualTo(0);
        assertThat(result.ambiguities().get(0).candidateTargetIds()).containsExactlyInAnyOrder("t1", "t2");
        assertThat(result.ambiguities().get(0).bestTargetId()).isEqualTo(result.matches().get(0).targetId());
    }

    @Test
    void candidatesFromAnotherDomainNeverMatchEvenWhenColocated() {
        SourceObservation obs = AssociationCostTest.observation(118.6, 37.4, null, null, null, null, null, T0);
        Candidate other = new Candidate("t-other", new FusionDomainKey("mock", "org-a", "district-a"), 118.6, 37.4, 15.0,
                null, null, null, null, null, T0.toEpochMilli(), 5, 0);
        Candidate otherOrg = new Candidate("t-org", new FusionDomainKey("replay", "org-b", "district-a"), 118.6, 37.4, 15.0,
                null, null, null, null, null, T0.toEpochMilli(), 5, 0);
        Result result = associator.associate(List.of(obs), List.of(15.0), List.of(other, otherOrg));
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedObservations()).containsExactly(0);
        assertThat(result.unmatchedTargets()).containsExactlyInAnyOrder("t-other", "t-org");
    }

    @Test
    void sameFrameObservationsNeverShareOneTarget() {
        // 两观测都贴着唯一的目标：只能有一个匹配，另一个必须落空（同一来源同一帧两回波不能进同一目标）。
        SourceObservation a = AssociationCostTest.observation(118.60001, 37.4, null, null, null, null, null, T0);
        SourceObservation b = AssociationCostTest.observation(118.59999, 37.4, null, null, null, null, null, T0);
        Candidate t1 = AssociationCostTest.candidate("t1", 118.6, 37.4, null, null, null, null, null, T0, 5, 0);
        Result result = associator.associate(List.of(a, b), List.of(15.0, 15.0), List.of(t1));
        assertThat(result.matches()).hasSize(1);
        assertThat(result.unmatchedObservations()).hasSize(1);
    }

    @Test
    void largeFramesFallBackToGreedyWithSameGating() {
        int n = Associator.HUNGARIAN_MAX_N + 1;
        List<SourceObservation> observations = new java.util.ArrayList<>();
        List<Double> accuracies = new java.util.ArrayList<>();
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double lon = 118.6 + i * 0.01;
            observations.add(AssociationCostTest.observation(lon + 0.00002, 37.4, null, null, null, null, null, T0));
            accuracies.add(15.0);
            candidates.add(AssociationCostTest.candidate("t" + i, lon, 37.4, null, null, null, null, null, T0, 5, 0));
        }
        Result result = associator.associate(observations, accuracies, candidates);
        assertThat(result.matches()).hasSize(n);
        assertThat(result.matches()).allSatisfy(m -> assertThat(m.targetId()).isEqualTo("t" + m.observationIndex()));
    }
}
