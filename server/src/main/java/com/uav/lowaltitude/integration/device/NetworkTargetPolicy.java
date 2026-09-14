package com.uav.lowaltitude.integration.device;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NetworkTargetPolicy {

    private static final byte[] CLOUD_METADATA = new byte[] { (byte) 169, (byte) 254, (byte) 169, (byte) 254 };

    private final boolean allowLoopbackWhenListed;

    public NetworkTargetPolicy() {
        this(false);
    }

    @Autowired
    public NetworkTargetPolicy(
            @Value("${app.network.allow-loopback-when-listed:false}") boolean allowLoopbackWhenListed) {
        this.allowLoopbackWhenListed = allowLoopbackWhenListed;
    }

    public List<InetAddress> resolveAllowed(String host, String allowedCidrs) {
        if (host == null || host.isBlank()) throw forbidden("设备主机地址未配置");
        List<Cidr> allowlist = parse(allowedCidrs);
        if (allowlist.isEmpty()) throw forbidden("live 来源未配置网络 CIDR 白名单");
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException ex) {
            throw forbidden("无法解析设备主机地址");
        }
        List<InetAddress> accepted = new ArrayList<>();
        for (InetAddress address : resolved) {
            if (unsafe(address) || allowlist.stream().noneMatch(cidr -> cidr.contains(address)))
                throw forbidden("设备解析地址不在授权 CIDR 白名单内：" + address.getHostAddress());
            accepted.add(address);
        }
        return List.copyOf(accepted);
    }

    private boolean unsafe(InetAddress address) {
        byte[] raw = address.getAddress();
        if (allowLoopbackWhenListed && address.isLoopbackAddress() && !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress() && !address.isMulticastAddress())
            return false;
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress() || (raw.length == 4 && Arrays.equals(raw, CLOUD_METADATA));
    }

    private static List<Cidr> parse(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Cidr> result = new ArrayList<>();
        for (String token : value.split("[,\\s]+")) {
            if (!token.isBlank()) result.add(Cidr.parse(token));
        }
        return result;
    }

    private static ProtocolException forbidden(String message) {
        return new ProtocolException("NETWORK_TARGET_FORBIDDEN", message);
    }

    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String value) {
            String[] parts = value.trim().split("/", -1);
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException();
                return new Cidr(address, prefix);
            } catch (Exception ex) {
                throw forbidden("CIDR 白名单格式无效：" + value);
            }
        }

        boolean contains(InetAddress candidate) {
            byte[] value = candidate.getAddress();
            if (value.length != network.length) return false;
            int full = prefix / 8, remaining = prefix % 8;
            for (int i = 0; i < full; i++) if (value[i] != network[i]) return false;
            if (remaining == 0) return true;
            int mask = 0xFF << (8 - remaining);
            return (value[full] & mask) == (network[full] & mask);
        }
    }
}
