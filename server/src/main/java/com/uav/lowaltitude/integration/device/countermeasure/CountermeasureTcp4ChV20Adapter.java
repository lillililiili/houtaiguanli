package com.uav.lowaltitude.integration.device.countermeasure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.AdapterConfiguration;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.device.NetworkTargetPolicy;
import com.uav.lowaltitude.integration.device.ProtocolException;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec.RelayState;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec.WireEncoding;

@Component
public class CountermeasureTcp4ChV20Adapter implements DeviceAdapterPort {

    private final ObjectMapper mapper;
    private final NetworkTargetPolicy networkPolicy;

    public CountermeasureTcp4ChV20Adapter(ObjectMapper mapper, NetworkTargetPolicy networkPolicy) {
        this.mapper = mapper;
        this.networkPolicy = networkPolicy;
    }

    @Override public SourceMode mode() { return SourceMode.live; }
    @Override public String protocolCode() { return DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0; }

    @Override
    public AdapterResult reboot(RebootWork work) {
        return new AdapterResult(false, "DEVICE_NOT_OPERABLE", "四通道网络控制器协议未声明重启能力");
    }

    @Override
    public AdapterResult setRelays(RelayWork work) {
        try {
            AdapterConfiguration config = AdapterConfiguration.parse(mapper, work.configurationJson());
            config.validateEndpoint();
            int address = config.protocol().path("device_address").asInt(1);
            WireEncoding encoding = encodingOf(config);
            if (encoding == WireEncoding.AUTO) {
                ProbeResult probed = query(work.configurationJson());
                encoding = probed.encoding();
            }
            byte[] logical = setFrame(address, work);
            List<InetAddress> addresses = networkPolicy.resolveAllowed(config.host(), config.allowedCidrs());
            byte[] response = exchange(addresses.get(0), config.port(), config.timeoutMillis(), logical, encoding);
            RelayState state = Countermeasure4ChCodec.parseResponse(
                    Countermeasure4ChCodec.decodeWire(response, encoding), address, functionOf(work.action()));
            return new AdapterResult(true, "COUNTERMEASURE_SET_OK",
                    "继电器设置回码已解析，低四位=" + Integer.toHexString(Byte.toUnsignedInt((byte) state.rawStatusWord()))
                            + "；不代表射频已发射");
        } catch (SocketTimeoutException ex) {
            return new AdapterResult(false, "ADAPTER_TIMEOUT", safe(ex));
        } catch (ProtocolException ex) {
            return new AdapterResult(false, ex.code(), ex.getMessage());
        } catch (IOException ex) {
            return new AdapterResult(false, "ADAPTER_UNAVAILABLE", safe(ex));
        }
    }

