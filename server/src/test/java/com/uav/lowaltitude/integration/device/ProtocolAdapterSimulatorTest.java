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

import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;
import com.uav.lowaltitude.integration.device.countermeasure.CountermeasureTcp4ChV20Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarTcpV300Adapter;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;

class ProtocolAdapterSimulatorTest {

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
