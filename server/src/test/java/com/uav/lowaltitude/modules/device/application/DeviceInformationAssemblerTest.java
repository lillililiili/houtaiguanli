package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DeviceInformationAssemblerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceInformationAssembler assembler = new DeviceInformationAssembler(mapper);
    private DeviceInformationAssembler.Section section(String json, Long observed, Long received, String... groups) throws Exception {
        return assembler.section("test", "测试", "协议", "PROTOCOL", mapper.readTree(json), observed, received, 100_000, groups);
    }
    @Test void preservesZeroAndFalseButDistinguishesMissingInvalidAndStale() throws Exception {
        var fields = section("{\"raw_status_word\":0,\"channels\":{\"900M\":false,\"1.5G\":\"false\"}}", null, 100_000L, "relay").fields();
        assertThat(fields.get(0).status()).isEqualTo("RECEIVED");
        assertThat(fields.get(2).value().booleanValue()).isFalse();
        assertThat(fields.get(2).status()).isEqualTo("RECEIVED");
        assertThat(fields.get(3).status()).isEqualTo("INVALID");
        assertThat(fields.get(4).status()).isEqualTo("NOT_REPORTED");
        assertThat(section("{\"raw_status_word\":0}", null, 69_999L, "relay").fields().get(0).status()).isEqualTo("STALE");
        assertThat(section("{\"raw_status_word\":0}", 1L, 100_000L, "relay").fields().get(0).status()).isEqualTo("STALE");
    }
    @Test void interpretsEoWorkStateTwoAsAutonomousAndUnknownEnumAsInvalid() throws Exception {
        var fields = section("{\"metadata\":{\"workState\":2}}", null, 100_000L, "eo_heartbeat").fields();
        assertThat(fields.get(7).note()).contains("自主探测");
        assertThat(fields.get(7).status()).isEqualTo("RECEIVED");
        assertThat(section("{\"metadata\":{\"workState\":9}}", null, 100_000L, "eo_heartbeat").fields().get(7).status()).isEqualTo("INVALID");
        var idle = section("{\"metadata\":{\"workState\":0,\"message\":\"\",\"taskId\":null}}", null, 100_000L, "eo_heartbeat").fields();
        assertThat(idle.get(5).status()).isEqualTo("RECEIVED");
        assertThat(idle.get(6).status()).isEqualTo("NOT_APPLICABLE");
    }
    @Test void requiresDirectionalCoverageAndFrequencyAlternative() throws Exception {
        assertThat(section("{\"extension\":{\"activeAntennaType\":0}}", null, null, "counter").fields()).allMatch(DeviceInformationAssembler.Field::required);
        assertThat(section("{\"extension\":{\"activeAntennaType\":1}}", null, null, "counter").fields().get(1).required()).isFalse();
        assertThat(section("{}", null, null, "frequency").fields().get(0).required()).isTrue();
        assertThat(section("{\"extension\":{\"detectionFrequency\":[\"2.4G\"]}}", null, null, "frequency").fields().get(0).required()).isFalse();
    }
    @Test void redactsConnectionAndNeverSerializesUnlistedCredentials() throws Exception {
        var values = mapper.readTree("{\"host\":\"private-host\",\"password\":\"DO-NOT-EXPOSE\",\"credential_ref\":\"DO-NOT-EXPOSE\"}");
        var redacted = assembler.section("connection", "配置", "平台", "REDACTED", values, null, null, 100_000, "connection");
        assertThat(redacted.fields()).allMatch(f -> f.status().equals("REDACTED") && f.value().isNull());
        assertThat(mapper.writeValueAsString(redacted)).doesNotContain("private-host", "DO-NOT-EXPOSE");
        assertThat(mapper.writeValueAsString(assembler.section("c", "c", "c", "CATALOG", values, null, null, 100_000, "connection"))).doesNotContain("DO-NOT-EXPOSE");
    }
}
