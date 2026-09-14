package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Candidate;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Result;

/** 关联代价：门限 d ≤ gate_sigma·σ 且 c ≤ cost_max；AGL 与 AMSL 永不互比。 */
class AssociationCostTest {
    static final FusionDomainKey DOMAIN = new FusionDomainKey("replay", "org-a", "district-a");
    static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private final AssociationCost cost = new AssociationCost(DemoFusionParams.demoV1());

    static SourceObservation observation(double lon, double lat, Double amsl, Double agl, Double speed, Double heading, String classCode, Instant at) {
        return new SourceObservation("obs-" + lon + "-" + lat, null, "src-radar", "replay-radar-a", "RADAR", "CONFIRMED", null, "session", "R-1", null,
                at, at, lon, lat, 15.0, amsl, agl, speed, heading, classCode, null, null, null, null, Map.of(), "replay", "org-a", "district-a", 1L);
    }

    static Candidate candidate(String id, double lon, double lat, Double amsl, Double agl, Double speed, Double heading, String classCode, Instant lastSeen, int hits, int misses) {
        return new Candidate(id, DOMAIN, lon, lat, 15.0, amsl, agl, speed, heading, classCode, lastSeen.toEpochMilli(), hits, misses);
    }

    @Test
    void observationOutsideGateIsNotAssociable() {
        // σ = √(15²+15²) ≈ 21 m，门限 3σ ≈ 64 m；500 m 外的观测不进门限。
        Result far = cost.evaluate(observation(118.6, 37.4, null, null, null, null, null, T0), 15.0,
                candidate("t1", 118.6 + 0.0056, 37.4, null, null, null, null, null, T0, 5, 0));
        assertThat(far.distanceM()).isBetween(450.0, 550.0);
        assertThat(far.gated()).isFalse();
        Result near = cost.evaluate(observation(118.6, 37.4, null, null, null, null, null, T0), 15.0,
                candidate("t1", 118.6 + 0.00005, 37.4, null, null, null, null, null, T0, 5, 0));
        assertThat(near.distanceM()).isLessThan(10.0);
        assertThat(near.gated()).isTrue();
        assertThat(near.cost()).isLessThan(6.0);
    }

    @Test
    void altitudeTermIsSkippedWhenDatumsDiffer() {
        SourceObservation amslOnly = observation(118.6, 37.4, 120.0, null, null, null, null, T0);
        Candidate aglOnly = candidate("t1", 118.6, 37.4, null, 40.0, null, null, null, T0, 5, 0);
        Result mixed = cost.evaluate(amslOnly, 15.0, aglOnly);
        assertThat(mixed.unknowns()).contains("ALTITUDE_NOT_COMPARABLE");
        // 两者都没有高度时代价相同：不可比的高度项不能贡献任何数值。
        Result none = cost.evaluate(observation(118.6, 37.4, null, null, null, null, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, null, null, null, T0, 5, 0));
        assertThat(mixed.cost()).isEqualTo(none.cost());
        // 同基准（AMSL 对 AMSL）相差 60 m → 高度项 w_alt·(60/30)² = 2.0。
        Result sameDatum = cost.evaluate(amslOnly, 15.0, candidate("t1", 118.6, 37.4, 60.0, null, null, null, null, T0, 5, 0));
        assertThat(sameDatum.cost() - none.cost()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(sameDatum.unknowns()).doesNotContain("ALTITUDE_NOT_COMPARABLE");
    }

    @Test
    void classMismatchAndUnknownClassUseContractSteps() {
        Result same = cost.evaluate(observation(118.6, 37.4, null, null, null, null, "UAV", T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, null, null, "UAV", T0, 5, 0));
        Result unknown = cost.evaluate(observation(118.6, 37.4, null, null, null, null, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, null, null, "UAV", T0, 5, 0));
        Result different = cost.evaluate(observation(118.6, 37.4, null, null, null, null, "BIRD", T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, null, null, "UAV", T0, 5, 0));
        // w_class=0.4：mismatch 0 / 0.5 / 1。
        assertThat(unknown.cost() - same.cost()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(different.cost() - same.cost()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(unknown.unknowns()).contains("CLASS_UNKNOWN");
    }

    @Test
    void motionAndStabilityTermsFollowParameters() {
        Result stable = cost.evaluate(observation(118.6, 37.4, null, null, 10.0, 90.0, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, 10.0, 90.0, null, T0, 9, 1));
        Result unstable = cost.evaluate(observation(118.6, 37.4, null, null, 10.0, 90.0, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, 10.0, 90.0, null, T0, 1, 9));
        // w_hist=0.3：稳定度 0.9 → 0.03，稳定度 0.1 → 0.27。
        assertThat(unstable.cost() - stable.cost()).isCloseTo(0.24, org.assertj.core.data.Offset.offset(1e-9));
        Result turned = cost.evaluate(observation(118.6, 37.4, null, null, 16.0, 135.0, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, 10.0, 90.0, null, T0, 9, 1));
        // w_motion=0.6：(6/6)² + (45/45)² = 2 → 1.2。
        assertThat(turned.cost() - stable.cost()).isCloseTo(1.2, org.assertj.core.data.Offset.offset(1e-9));
        Result noMotion = cost.evaluate(observation(118.6, 37.4, null, null, null, null, null, T0), 15.0,
                candidate("t1", 118.6, 37.4, null, null, 10.0, 90.0, null, T0, 9, 1));
        assertThat(noMotion.unknowns()).contains("MOTION_NOT_COMPARABLE");
        assertThat(noMotion.cost()).isEqualTo(stable.cost());
    }
}
