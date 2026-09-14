package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.SplitIds;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.Transition;

/** ID 状态机：3 帧→STABLE、3 s→SHORT_LOST、15 s→TERMINATED、合并保留更早 ID、分裂产生两新 ID、观察期 ID 不变。 */
class IdentityStateMachineTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private final IdentityStateMachine machine = new IdentityStateMachine(DemoFusionParams.demoV1());

    @Test
    void threeConsecutiveHitsPromoteTentativeToStable() {
        TrackState state = TrackState.created(T0);
        assertThat(state.status()).isEqualTo(TrackStatus.TENTATIVE);
        Transition first = machine.onHit(state, T0);
        Transition second = machine.onHit(first.state(), T0.plusSeconds(1));
        // 观察期内目标身份不变：状态机只累计命中，不会要求重新建目标。
        assertThat(second.state().status()).isEqualTo(TrackStatus.TENTATIVE);
        assertThat(second.changed()).isFalse();
        assertThat(second.state().confirmHits()).isEqualTo(2);
        Transition third = machine.onHit(second.state(), T0.plusSeconds(2));
        assertThat(third.state().status()).isEqualTo(TrackStatus.STABLE);
        assertThat(third.changed()).isTrue();
        assertThat(third.state().since()).isEqualTo(T0.plusSeconds(2));
        assertThat(third.state().missFrames()).isZero();
        assertThat(third.state().lastObservedAt()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    void noSourceForThreeSecondsIsShortLostAndFifteenSecondsTerminates() {
        TrackState stable = new TrackState(TrackStatus.STABLE, T0, 3, 0, T0);
        Transition soon = machine.onFrame(stable, T0.plusMillis(2000));
        assertThat(soon.state().status()).isEqualTo(TrackStatus.STABLE);
        assertThat(soon.state().missFrames()).isEqualTo(1);
        Transition lost = machine.onFrame(soon.state(), T0.plusMillis(3001));
        assertThat(lost.state().status()).isEqualTo(TrackStatus.SHORT_LOST);
        assertThat(lost.changed()).isTrue();
        assertThat(lost.state().missFrames()).isEqualTo(2);
        Transition terminated = machine.onFrame(lost.state(), T0.plusMillis(15001));
        assertThat(terminated.state().status()).isEqualTo(TrackStatus.TERMINATED);
        // 终止后再来帧也不再变化。
        assertThat(machine.onFrame(terminated.state(), T0.plusMillis(20000)).changed()).isFalse();
    }

    @Test
    void reacquiredShortLostTargetReturnsToStableWithSameIdentity() {
        TrackState lost = new TrackState(TrackStatus.SHORT_LOST, T0.plusSeconds(4), 3, 4, T0);
        Transition back = machine.onHit(lost, T0.plusSeconds(5));
        assertThat(back.state().status()).isEqualTo(TrackStatus.STABLE);
        assertThat(back.state().missFrames()).isZero();
        assertThat(back.changed()).isTrue();
        // 合并/分裂/终止是终态，不再接受命中改状态。
        for (TrackStatus terminal : new TrackStatus[]{TrackStatus.MERGE, TrackStatus.SPLIT, TrackStatus.TERMINATED}) {
            TrackState state = new TrackState(terminal, T0, 3, 0, T0);
            assertThat(machine.onHit(state, T0.plusSeconds(1)).state().status()).isEqualTo(terminal);
        }
    }

    @Test
    void mergeKeepsTheEarlierTargetAndSplitProducesTwoFreshIds() {
        assertThat(machine.chooseSurvivor("later", T0.plusSeconds(10), "earlier", T0)).isEqualTo("earlier");
        assertThat(machine.chooseSurvivor("b", T0, "a", T0)).as("同时首见时取稳定的较小 ID").isEqualTo("a");
        SplitIds ids = machine.newSplitIds("origin");
        assertThat(ids.childA()).isNotEqualTo(ids.childB());
        assertThat(ids.childA()).isNotEqualTo("origin");
        assertThat(ids.childB()).isNotEqualTo("origin");
        assertThat(machine.mergeEligible(new TrackState(TrackStatus.STABLE, T0, 3, 0, T0), new TrackState(TrackStatus.STABLE, T0, 3, 0, T0))).isTrue();
        assertThat(machine.mergeEligible(new TrackState(TrackStatus.STABLE, T0, 3, 0, T0), new TrackState(TrackStatus.TENTATIVE, T0, 1, 0, T0))).isFalse();
        // 合并距离门限 = merge_max_dist_sigma·σ = 2.0·σ；分裂门限来自参数。
        assertThat(machine.mergeDistanceThreshold(21.2)).isCloseTo(42.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(machine.mergeMinFrames()).isEqualTo(4);
        assertThat(machine.splitMinFrames()).isEqualTo(4);
        assertThat(machine.splitMinSeparationM()).isEqualTo(100.0);
    }
}
