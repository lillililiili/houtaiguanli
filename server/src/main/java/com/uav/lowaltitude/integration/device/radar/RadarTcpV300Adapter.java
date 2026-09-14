package com.uav.lowaltitude.integration.device.radar;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.AdapterConfiguration;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.integration.device.NetworkTargetPolicy;
import com.uav.lowaltitude.integration.device.ProtocolException;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec.RadarFrame;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec.StreamDecoder;

@Component
public class RadarTcpV300Adapter implements DeviceAdapterPort {

    private final ObjectMapper mapper;
    private final NetworkTargetPolicy networkPolicy;
    private final EnvironmentCredentialResolver credentials;
    private final AtomicLong frameSequence = new AtomicLong(1);

    public RadarTcpV300Adapter(ObjectMapper mapper, NetworkTargetPolicy networkPolicy,
                              EnvironmentCredentialResolver credentials) {
        this.mapper = mapper;
        this.networkPolicy = networkPolicy;
        this.credentials = credentials;
    }

    @Override public SourceMode mode() { return SourceMode.live; }
    @Override public String protocolCode() { return DeviceProtocolCodes.RADAR_TCP_V3_0_0; }

    @Override
    public AdapterResult reboot(RebootWork work) {
        return new AdapterResult(false, "DEVICE_NOT_OPERABLE", "雷达 v3.0.0 接入未开放重启或启停控制");
    }

