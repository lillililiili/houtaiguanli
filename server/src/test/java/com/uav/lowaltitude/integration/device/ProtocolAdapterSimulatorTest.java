package com.uav.lowaltitude.integration.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;
import com.uav.lowaltitude.integration.device.countermeasure.CountermeasureTcp4ChV20Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarTcpV300Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;

class ProtocolAdapterSimulatorTest {

    @ParameterizedTest
    @CsvSource({
            "SET_MASK,0,15,false", "SET_MASK,15,0,false", "SET_MASK,13,15,false",
            "CHANNEL_ON,1,8,false", "CHANNEL_OFF,1,15,false",
            "SET_MASK,0,0,true", "SET_MASK,15,31,true", "SET_MASK,13,13,true",
            "CHANNEL_ON,1,9,true", "CHANNEL_OFF,1,14,true"
    })
    void countermeasureReplyMustConfirmRequestedRelayState(String action,int requested,int actual,boolean expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] request = Countermeasure4ChCodec.decodeWire(readUntilNewline(socket),
                            Countermeasure4ChCodec.WireEncoding.ASCII_HEX_SPACED);
                    byte[] reply = {0x22, 1, request[2], 0, 0, 0, (byte) actual, 0};
                    reply[7] = Countermeasure4ChCodec.checksum(reply, 0, 7);
                    socket.getOutputStream().write(Countermeasure4ChCodec.encodeWire(reply,
                            Countermeasure4ChCodec.WireEncoding.ASCII_HEX_SPACED));
                    socket.getOutputStream().flush();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var adapter = new CountermeasureTcp4ChV20Adapter(new ObjectMapper(), allowedLoopbackPolicy());
            var result = adapter.setRelays(new DeviceAdapterPort.RelayWork("cmd", "device",
                    config(server.getLocalPort(), "{\"device_address\":1,\"wire_encoding\":\"ASCII_HEX_SPACED\"}"),
                    action, "SET_MASK".equals(action) ? null : requested, "SET_MASK".equals(action) ? requested : null));
            simulator.get(5, TimeUnit.SECONDS);
            assertThat(result.success()).isEqualTo(expected);
            if (!expected) {
                assertThat(result.resultCode()).isEqualTo("COUNTERMEASURE_STATE_MISMATCH");
                assertThat(result.detail()).contains("未确认", "0x" + Integer.toHexString(actual & 0x0F));
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 0x0401})
    void radarCommissionReadsBothInformationRegistersWithoutWriteCommands(int workMode) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var login = readRadar(socket);
                    assertThat(login.command()).isEqualTo(RadarV300Codec.COMMAND_LOGIN);
                    socket.getOutputStream().write(RadarV300Codec.encode(login.command(), login.frameId(), ByteBuffer.allocate(6).putInt(5).putShort((short) 0).array()));
                    var heartbeat = readRadar(socket);
                    assertThat(heartbeat.command()).isEqualTo(RadarV300Codec.COMMAND_HEARTBEAT);
                    socket.getOutputStream().write(RadarV300Codec.encode(heartbeat.command(), heartbeat.frameId(), heartbeat.payload()));
                    var query = readRadar(socket);
                    assertThat(query.command()).isEqualTo(RadarV300Codec.COMMAND_GET_REGISTER);
                    assertThat(query.payload()).containsExactly(0,0,0,2,0,0,4,0x40,0,0,4,1);
                    socket.getOutputStream().write(RadarV300Codec.encode(query.command(), query.frameId(),
                            ByteBuffer.allocate(20).putInt(2).putInt(0x440).putInt(0x07030201).putInt(0x401).putInt(workMode).array()));
                    socket.getOutputStream().flush();
                    // Leave the data stage without targets: register acquisition must still be preserved.
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var adapter = new RadarTcpV300Adapter(new ObjectMapper(), allowedLoopbackPolicy(), new EnvironmentCredentialResolver());
            var result = adapter.commission(new DeviceAdapterPort.CommissionWork("task", "T-1", "device", "D-1",
                    DeviceProtocolCodes.RADAR_TCP_V3_0_0, config(server.getLocalPort(), "{\"login_role\":\"DATA\"}")));
            assertThat(result.items()).anySatisfy(item -> {
                assertThat(item.code()).isEqualTo("WORK_MODE");
                assertThat(item.value()).contains("frequency_code", "scan_speed_deg_s", workMode == 0 ? "待机" : "360");
            });
            assertThat(result.success()).isFalse();
            assertThat(result.resultCode()).isEqualTo("RADAR_DATA_UNTESTABLE");
            assertThat(result.items()).anySatisfy(item -> {
                assertThat(item.code()).isEqualTo("DATA");
                assertThat(item.result()).isEqualTo("UNTESTABLE");
            });
            simulator.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void radarMonitorPreservesUploadCoalescedWithLoginResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var login = readRadar(socket);
                    byte[] response = RadarV300Codec.encode(login.command(), login.frameId(), ByteBuffer.allocate(6).putInt(5).putShort((short) 0).array());
                    byte[] data = RadarV300Codec.encode(RadarV300Codec.COMMAND_UPLOAD_TARGET_V3, 100, new byte[32]);
                    byte[] combined = Arrays.copyOf(response, response.length + data.length);
                    System.arraycopy(data, 0, combined, response.length, data.length);
                    socket.getOutputStream().write(combined);
                    assertThat(readRadar(socket).command()).isEqualTo(RadarV300Codec.COMMAND_GET_REGISTER);
                    // Drain optional polling requests if the upload arrives in a later socket read.
                    while (socket.getInputStream().read() != -1) { }
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var received = new java.util.concurrent.atomic.AtomicInteger();
            var adapter = new RadarTcpV300Adapter(new ObjectMapper(), allowedLoopbackPolicy(), new EnvironmentCredentialResolver());
            adapter.monitor(config(server.getLocalPort(), "{\"login_role\":\"DATA\"}"), () -> received.get() == 0,
                    () -> { }, new RadarTcpV300Adapter.LiveFrameListener() {
                        public void online() { }
                        public void invalidFrames(long count) { throw new AssertionError("unexpected invalid frame"); }
                        public void frame(RadarV300Codec.RadarFrame frame, byte[] raw, long now) {
                            assertThat(frame.command()).isEqualTo(RadarV300Codec.COMMAND_UPLOAD_TARGET_V3);
                            received.incrementAndGet();
                        }
                    });
            simulator.get(5, TimeUnit.SECONDS);
            assertThat(received.get()).isEqualTo(1);
        }
    }

    private static RadarV300Codec.RadarFrame readRadar(Socket socket) throws Exception {
        byte[] header = socket.getInputStream().readNBytes(8);
        int length = ByteBuffer.wrap(header, 4, 4).getInt();
        byte[] frame = Arrays.copyOf(header, 8 + length);
        byte[] rest = socket.getInputStream().readNBytes(length);
        System.arraycopy(rest, 0, frame, 8, length);
        return RadarV300Codec.decode(frame, false);
    }

    @ParameterizedTest
    @CsvSource({"196609,0,false,false", "196610,0,false,false", "196609,32,true,false", "196610,40,true,false", "196609,32,true,true"})
    void radarCommissionValidatesPayloadAndPreservesCoalescedBusinessFrames(int command, int payloadLength,
                                                                           boolean expected, boolean coalesced) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var login = readRadar(socket);
                    assertThat(login.command()).isEqualTo(RadarV300Codec.COMMAND_LOGIN);
                    socket.getOutputStream().write(RadarV300Codec.encode(login.command(), login.frameId(), ByteBuffer.allocate(6).putInt(5).putShort((short) 0).array()));
                    var heartbeat = readRadar(socket);
                    assertThat(heartbeat.command()).isEqualTo(RadarV300Codec.COMMAND_HEARTBEAT);
                    socket.getOutputStream().write(RadarV300Codec.encode(heartbeat.command(), heartbeat.frameId(), heartbeat.payload()));
                    var query = readRadar(socket);
                    assertThat(query.command()).isEqualTo(RadarV300Codec.COMMAND_GET_REGISTER);
                    byte[] registers = RadarV300Codec.encode(query.command(), query.frameId(),
                            ByteBuffer.allocate(20).putInt(2).putInt(0x440).putInt(0).putInt(0x401).putInt(0).array());
                    byte[] data = RadarV300Codec.encode(command, 100, new byte[payloadLength]);
                    if (coalesced) {
                        byte[] combined = Arrays.copyOf(registers, registers.length + data.length);
                        System.arraycopy(data, 0, combined, registers.length, data.length);
                        socket.getOutputStream().write(combined);
                    } else {
                        socket.getOutputStream().write(registers);
                        socket.getOutputStream().flush();
                        Thread.sleep(150);
                        socket.getOutputStream().write(data);
                    }
                    socket.getOutputStream().flush();
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var adapter = new RadarTcpV300Adapter(new ObjectMapper(), allowedLoopbackPolicy(), new EnvironmentCredentialResolver());
            var result = adapter.commission(new DeviceAdapterPort.CommissionWork("task", "T-1", "device", "D-1",
                    DeviceProtocolCodes.RADAR_TCP_V3_0_0, config(server.getLocalPort(), "{\"login_role\":\"DATA\"}")));
            simulator.get(5, TimeUnit.SECONDS);
            assertThat(result.success()).isEqualTo(expected);
            if (!expected) assertThat(result.resultCode()).isEqualTo("PROTOCOL_FRAME_INVALID");
        }
    }

    @Test
    void countermeasureAutoProbeUsesOnlySafeQueryAndDetectsAsciiSpaced() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<byte[]> requests = java.util.Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try (Socket first = server.accept()) {
                    byte[] request = readUntilNewline(first);
                    requests.add(request);
                    byte[] response = "22 01 10 00 00 00 0D 40\r\n".getBytes(StandardCharsets.US_ASCII);
                    first.getOutputStream().write(response, 0, 7);
                    first.getOutputStream().flush();
                    first.getOutputStream().write(response, 7, response.length - 7);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            NetworkTargetPolicy policy = allowedLoopbackPolicy();
            CountermeasureTcp4ChV20Adapter adapter = new CountermeasureTcp4ChV20Adapter(new ObjectMapper(), policy);
            CountermeasureTcp4ChV20Adapter.ProbeResult result = adapter.query(config(server.getLocalPort(),
                    "{\"device_address\":1,\"wire_encoding\":\"AUTO\"}"));
            assertThat(result.encoding()).isEqualTo(Countermeasure4ChCodec.WireEncoding.ASCII_HEX_SPACED);
            assertThat(result.state().channels()).containsEntry("900M", true).containsEntry("1.5G", false)
                    .containsEntry("2.4G", true).containsEntry("5.8G", true);
            simulator.get(5, TimeUnit.SECONDS);
            assertThat(new String(requests.get(0), StandardCharsets.US_ASCII)).contains("55 01 10 00 00 00 01 67");
            assertThat(requests).allSatisfy(bytes -> assertThat(Arrays.toString(bytes))
                    .doesNotContain("17", "18", "19"));
        }
    }

    @Test
    void countermeasureSetMaskSendsDocumentedFrameAndParsesReply() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<byte[]> requests = java.util.Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Void> simulator = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = server.accept()) {
                            byte[] request = readUntilNewline(socket);
                            requests.add(request);
                            boolean query = new String(request, StandardCharsets.US_ASCII).contains(" 10 ");
                            String reply = query ? "22 01 10 00 00 00 00 33\r\n" : "22 01 13 00 00 00 0F 45\r\n";
                            socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            CountermeasureTcp4ChV20Adapter adapter = new CountermeasureTcp4ChV20Adapter(new ObjectMapper(),
                    allowedLoopbackPolicy());
            DeviceAdapterPort.AdapterResult result = adapter.setRelays(new DeviceAdapterPort.RelayWork(
                    "cmd", "device", config(server.getLocalPort(),
                    "{\"device_address\":1,\"wire_encoding\":\"AUTO\"}"),
                    "SET_MASK", null, 0x0F));
            assertThat(result.success()).isTrue();
            assertThat(result.resultCode()).isEqualTo("COUNTERMEASURE_SET_OK");
            simulator.get(5, TimeUnit.SECONDS);
            assertThat(new String(requests.get(0), StandardCharsets.US_ASCII)).contains("55 01 10 00 00 00 01 67");
            assertThat(new String(requests.get(1), StandardCharsets.US_ASCII)).contains("55 01 13 00 00 00 0F 78");
        }
    }

    @Test
    void countermeasureSetTimesOutWhenDeviceDoesNotReply() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> acceptor = CompletableFuture.runAsync(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(1500);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            CountermeasureTcp4ChV20Adapter adapter = new CountermeasureTcp4ChV20Adapter(new ObjectMapper(),
                    allowedLoopbackPolicy());
            DeviceAdapterPort.AdapterResult result = adapter.setRelays(new DeviceAdapterPort.RelayWork(
                    "cmd", "device", "{\"allowed_cidrs\":\"127.0.0.1/32\",\"connection\":{\"host\":\"127.0.0.1\",\"port\":"
                            + server.getLocalPort() + ",\"timeout_millis\":500},"
                            + "\"protocol_configuration\":{\"device_address\":1,\"wire_encoding\":\"ASCII_HEX_SPACED\"}}",
                    "SET_MASK", null, 0x00));
            assertThat(result.success()).isFalse();
            assertThat(result.resultCode()).isIn("ADAPTER_TIMEOUT", "ADAPTER_UNAVAILABLE", "PROTOCOL_FRAME_INVALID");
            assertThat(result.detail()).contains("本次设置结果未确认", "可能已动作");
            acceptor.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void countermeasureConnectOnlyOpensTcpWithoutSendingQuery() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Integer> firstByte = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    return socket.getInputStream().read();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            CountermeasureTcp4ChV20Adapter adapter = new CountermeasureTcp4ChV20Adapter(new ObjectMapper(),
                    allowedLoopbackPolicy());
            DeviceAdapterPort.AdapterResult result = adapter.connect(new DeviceAdapterPort.CommissionWork(
                    "task", "T-1", "device", "D-1", DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0,
                    config(server.getLocalPort(), "{\"device_address\":1,\"wire_encoding\":\"AUTO\"}")));
            assertThat(result.success()).isTrue();
            assertThat(result.resultCode()).isEqualTo("COUNTERMEASURE_TCP_OK");
            assertThat(firstByte.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }

    @Test
    void radarLoginHandlesSplitResponseAndNeverUsesDebugCrc() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<RadarV300Codec.RadarFrame> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    byte[] header = socket.getInputStream().readNBytes(8);
                    int body = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    byte[] remainder = socket.getInputStream().readNBytes(body);
                    byte[] request = new byte[8 + body];
                    System.arraycopy(header, 0, request, 0, 8);
                    System.arraycopy(remainder, 0, request, 8, body);
                    RadarV300Codec.RadarFrame login = RadarV300Codec.decode(request, false);
                    byte[] replyPayload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
                            .putInt(5).putShort((short) 0).array();
                    byte[] reply = RadarV300Codec.encode(RadarV300Codec.COMMAND_LOGIN, login.frameId(), replyPayload);
                    socket.getOutputStream().write(reply, 0, 5);
                    socket.getOutputStream().flush();
                    socket.getOutputStream().write(reply, 5, reply.length - 5);
                    return login;
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            RadarTcpV300Adapter adapter = new RadarTcpV300Adapter(new ObjectMapper(), allowedLoopbackPolicy(),
                    new EnvironmentCredentialResolver());
            adapter.commission(new DeviceAdapterPort.CommissionWork(
                    "task", "T-1", "device", "D-1", DeviceProtocolCodes.RADAR_TCP_V3_0_0,
                    config(server.getLocalPort(), "{\"login_role\":\"DATA\"}")));
            RadarV300Codec.RadarFrame login = received.get(5, TimeUnit.SECONDS);
            assertThat(login.command()).isEqualTo(RadarV300Codec.COMMAND_LOGIN);
            assertThat(login.payload()).hasSize(8);
            ByteBuffer loginPayload = ByteBuffer.wrap(login.payload()).order(ByteOrder.BIG_ENDIAN);
            assertThat(loginPayload.getInt()).isEqualTo(5);
            assertThat(loginPayload.getInt()).isEqualTo(0);
            assertThat(login.debugCrc()).isFalse();
        }
    }

    @Test
    void radarConnectOnlyOpensTcpWithoutLogin() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Integer> firstByte = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    return socket.getInputStream().read();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            RadarTcpV300Adapter adapter = new RadarTcpV300Adapter(new ObjectMapper(), allowedLoopbackPolicy(),
                    new EnvironmentCredentialResolver());
            DeviceAdapterPort.AdapterResult result = adapter.connect(new DeviceAdapterPort.CommissionWork(
                    "task", "T-1", "device", "D-1", DeviceProtocolCodes.RADAR_TCP_V3_0_0,
                    config(server.getLocalPort(), "{\"login_role\":\"DATA\"}")));
            assertThat(result.success()).isTrue();
            assertThat(result.resultCode()).isEqualTo("RADAR_TCP_OK");
            assertThat(firstByte.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }

    private static NetworkTargetPolicy allowedLoopbackPolicy() throws Exception {
        NetworkTargetPolicy policy = mock(NetworkTargetPolicy.class);
        when(policy.resolveAllowed("127.0.0.1", "127.0.0.1/32")).thenReturn(List.of(InetAddress.getLoopbackAddress()));
        return policy;
    }

    private static String config(int port, String protocol) {
        return "{\"allowed_cidrs\":\"127.0.0.1/32\",\"connection\":{\"host\":\"127.0.0.1\",\"port\":"
                + port + ",\"timeout_millis\":1000},\"protocol_configuration\":" + protocol + "}";
    }

    private static byte[] readUntilNewline(Socket socket) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = socket.getInputStream().read()) >= 0) {
            out.write(value);
            if (value == '\n') break;
        }
        return out.toByteArray();
    }
}
