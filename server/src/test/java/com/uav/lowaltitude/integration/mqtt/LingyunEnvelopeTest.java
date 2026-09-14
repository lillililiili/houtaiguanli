package com.uav.lowaltitude.integration.mqtt;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LingyunEnvelopeTest {
    @ParameterizedTest
    @ValueSource(strings={"radar","5ga","tdoa","aoa","dcd","rid"})
    void parsesEnvelopeWithoutInventingOrValidatingObjectCoordinates(String type) {
        String raw="{\"deviceId\":\"external-1\",\"ptTime\":1000,\"msgCnt\":2147483647,\"objects\":[{\"longitude\":999}]}";
        var decoded=LingyunEnvelope.decode("bridge/provider/device_data/"+type+"/external-1",raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.json()).isEqualTo(raw);
        assertThat(decoded.msgCnt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(decoded.hash()).hasSize(64);
        assertThat(decoded.hash()).isNotEqualTo(LingyunEnvelope.hash((raw+" ").getBytes(StandardCharsets.UTF_8)));
    }
    @ParameterizedTest
    @ValueSource(strings={"{}","[]","null","{bad}","{\"deviceId\":\"x\"}",
            "{\"deviceId\":\"external-1\",\"ptTime\":1,\"msgCnt\":-1,\"objects\":[]}",
            "{\"deviceId\":\"external-1\",\"ptTime\":1.5,\"msgCnt\":1,\"objects\":[]}",
            "{\"deviceId\":\"external-1\",\"ptTime\":1,\"msgCnt\":2147483648,\"objects\":[]}",
            "{\"deviceId\":\"external-1\",\"ptTime\":1,\"msgCnt\":1,\"objects\":[]}{}",
            "{\"deviceId\":\"external-1\",\"deviceId\":\"external-1\",\"ptTime\":1,\"msgCnt\":1,\"objects\":[]}"})
    void rejectsMalformedEnvelopes(String raw) {
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device_data/radar/external-1",raw.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(LingyunEnvelope.Rejected.class);
    }
    @Test void rejectsInvalidUtf8AndUnsupportedTopic() {
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device_data/radar/x",new byte[]{(byte)0xff})).hasMessage("INVALID_JSON_UTF8");
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device_data/eo/x",new byte[0])).hasMessage("UNSUPPORTED_TYPE");
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device_data/dec/x",new byte[0])).hasMessage("UNSUPPORTED_TYPE");
    }

    @ParameterizedTest
    @ValueSource(strings={"aoa","dcd","rid","dec","ifr","bsc"})
    void workParamIdentityUsesAppendixDeviceType(String type) {
        int code = LingyunEnvelope.TYPES.get(type);
        String raw = "{\"providerCode\":\"provider\",\"deviceId\":\"external-1\",\"deviceName\":\"fixture\",\"deviceType\":"
                + code + ",\"workState\":1}";
        var decoded = LingyunEnvelope.decode("bridge/provider/device/" + type + "/external-1",
                raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.sensing()).isFalse();
        assertThat(decoded.workState()).isEqualTo(1);
        String wrong = raw.replace("\"deviceType\":" + code, "\"deviceType\":1");
        if (code != 1) {
            assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device/" + type + "/external-1",
                    wrong.getBytes(StandardCharsets.UTF_8))).hasMessage("IDENTITY_MISMATCH");
        }
    }
}
