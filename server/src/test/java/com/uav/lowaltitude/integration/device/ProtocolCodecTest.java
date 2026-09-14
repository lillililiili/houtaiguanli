package com.uav.lowaltitude.integration.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder;

class ProtocolCodecTest {

    @Test
    void radarGoldenHeartbeatCrcAndFragmentedStickyFrames() {
        assertThat(RadarV300Codec.crc16Modbus("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 9))
                .isEqualTo(0x4B37);
        byte[] heartbeat = RadarV300Codec.encode(RadarV300Codec.COMMAND_HEARTBEAT, 1,
                RadarV300Codec.heartbeatPayload());
        assertThat(HexFormat.of().withUpperCase().formatHex(heartbeat))
                .isEqualTo("55AA55AA0000001200000001000000010000000000000000E1B1");

        byte[] second = RadarV300Codec.encode(RadarV300Codec.COMMAND_LOGIN, 0xFFFFFFFFL, new byte[] { 0, 0 });
        byte[] combined = new byte[3 + heartbeat.length + second.length];
        combined[0] = 9; combined[1] = 8; combined[2] = 7;
        System.arraycopy(heartbeat, 0, combined, 3, heartbeat.length);
        System.arraycopy(second, 0, combined, 3 + heartbeat.length, second.length);
        RadarV300Codec.StreamDecoder decoder = new RadarV300Codec.StreamDecoder(1024, false);
        assertThat(decoder.feed(java.util.Arrays.copyOfRange(combined, 0, 11))).isEmpty();
        List<RadarV300Codec.RadarFrame> frames = decoder.feed(java.util.Arrays.copyOfRange(combined, 11, combined.length));
        assertThat(frames).hasSize(2);
        assertThat(frames.get(1).frameId()).isEqualTo(0xFFFFFFFFL);
    }

    @Test
    void radarRejectsDebugCrcInProductionAndDecodesSignedUnitsAndUnsignedTrackId() {
        byte[] frame = RadarV300Codec.encode(RadarV300Codec.COMMAND_HEARTBEAT, 1, new byte[0]);
        frame[frame.length - 2] = (byte) 0xCC; frame[frame.length - 1] = (byte) 0xCC;
        assertThatThrownBy(() -> RadarV300Codec.decode(frame, false)).isInstanceOf(ProtocolException.class);
        assertThat(RadarV300Codec.decode(frame, true).debugCrc()).isTrue();

        ByteBuffer payload = ByteBuffer.allocate(40 + 64).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(123456789L).putLong(-1L).putLong(1_700_000_000_000L)
                .putInt(-12500).putInt(252500).put((byte) 0).put((byte) 0)
                .put((byte) 1).put((byte) 2).putInt(1);
        payload.putInt(-1234).putInt(5678).putInt(-90)
                .putInt(-101).putInt(202).putInt(-303).putInt(-1)
                .putShort((short) -250).putShort((short) 123)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .put((byte) 3).put((byte) 0).putLong(0).putInt(0).putInt(0)
                .putInt(1234567).putInt(1);
        RadarV300PayloadDecoder.TrackBatch batch = RadarV300PayloadDecoder.track(payload.array());
        assertThat(batch.payloadFrameId()).isEqualTo("18446744073709551615");
        assertThat(batch.items().get(0).externalTrackId()).isEqualTo("4294967295");
        assertThat(batch.items().get(0).xM()).isEqualByComparingTo("-12.34");
        assertThat(batch.items().get(0).snrDb()).isEqualByComparingTo("-2.50");
        assertThat(batch.items().get(0).categoryCode()).isEqualTo("UAV");
        assertThat(batch.items().get(0).highResolutionRcsM2()).isEqualByComparingTo("1.234567");
    }

    @Test
    void radarLoginPayloadIsEightBytesAndStatusSkipsPermissionType() {
        byte[] payload = RadarV300Codec.loginDataPayload(0);
        assertThat(HexFormat.of().withUpperCase().formatHex(payload)).isEqualTo("0000000500000000");
        byte[] frame = RadarV300Codec.encode(RadarV300Codec.COMMAND_LOGIN, 1, payload);
        assertThat(ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt()).isEqualTo(0x12);
        assertThat(RadarV300Codec.loginStatus(HexFormat.of().parseHex("000000030000"))).isEqualTo(0);
        assertThat(RadarV300Codec.loginStatus(HexFormat.of().parseHex("000000050001"))).isEqualTo(1);
        assertThatThrownBy(() -> RadarV300Codec.loginStatus(new byte[] { 0, 0 }))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> RadarV300Codec.loginDataPayload(1L << 32))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void countermeasureGoldenQueryMapsOnlyLowFourChannelsAndForbidsBroadcast() {
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.query(1)))
                .isEqualTo("5501100000000167");
        byte[] response = HexFormat.of().parseHex("2201101234567F4E");
        Countermeasure4ChCodec.RelayState state = Countermeasure4ChCodec.parseResponse(response, 1);
        assertThat(state.rawStatusWord()).isEqualTo(0x1234567FL);
        assertThat(state.channels()).containsEntry("900M", true).containsEntry("1.5G", true)
                .containsEntry("2.4G", true).containsEntry("5.8G", true);
        assertThat(Countermeasure4ChCodec.MASK_FORCE_LAND).isEqualTo(0x0F);
        assertThat(Countermeasure4ChCodec.MASK_DRIVE_AWAY).isEqualTo(0x0D);
        assertThatThrownBy(() -> Countermeasure4ChCodec.query(245)).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Countermeasure4ChCodec.channelOn(245, 1)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void countermeasureGoldenSetFramesAndParsesMatchingFunctionCodes() {
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.channelOn(1, 0x01)))
                .isEqualTo("5501120000000169");
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.channelOff(1, 0x01)))
                .isEqualTo("5501110000000168");
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.setMask(1, 0x0F)))
                .isEqualTo("5501130000000F78");
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.setMask(1, 0x00)))
                .isEqualTo("5501130000000069");
        assertThat(HexFormat.of().withUpperCase().formatHex(Countermeasure4ChCodec.setMask(1, 0x0D)))
                .isEqualTo("5501130000000D76");
        Countermeasure4ChCodec.RelayState on = Countermeasure4ChCodec.parseResponse(
                HexFormat.of().parseHex("2201120000000136"), 1, Countermeasure4ChCodec.FUNCTION_ON);
        assertThat(on.channels()).containsEntry("900M", true).containsEntry("1.5G", false);
        Countermeasure4ChCodec.RelayState off = Countermeasure4ChCodec.parseResponse(
                HexFormat.of().parseHex("2201110000000034"), 1, Countermeasure4ChCodec.FUNCTION_OFF);
        assertThat(off.channels()).containsEntry("900M", false);
        assertThatThrownBy(() -> Countermeasure4ChCodec.parseResponse(
                HexFormat.of().parseHex("2201120000000136"), 1))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Countermeasure4ChCodec.setMask(1, 0x03))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> Countermeasure4ChCodec.channelOn(1, 0x03))
                .isInstanceOf(ProtocolException.class);
    }
}
