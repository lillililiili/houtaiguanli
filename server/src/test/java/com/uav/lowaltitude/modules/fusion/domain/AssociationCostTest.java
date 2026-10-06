package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    /** 报出机身序列号的观测（凌云协议 extension.uavSN 由映射器写进 quality.uav_sn）。 */
    static SourceObservation serialObservation(double lon, double lat, String serial) {
        Map<String, Object> quality = new HashMap<>();
        if (serial != null) quality.put(IdentitySerials.QUALITY_KEY, serial);
        return new SourceObservation("obs-" + serial, null, "src-tdoa", "replay-tdoa-a", "TDOA", "DEMO", null, "session", "D-1", null,
                T0, T0, lon, lat, 60.0, null, null, null, null, "UAV", null, serial, null, null, quality, "replay", "org-a", "district-a", 1L);
    }

    static Candidate serialCandidate(String id, double lon, double lat, Set<String> serials) {
        return new Candidate(id, DOMAIN, lon, lat, 15.0, null, null, null, null, "UAV", T0.toEpochMilli(), 5, 0, serials);
    }

    @Test
    void differentSerialNumbersNeverAssociateHoweverClose() {
        // ZT-01：两架都报出了序列号、序列号不同，同一个位置也不是同一架——直接判门外，不是加一项代价。
        Result conflict = cost.evaluate(serialObservation(118.6, 37.4, "SN-B"), 60.0, serialCandidate("t1", 118.6, 37.4, Set.of("SN-A")));
        assertThat(conflict.gated()).isFalse();
        assertThat(conflict.cost()).isInfinite();
        assertThat(conflict.unknowns()).containsExactly("IDENTITY_CONFLICT");
        // 同一个序列号（大小写、首尾空白不同也算同一台）照常关联。
        Result same = cost.evaluate(serialObservation(118.6, 37.4, " sn-a "), 60.0, serialCandidate("t1", 118.6, 37.4, Set.of("SN-A")));
        assertThat(same.gated()).isTrue();
        assertThat(same.unknowns()).doesNotContain("IDENTITY_CONFLICT");
    }

    @Test
    void missingSerialOnEitherSideFallsBackToPositionAndMotion() {
        // 雷达报不出序列号；目标还没有任何来源报出序列号时也不做判断——交回位置/运动/类别去关联。
        Result radar = cost.evaluate(serialObservation(118.6, 37.4, null), 60.0, serialCandidate("t1", 118.6, 37.4, Set.of("SN-A")));
        Result fresh = cost.evaluate(serialObservation(118.6, 37.4, "SN-B"), 60.0, serialCandidate("t1", 118.6, 37.4, Set.of()));
        assertThat(radar.gated()).isTrue();
        assertThat(fresh.gated()).isTrue();
        // 旧签名的候选等同于"没有已知序列号"。
        assertThat(cost.evaluate(serialObservation(118.6, 37.4, "SN-B"), 60.0,
                candidate("t1", 118.6, 37.4, null, null, null, null, "UAV", T0, 5, 0)).gated()).isTrue();
    }

    @Test
    void associatorNeverPutsADifferentSerialOnTheNearerTarget() {
        // 观测离 t1（序列号 SN-A）更近，但它报的是 SN-B：只能落到 t2（还没有序列号）上，即使 t2 远一些。
        Associator associator = new Associator(cost);
        double east40m = 0.00045;
        Associator.Result result = associator.associate(java.util.List.of(serialObservation(118.6, 37.4, "SN-B")), java.util.List.of(60.0),
                java.util.List.of(serialCandidate("t1", 118.6, 37.4, Set.of("SN-A")), serialCandidate("t2", 118.6 + east40m, 37.4, Set.of())));
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).targetId()).isEqualTo("t2");
        // 被否决的候选不进歧义名单：它根本不在门内。
        assertThat(result.ambiguities()).isEmpty();
    }
}
