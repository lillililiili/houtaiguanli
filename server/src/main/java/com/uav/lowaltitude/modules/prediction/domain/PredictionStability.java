package com.uav.lowaltitude.modules.prediction.domain;

import java.util.Objects;
import java.util.List;

/** Distinct new measurements drive stability; polling, repeated anchors and stale inputs never do. */
public final class PredictionStability {
    public enum Decision { ENTER, CLEAR, UNKNOWN }
    public enum Status { WATCHING, ACTIVE, CLEARED, UNKNOWN }
    public record Config(int enterCount, long enterSpanMillis, int clearCount, long clearSpanMillis, long maximumGapMillis) {
        public Config {
            if (enterCount < 1 || clearCount < 1 || enterSpanMillis < 0 || clearSpanMillis < 0 || maximumGapMillis < 1)
                throw new IllegalArgumentException("Invalid stability configuration");
        }
        public static Config defaults() { return new Config(3, 2_000, 5, 5_000, 2_000); }
    }
    public record State(Status status, boolean active, Decision streakDecision, int streakCount, Long streakStartedAt,
            String lastAnchorId, Long lastObservedAt, String continuityKey) {
        public static State initial() { return new State(Status.WATCHING, false, Decision.UNKNOWN, 0, null, null, null, null); }
    }
    public record Transition(State state, boolean activated, boolean cleared, boolean ignored) { }

    public Transition advance(State previous, Decision decision, String anchorId, long observedAt, String continuityKey, Config config) {
        return advance(previous, decision, anchorId, observedAt, continuityKey, config, List.of());
    }

    /** Intermediate verified MEAS may bridge worker sampling gaps, but never add decision counts. */
    public Transition advance(State previous, Decision decision, String anchorId, long observedAt, String continuityKey,
            Config config, List<Long> validatedMeasurementTimes) {
        State prior = previous == null ? State.initial() : previous;
        if (decision == null || anchorId == null || anchorId.isBlank() || continuityKey == null || continuityKey.isBlank())
            throw new IllegalArgumentException("Prediction decision and measurement identity are required");
        boolean continuityChanged = !Objects.equals(prior.continuityKey(), continuityKey);
        // A policy/airspace revision invalidates the streak even when it reuses the anchor.
        // It never converts the repeated anchor into additional positive/safe evidence.
        if (prior.lastObservedAt() != null && (observedAt <= prior.lastObservedAt() || anchorId.equals(prior.lastAnchorId()))) {
            // A moving horizon can change the assessment without new observation. That may
            // invalidate an old streak, but it cannot create a new positive or safe sample.
            boolean reassessed = observedAt == prior.lastObservedAt() && decision != prior.streakDecision();
            State kept = continuityChanged || reassessed ? reset(prior, continuityKey) : prior;
            return new Transition(kept, false, false, true);
        }
        boolean gap = prior.lastObservedAt() != null && observedAt - prior.lastObservedAt() > config.maximumGapMillis()
                && !measuredContinuity(prior.lastObservedAt(), observedAt, validatedMeasurementTimes, config.maximumGapMillis());
        if (decision == Decision.UNKNOWN) {
            State state = new State(Status.UNKNOWN, prior.active(), Decision.UNKNOWN, 0, null, anchorId, observedAt, continuityKey);
            return new Transition(state, false, false, false);
        }
        boolean continueStreak = !continuityChanged && !gap && decision == prior.streakDecision() && prior.streakStartedAt() != null;
        int count = continueStreak ? prior.streakCount() + 1 : 1;
        long started = continueStreak ? prior.streakStartedAt() : observedAt;
        boolean enter = decision == Decision.ENTER && count >= config.enterCount() && observedAt - started >= config.enterSpanMillis();
        boolean clear = decision == Decision.CLEAR && count >= config.clearCount() && observedAt - started >= config.clearSpanMillis();
        boolean activated = enter && !prior.active(), cleared = clear && prior.active();
        boolean active = enter || (prior.active() && !clear);
        Status status = active ? Status.ACTIVE : clear ? Status.CLEARED : Status.WATCHING;
        return new Transition(new State(status, active, decision, count, started, anchorId, observedAt, continuityKey), activated, cleared, false);
    }

    private static boolean measuredContinuity(long previousAt, long currentAt, List<Long> times, long maximumGap) {
        if (times == null || !times.contains(previousAt) || !times.contains(currentAt)) return false;
        long prior = previousAt;
        for (long at : times.stream().filter(Objects::nonNull).filter(t -> t > previousAt && t <= currentAt).distinct().sorted().toList()) {
            if (at - prior > maximumGap) return false;
            prior = at;
        }
        return prior == currentAt;
    }

    /** No fresh observation exists: retain the open warning, invalidate any pending safe streak. */
    public State unavailable(State previous) {
        State prior = previous == null ? State.initial() : previous;
        return reset(prior, prior.continuityKey());
    }

    private static State reset(State prior, String continuityKey) {
        return new State(Status.UNKNOWN, prior.active(), Decision.UNKNOWN, 0, null, prior.lastAnchorId(), prior.lastObservedAt(), continuityKey);
    }
}