    @Override
    public AdapterResult connect(CommissionWork work) {
        try {
            AdapterConfiguration config = AdapterConfiguration.parse(mapper, work.configurationJson());
            config.validateEndpoint();
            List<InetAddress> addresses = networkPolicy.resolveAllowed(config.host(), config.allowedCidrs());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(addresses.get(0), config.port()), config.timeoutMillis());
            }
            return new AdapterResult(true, "COUNTERMEASURE_TCP_OK", "TCP 端口可达，编码探测和状态查询在开始协议调测时执行");
        } catch (ProtocolException ex) {
            return new AdapterResult(false, ex.code(), ex.getMessage());
        } catch (IOException ex) {
            return new AdapterResult(false, "ADAPTER_UNAVAILABLE", safe(ex));
        }
    }

    @Override
    public CommissionResult commission(CommissionWork work) {
        try {
            ProbeResult result = query(work.configurationJson());
            RelayState state = result.state();
            List<CommissionItem> items = new ArrayList<>();
            items.add(new CommissionItem("TCP", "TCP 连接", "PASSED", "reachable", null, "SOCKET_CONNECT"));
            items.add(new CommissionItem("WIRE_ENCODING", "编码探测", "PASSED", result.encoding().name(), null, "SAFE_QUERY_0x10"));
            items.add(new CommissionItem("ADDRESS_CHECKSUM", "地址与校验和", "PASSED", "valid", null, "PROTOCOL_V2_0"));
            state.channels().forEach((band, on) -> items.add(new CommissionItem("CHANNEL_" + band.replace(".", "_"),
                    band + " 通道状态", "PASSED", on ? "ON" : "OFF", null, "READ_ONLY_RELAY_BITMAP")));
            return new CommissionResult(true, "COUNTERMEASURE_QUERY_PASSED",
                    "连接和状态查询通过；不包含射频发射能力验证", items);
        } catch (ProtocolException ex) {
            return failed(ex.code(), ex.getMessage());
        } catch (IOException ex) {
            return failed("ADAPTER_UNAVAILABLE", safe(ex));
        }
    }

    public ProbeResult query(String json) throws IOException {
        AdapterConfiguration config = AdapterConfiguration.parse(mapper, json);
        config.validateEndpoint();
        int address = config.protocol().path("device_address").asInt(1);
        WireEncoding configured = encodingOf(config);
        List<WireEncoding> candidates = configured == WireEncoding.AUTO
                ? List.of(WireEncoding.ASCII_HEX_SPACED, WireEncoding.ASCII_HEX_COMPACT, WireEncoding.RAW_BYTES)
                : List.of(configured);
        List<InetAddress> addresses = networkPolicy.resolveAllowed(config.host(), config.allowedCidrs());
        String lastError = null;
        for (WireEncoding candidate : candidates) {
            try {
                byte[] response = exchange(addresses.get(0), config.port(), config.timeoutMillis(),
                        Countermeasure4ChCodec.query(address), candidate);
                RelayState state = Countermeasure4ChCodec.parseResponse(
                        Countermeasure4ChCodec.decodeWire(response, candidate), address);
                return new ProbeResult(candidate, state);
            } catch (IOException | ProtocolException ex) {
                lastError = ex.getMessage();
            }
        }
        throw new ProtocolException("PROTOCOL_FRAME_INVALID", "三种只读查询编码均未收到有效响应：" + lastError);
    }

    private byte[] exchange(InetAddress address, int port, int timeout, byte[] logicalFrame,
                            WireEncoding encoding) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeout);
            socket.setSoTimeout(timeout);
            byte[] request = Countermeasure4ChCodec.encodeWire(logicalFrame, encoding);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return readResponse(socket, encoding);
        }
    }

    private static WireEncoding encodingOf(AdapterConfiguration config) {
        try {
            return WireEncoding.valueOf(config.protocol().path("wire_encoding").asText("AUTO"));
        } catch (IllegalArgumentException ex) {
            throw new ProtocolException("PROTOCOL_NOT_CONFIGURED", "wire_encoding 无效");
        }
    }

    private static byte[] setFrame(int address, RelayWork work) {
        String action = work.action() == null ? "" : work.action();
        return switch (action) {
            case "CHANNEL_ON" -> Countermeasure4ChCodec.channelOn(address, requiredBit(work.channelBit()));
            case "CHANNEL_OFF" -> Countermeasure4ChCodec.channelOff(address, requiredBit(work.channelBit()));
            case "SET_MASK" -> Countermeasure4ChCodec.setMask(address, requiredMask(work.mask()));
            default -> throw new ProtocolException("PROTOCOL_UNSUPPORTED", "不支持的四通道动作：" + action);
        };
    }

    private static int functionOf(String action) {
        return switch (action == null ? "" : action) {
            case "CHANNEL_ON" -> Countermeasure4ChCodec.FUNCTION_ON;
            case "CHANNEL_OFF" -> Countermeasure4ChCodec.FUNCTION_OFF;
            case "SET_MASK" -> Countermeasure4ChCodec.FUNCTION_SET;
            default -> throw new ProtocolException("PROTOCOL_UNSUPPORTED", "不支持的四通道动作：" + action);
        };
    }

    private static int requiredBit(Integer bit) {
        if (bit == null) throw new ProtocolException("PROTOCOL_FRAME_INVALID", "单通道动作必须指定通道位");
        return bit;
    }

    private static int requiredMask(Integer mask) {
        if (mask == null) throw new ProtocolException("PROTOCOL_FRAME_INVALID", "组合动作必须指定掩码");
        return mask;
    }

    private static byte[] readResponse(Socket socket, WireEncoding encoding) throws IOException {
        int expected = encoding == WireEncoding.RAW_BYTES ? 8 : encoding == WireEncoding.ASCII_HEX_COMPACT ? 16 : 23;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < expected + 2) {
            try {
                int value = socket.getInputStream().read();
                if (value < 0) break;
                out.write(value);
                if (encoding != WireEncoding.RAW_BYTES && (value == '\n' || value == '\r') && out.size() >= 16) break;
                if (encoding == WireEncoding.RAW_BYTES && out.size() == 8) break;
            } catch (SocketTimeoutException ex) {
                break;
            }
        }
        byte[] value = out.toByteArray();
        if (value.length == 0) throw new SocketTimeoutException("状态查询超时");
        return value;
    }

    private static CommissionResult failed(String code, String detail) {
        return new CommissionResult(false, code, detail,
                List.of(new CommissionItem("READ_ONLY_QUERY", "只读状态查询", "FAILED", detail, null, code)));
    }

    private static String safe(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public record ProbeResult(WireEncoding encoding, RelayState state) { }
}