    @Override
    public AdapterResult connect(CommissionWork work) {
        try {
            AdapterConfiguration configuration = AdapterConfiguration.parse(mapper, work.configurationJson());
            configuration.validateEndpoint();
            InetAddress address = networkPolicy.resolveAllowed(configuration.host(), configuration.allowedCidrs()).get(0);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, configuration.port()), configuration.timeoutMillis());
            }
            return new AdapterResult(true, "RADAR_TCP_OK", "TCP 端口可达，数据权限登录在开始协议调测时执行");
        } catch (ProtocolException ex) {
            return new AdapterResult(false, ex.code(), ex.getMessage());
        } catch (IOException ex) {
            return new AdapterResult(false, "ADAPTER_UNAVAILABLE", safe(ex));
        }
    }

    @Override
    public CommissionResult commission(CommissionWork work) {
        List<CommissionItem> items = new ArrayList<>();
        try (Session session = open(work.configurationJson())) {
            items.add(pass("TCP", "TCP 连接", "reachable", "SOCKET_CONNECT"));
            LoginResult login = login(session);
            if (!login.success()) return failed(items, "RADAR_LOGIN_FAILED", login.detail());
            items.add(pass("LOGIN", "数据权限登录", "permission=0x5", "RADAR_V3_LOGIN"));

            write(session, RadarV300Codec.COMMAND_HEARTBEAT, RadarV300Codec.heartbeatPayload());
            RadarFrame heartbeat = waitFor(session, RadarV300Codec.COMMAND_HEARTBEAT, session.timeoutMillis());
            items.add(pass("HEARTBEAT", "心跳", "valid frame " + heartbeat.frameId(), "RADAR_V3_HEARTBEAT"));

            write(session, RadarV300Codec.COMMAND_GET_REGISTER, RadarV300Codec.getWorkModePayload());
            waitFor(session, RadarV300Codec.COMMAND_GET_REGISTER, session.timeoutMillis());
            items.add(pass("WORK_MODE", "工作模式读取", "response received", "GETREG_0x401"));

            JsonNode protocol = session.configuration().protocol();
            if (protocol.path("rtk_enabled").asBoolean(false)) {
                write(session, RadarV300Codec.COMMAND_REQUEST_RTK, RadarV300Codec.enableRtkUploadPayload());
                waitFor(session, RadarV300Codec.COMMAND_REQUEST_RTK, session.timeoutMillis());
                try {
                    waitFor(session, RadarV300Codec.COMMAND_UPLOAD_RTK, session.timeoutMillis());
                    items.add(pass("RTK", "RTK 数据", "valid frame", "UPLOAD_RTK"));
                } catch (SocketTimeoutException ex) {
                    items.add(new CommissionItem("RTK", "RTK 数据", "UNTESTABLE", "未在时间窗内收到 RTK", null, "UPLOAD_RTK"));
                }
            }

            try {
                RadarFrame data = waitForAny(session,
                        List.of(RadarV300Codec.COMMAND_UPLOAD_TARGET_V3, RadarV300Codec.COMMAND_UPLOAD_TRACK_V3),
                        Math.max(session.timeoutMillis(), 3000));
                items.add(pass("DATA", "点迹/航迹接收", "command=0x" + Integer.toHexString(data.command()), "RADAR_V3_UPLOAD"));
                return new CommissionResult(true, "RADAR_COMMISSION_PASSED", "雷达协议链路调测通过", items);
            } catch (SocketTimeoutException ex) {
                items.add(new CommissionItem("DATA", "点迹/航迹接收", "UNTESTABLE", "雷达可能处于待机，未收到业务数据", null, "RADAR_V3_UPLOAD"));
                return new CommissionResult(false, "RADAR_DATA_UNTESTABLE", "连接、登录、心跳和工作模式读取通过；业务数据无判据", items);
            }
        } catch (ProtocolException ex) {
            return failed(items, ex.code(), ex.getMessage());
        } catch (IOException ex) {
            return failed(items, "ADAPTER_UNAVAILABLE", safe(ex));
        }
    }

    public Session open(String json) throws IOException {
        AdapterConfiguration configuration = AdapterConfiguration.parse(mapper, json);
        configuration.validateEndpoint();
        InetAddress address = networkPolicy.resolveAllowed(configuration.host(), configuration.allowedCidrs()).get(0);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address, configuration.port()), configuration.timeoutMillis());
        socket.setSoTimeout(Math.min(configuration.timeoutMillis(), 1000));
        return new Session(socket, new StreamDecoder(RadarV300Codec.DEFAULT_MAX_FRAME_LENGTH, false),
                configuration, configuration.timeoutMillis());
    }

    public void monitor(String json, BooleanSupplier keepRunning, Runnable leasePulse,
                        LiveFrameListener listener) throws IOException {
        try (Session session = open(json)) {
            LoginResult result = login(session);
            if (!result.success()) throw new ProtocolException("ADAPTER_UNAVAILABLE", result.detail());
            write(session, RadarV300Codec.COMMAND_GET_REGISTER, RadarV300Codec.getWorkModePayload());
            if (session.configuration().protocol().path("rtk_enabled").asBoolean(false))
                write(session, RadarV300Codec.COMMAND_REQUEST_RTK, RadarV300Codec.enableRtkUploadPayload());
            listener.online();
            long lastValidAt = System.currentTimeMillis();
            long lastHeartbeatAt = 0;
            long lastInvalidCount = session.decoder().invalidFrameCount();
            byte[] chunk = new byte[64 * 1024];
            while (keepRunning.getAsBoolean()) {
                long now = System.currentTimeMillis();
                leasePulse.run();
                if (now - lastHeartbeatAt >= 10_000L) {
                    write(session, RadarV300Codec.COMMAND_HEARTBEAT, RadarV300Codec.heartbeatPayload());
                    lastHeartbeatAt = now;
                }
                try {
                    int read = session.socket().getInputStream().read(chunk);
                    if (read < 0) throw new IOException("雷达关闭了 TCP 连接");
                    List<RadarFrame> frames = session.decoder().feed(java.util.Arrays.copyOf(chunk, read));
                    if (!frames.isEmpty()) lastValidAt = now;
                    for (RadarFrame frame : frames) listener.frame(frame,
                            RadarV300Codec.encode(frame.command(), frame.frameId(), frame.payload()), now);
                    long invalid = session.decoder().invalidFrameCount();
                    if (invalid > lastInvalidCount) listener.invalidFrames(invalid - lastInvalidCount);
                    lastInvalidCount = invalid;
                } catch (SocketTimeoutException ignored) {
                    // 每秒检查租约、停用和三周期离线阈值。
                }
                if (System.currentTimeMillis() - lastValidAt >= 30_000L)
                    throw new SocketTimeoutException("连续三个心跳周期未收到有效雷达帧");
            }
        }
    }

    private LoginResult login(Session session) throws IOException {
        String reference = text(session.configuration().protocol(), "recognition_code_ref");
        long recognitionCode = 0;
        if (reference != null) {
            String raw = credentials.resolve(reference);
            try { recognitionCode = raw.startsWith("0x") ? Long.parseUnsignedLong(raw.substring(2), 16) : Long.parseUnsignedLong(raw); }
            catch (NumberFormatException ex) { throw new ProtocolException("CREDENTIAL_UNAVAILABLE", "雷达识别码不是无符号整数"); }
            if ((recognitionCode >>> 32) != 0)
                throw new ProtocolException("CREDENTIAL_UNAVAILABLE", "雷达识别码超出 UINT32");
        }
        write(session, RadarV300Codec.COMMAND_LOGIN, RadarV300Codec.loginDataPayload(recognitionCode));
        RadarFrame response = waitFor(session, RadarV300Codec.COMMAND_LOGIN, session.timeoutMillis());
        try {
            int status = RadarV300Codec.loginStatus(response.payload());
            return new LoginResult(status == 0, status == 0 ? "登录成功" : "雷达拒绝登录，状态码=" + status);
        } catch (ProtocolException ex) {
            return new LoginResult(false, ex.getMessage());
        }
    }

    private void write(Session session, int command, byte[] payload) throws IOException {
        byte[] frame = RadarV300Codec.encode(command, frameSequence.getAndIncrement(), payload);
        session.socket().getOutputStream().write(frame);
        session.socket().getOutputStream().flush();
    }

    private RadarFrame waitFor(Session session, int command, int timeout) throws IOException {
        return waitForAny(session, List.of(command), timeout);
    }

    private RadarFrame waitForAny(Session session, List<Integer> commands, int timeout) throws IOException {
        long deadline = System.currentTimeMillis() + timeout;
        byte[] chunk = new byte[8192];
        while (System.currentTimeMillis() < deadline) {
            try {
                int read = session.socket().getInputStream().read(chunk);
                if (read < 0) throw new IOException("雷达关闭了 TCP 连接");
                for (RadarFrame frame : session.decoder().feed(java.util.Arrays.copyOf(chunk, read)))
                    if (commands.contains(frame.command())) return frame;
            } catch (SocketTimeoutException ignored) {
                // 小步超时用于检查总截止时间。
            }
        }
        throw new SocketTimeoutException("等待雷达响应超时");
    }

    private static CommissionItem pass(String code, String label, String value, String basis) {
        return new CommissionItem(code, label, "PASSED", value, null, basis);
    }

    private static CommissionResult failed(List<CommissionItem> completed, String code, String detail) {
        List<CommissionItem> items = new ArrayList<>(completed);
        items.add(new CommissionItem("BLOCKED", "调测阻断", "FAILED", detail, null, code));
        return new CommissionResult(false, code, detail, items);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }
    private static String safe(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private record LoginResult(boolean success, String detail) { }
    public record Session(Socket socket, StreamDecoder decoder, AdapterConfiguration configuration,
                          int timeoutMillis) implements AutoCloseable {
        @Override public void close() throws IOException { socket.close(); }
    }
    public interface LiveFrameListener {
        void online();
        void frame(RadarFrame frame, byte[] rawFrame, long receivedAt);
        void invalidFrames(long count);
    }
}
