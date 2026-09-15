package com.uav.lowaltitude.integration.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder;

class RadarInformationTest {
    @Test void readsBothDocumentedRegistersAndDecodesTheirFields() {
        assertThat(RadarV300Codec.getInformationPayload()).containsExactly(
                0,0,0,2, 0,0,4,0x40, 0,0,4,1);
        var decoded = RadarV300PayloadDecoder.registers(ByteBuffer.allocate(20)
                .putInt(2).putInt(0x440).putInt(0x07030201).putInt(0x401).putInt(0x0401).array());
        assertThat(decoded.get("frequency_code")).isEqualTo(7);
        assertThat(decoded.get("speed_threshold_mps").toString()).isEqualTo("1.00");
        assertThat(decoded.get("detection_threshold")).isEqualTo("高门限");
        assertThat(decoded.get("rcs_threshold_m2").toString()).isEqualTo("0.01");
        assertThat(decoded.get("scan_speed_deg_s")).isEqualTo(360);
        assertThat(decoded.get("work_mode")).isEqualTo("周扫");
        assertThat(decoded).doesNotContainKey("rotation_rpm");
    }
    @Test void preservesUnknownCodesAndRejectsMalformedOrDuplicateRegisters() {
        var decoded = RadarV300PayloadDecoder.registers(ByteBuffer.allocate(12)
                .putInt(1).putInt(0x401).putInt(0x0309).array());
        assertThat(decoded.get("work_mode_code")).isEqualTo(9);
        assertThat(decoded).doesNotContainKeys("work_mode", "scan_speed_deg_s");
        assertThatThrownBy(() -> RadarV300PayloadDecoder.registers(new byte[3])).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> RadarV300PayloadDecoder.registers(ByteBuffer.allocate(20)
                .putInt(2).putInt(0x401).putInt(1).putInt(0x401).putInt(2).array())).isInstanceOf(ProtocolException.class);
    }
}
