package com.uav.lowaltitude.modules.prediction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.util.List;

import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.Config;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.Decision;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.State;

class PredictionStabilityTest {
    private final PredictionStability machine = new PredictionStability();

    @Test
    void requiresThreeDistinctMeasurementsOverTwoSecondsAndSixOneHertzClearMeasurements() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        assertThat(s.active()).isTrue();
        for (int i = 3; i < 8; i++) s = step(s, Decision.CLEAR, i * 1000).state();
        assertThat(s.active()).as("five safe observations span only four seconds").isTrue();
        var last = step(s, Decision.CLEAR, 8000);
        assertThat(last.cleared()).isTrue();
        assertThat(last.state().active()).isFalse();
    }

    @Test
    void repeatedOrRevisedSameTimestampDoesNotAdvanceTheCounter() {
        State s = step(State.initial(), Decision.ENTER, 1000).state();
        assertThat(step(s, Decision.ENTER, 1000).ignored()).isTrue();
        var revision = machine.advance(s, Decision.ENTER, "revised", 1000, "scope:track:policy:area-v1", Config.defaults());
        assertThat(revision.ignored()).isTrue();
        assertThat(revision.state().streakCount()).isEqualTo(1);
    }

    @Test
    void unknownOrGapInterruptsSafeStreakWithoutClearingActiveWarning() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        s = step(s, Decision.CLEAR, 3000).state();
        s = step(s, Decision.UNKNOWN, 4000).state();
        assertThat(s.active()).isTrue();
        assertThat(s.streakCount()).isZero();
        s = step(s, Decision.CLEAR, 5000).state();
        s = step(s, Decision.CLEAR, 9000).state();
        assertThat(s.active()).isTrue();
        assertThat(s.streakCount()).isEqualTo(1);
    }

    @Test
    void effectiveVersionChangeResetsEvenWhenItReevaluatesSameAnchor() {
        State s = step(State.initial(), Decision.ENTER, 1000).state();
        s = step(s, Decision.ENTER, 2000).state();
        var revised = machine.advance(s, Decision.ENTER, "p2000", 2000, "scope:track:policy:area-v2", Config.defaults());
        assertThat(revised.ignored()).isTrue();
        assertThat(revised.state().streakCount()).isZero();
        assertThat(revised.state().active()).isFalse();
    }

    @Test
    void changingHorizonOnTheSameObservationInvalidatesButNeverStartsAStreak() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        var safe = step(s, Decision.CLEAR, 2000);
        assertThat(safe.ignored()).isTrue();
        assertThat(safe.state().active()).isTrue();
        assertThat(safe.state().streakCount()).isZero();
        var back = step(safe.state(), Decision.ENTER, 2000);
        assertThat(back.ignored()).isTrue();
        assertThat(back.state().streakCount()).isZero();
        assertThat(back.activated()).isFalse();
        State restored = back.state();
        for (int i = 3; i < 6; i++) restored = step(restored, Decision.ENTER, i * 1000).state();
        assertThat(restored.streakCount()).isEqualTo(3);
        assertThat(restored.streakStartedAt()).isEqualTo(3000);
    }

    @Test
    void verifiedIntermediateMeasurementsBridgeSkippedWorkerSamplesWithoutCountingThem() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        var next = machine.advance(s, Decision.ENTER, "p4100", 4100, "scope:track:policy:area-v1", Config.defaults(),
                List.of(2000L, 3100L, 4100L));
        assertThat(next.state().active()).isTrue();
        assertThat(next.state().streakCount()).isEqualTo(4);
        assertThat(next.state().streakStartedAt()).isEqualTo(0);
        assertThat(next.activated()).isFalse();
        var repeated = machine.advance(next.state(), Decision.ENTER, "p4100", 4100, "scope:track:policy:area-v1",
                Config.defaults(), List.of(2000L, 3100L, 4100L));
        assertThat(repeated.ignored()).isTrue();
        assertThat(repeated.state().streakCount()).isEqualTo(4);
    }

    @Test
    void actualGapOrWindowMissingEitherAnchorCannotBridgeContinuity() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        for (List<Long> times : List.of(List.of(2000L, 4100L), List.of(3100L, 4100L), List.of(2000L, 3100L))) {
            var next = machine.advance(s, Decision.ENTER, "p4100", 4100, "scope:track:policy:area-v1", Config.defaults(), times);
            assertThat(next.state().streakCount()).isEqualTo(1);
            assertThat(next.state().streakStartedAt()).isEqualTo(4100);
            assertThat(next.state().active()).isTrue();
        }
    }

    @Test
    void bridgeDoesNotOverrideUnknownOrVersionChangeAndDoesNotRetroactivelyCountDecisions() {
        State s = State.initial();
        for (int i = 0; i < 3; i++) s = step(s, Decision.ENTER, i * 1000).state();
        var times = List.of(2000L, 3100L, 4100L);
        var unknown = machine.advance(s, Decision.UNKNOWN, "p4100", 4100, "scope:track:policy:area-v1", Config.defaults(), times);
        assertThat(unknown.state().streakCount()).isZero();
        var revised = machine.advance(s, Decision.ENTER, "p4100", 4100, "scope:track:policy:area-v2", Config.defaults(), times);
        assertThat(revised.state().streakCount()).isEqualTo(1);
        var first = machine.advance(State.initial(), Decision.ENTER, "p4100", 4100, "scope:track:policy:area-v1", Config.defaults(), times);
        assertThat(first.state().streakCount()).isEqualTo(1);
        assertThat(first.state().active()).isFalse();
    }

    private PredictionStability.Transition step(State state, Decision decision, long at) {
        return machine.advance(state, decision, "p" + at, at, "scope:track:policy:area-v1", Config.defaults());
    }
}
