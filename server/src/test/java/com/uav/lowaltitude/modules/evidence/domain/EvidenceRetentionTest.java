package com.uav.lowaltitude.modules.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class EvidenceRetentionTest {
    @Test
    void retainUntilFollowsKindFromCapturedAt() {
        Instant captured = Instant.parse("2020-01-15T08:00:00Z");
        assertThat(EvidenceRetention.until("COMMISSION_REPORT", captured, Instant.parse("2020-02-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2020-04-14T08:00:00Z"));
        assertThat(EvidenceRetention.until("SCENE_PHOTO", captured, null))
                .isEqualTo(Instant.parse("2021-01-15T08:00:00Z"));
        assertThat(EvidenceRetention.until("EO_STILL", captured, null))
                .isEqualTo(Instant.parse("2023-01-15T08:00:00Z"));
        assertThat(EvidenceRetention.until("EO_VIDEO", captured, null))
                .isEqualTo(Instant.parse("2023-01-15T08:00:00Z"));
        assertThat(EvidenceRetention.until("TRACK_SNAPSHOT", captured, null))
                .isEqualTo(Instant.parse("2025-01-15T08:00:00Z"));
        assertThat(EvidenceRetention.until("PENALTY_DOCUMENT", captured, null))
                .isEqualTo(Instant.parse("2025-01-15T08:00:00Z"));
    }

    @Test
    void fallsBackToStoredAtAndLeapDayPlusOneYear() {
        Instant stored = Instant.parse("2020-02-29T12:00:00Z");
        assertThat(EvidenceRetention.until("SCENE_PHOTO", null, stored))
                .isEqualTo(stored.atOffset(ZoneOffset.UTC).plusYears(1).toInstant());
        assertThat(EvidenceRetention.until("SCENE_PHOTO", null, stored))
                .isEqualTo(Instant.parse("2021-02-28T12:00:00Z"));
        assertThat(EvidenceRetention.until("EO_STILL", null, null)).isNull();
        assertThat(EvidenceRetention.effectiveUntil(Instant.parse("2030-01-01T00:00:00Z"), "EO_STILL",
                Instant.parse("2020-01-01T00:00:00Z"), null)).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void custodyHoldWinsAndNearingWindowIsThirtyDays() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        assertThat(EvidenceRetention.custody(now.minusSeconds(1), now, false)).isEqualTo("DUE");
        assertThat(EvidenceRetention.custody(now, now, false)).isEqualTo("DUE");
        assertThat(EvidenceRetention.custody(now.plusSeconds(1), now, false)).isEqualTo("NEARING");
        assertThat(EvidenceRetention.custody(now.plus(java.time.Duration.ofDays(30)), now, false)).isEqualTo("NEARING");
        assertThat(EvidenceRetention.custody(now.plus(java.time.Duration.ofDays(30)).plusMillis(1), now, false))
                .isEqualTo("KEPT");
        assertThat(EvidenceRetention.custody(now.minusSeconds(1), now, true)).isEqualTo("HELD");
        assertThat(EvidenceRetention.policy("COMMISSION_REPORT").label()).isEqualTo("90 天");
        assertThat(EvidenceRetention.policy("EO_VIDEO").label()).isEqualTo("3 年");
    }
}
