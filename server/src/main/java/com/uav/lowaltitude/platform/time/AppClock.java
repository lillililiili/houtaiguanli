package com.uav.lowaltitude.platform.time;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class AppClock {

    private final Clock clock;

    public AppClock() {
        this(Clock.systemUTC());
    }

    public AppClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public long nowMillis() {
        return now().toEpochMilli();
    }

    /** Fixed/offset application clocks must keep their explicit instant in database reads. */
    public boolean usesSystemClock() {
        return clock.equals(Clock.systemUTC());
    }
}
