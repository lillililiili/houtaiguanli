package com.uav.lowaltitude.modules.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.evidence.domain.EvidenceChainChecksum.Member;

class EvidenceChainChecksumTest {
    @Test
    void emptyChainHashesEmptyStringAndOrderDoesNotMatter() {
        String empty = EvidenceChainChecksum.digest(List.of());
        assertThat(empty).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        Member a = new Member("ALARM", "a1", EvidenceChainChecksum.fingerprintAlarm(1L, "HIGH"));
        Member b = new Member("TRACK", "t1", EvidenceChainChecksum.fingerprintTrack("FUSED", null, 3));
        assertThat(EvidenceChainChecksum.digest(List.of(a, b))).isEqualTo(EvidenceChainChecksum.digest(List.of(b, a)));
    }

    @Test
    void fileKindMapsToRecordType() {
        assertThat(EvidenceChainChecksum.fileRecordType("EO_VIDEO")).isEqualTo("VIDEO");
        assertThat(EvidenceChainChecksum.fileRecordType("TRACK_SNAPSHOT")).isEqualTo("IMAGE");
        assertThat(EvidenceChainChecksum.fileRecordType("COMMAND_LOG")).isEqualTo("AUTHORIZATION");
        assertThat(EvidenceChainChecksum.fileRecordType("NOTICE_RECEIPT")).isEqualTo("DISPOSAL");
        assertThat(EvidenceChainChecksum.fileRecordType("COMMISSION_REPORT")).isEqualTo("OPERATION");
    }
}
