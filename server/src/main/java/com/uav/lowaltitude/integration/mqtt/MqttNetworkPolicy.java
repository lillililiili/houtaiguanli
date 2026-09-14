package com.uav.lowaltitude.integration.mqtt;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.integration.device.NetworkTargetPolicy;
import com.uav.lowaltitude.integration.device.ProtocolException;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Broker;

@Component
public class MqttNetworkPolicy {
    private final NetworkTargetPolicy policy;
    private final Environment environment;
    public MqttNetworkPolicy(NetworkTargetPolicy policy, Environment environment) {
        this.policy = policy;
        this.environment = environment;
    }
    public List<InetAddress> resolve(Broker broker) {
        try {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(broker.host()));
            boolean development = Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("local") || p.equals("test"))
                    && Arrays.stream(environment.getActiveProfiles()).noneMatch(p -> p.equals("prod") || p.equals("production"));
            if (development && broker.sourceMode().equals("replay") && addresses.stream().allMatch(InetAddress::isLoopbackAddress))
                return addresses;
        } catch (java.net.UnknownHostException ex) {
            throw new ProtocolException("NETWORK_TARGET_FORBIDDEN", "无法解析 MQTT 主机");
        }
        return policy.resolveAllowed(broker.host(), broker.allowedCidrs());
    }
}
