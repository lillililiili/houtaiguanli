package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.integration.DeviceAdapterPort.CommissionItem;
import com.uav.lowaltitude.integration.DeviceAdapterPort.CommissionResult;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceInformationRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** MQTT 设备调测只看平台自己的会话、订阅和已接收报文；不下发指令，缺业务数据不冒充通过。 */
class MqttCommissionCheckTest {
    private static final long NOW = 10_000_000L;
    private static final String LINGYUN = "LINGYUN_MQTT_V8_6";
    private static final String EO = "EO_EDGE_MQTT_20250826";
    private final DeviceInformationRepository information = mock(DeviceInformationRepository.class);
    private final MqttCommissionCheck check = new MqttCommissionCheck(information,
            new AppClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)), 3_000);
    private Map<String, Object> binding;

    @BeforeEach
    void connectedRadar() {
        binding = new HashMap<>(Map.of("broker_name", "现场 MQTT", "host", "10.8.0.5", "port", 8883, "tls", true,
                "broker_enabled", true, "connection_state", "CONNECTED", "lease_until", NOW + 20_000, "subscribed", true,
                "provider_code", "field", "external_device_id", "radar-01"));
        binding.put("device_type_abbr", "radar");
        binding.put("last_static_at", NOW - 2_000);
        binding.put("last_sense_at", NOW - 1_000);
        binding.put("last_msg_cnt", 7L);
        binding.put("suspected_gap_count", 0L);
        binding.put("duplicate_count", 1L);
        when(information.mqtt("device", false)).thenReturn(binding);
        when(information.reportedWorkState("device")).thenReturn("1");
        when(information.receiveProblems(anyString(), anyLong())).thenReturn(List.of());
    }

    @Test
    void onlyTheTwoMqttProtocolsAreRoutedHere() {
        assertThat(MqttCommissionCheck.supports(LINGYUN)).isTrue();
        assertThat(MqttCommissionCheck.supports(EO)).isTrue();
        assertThat(MqttCommissionCheck.supports("RADAR_TCP_V3_0_0")).isFalse();
        assertThat(MqttCommissionCheck.supports(null)).isFalse();
    }

    @Test
    void connectNeedsAnEnabledBrokerALiveSessionAndTheDeviceSubscription() {
        assertThat(check.connect("device", LINGYUN).success()).isTrue();
        assertThat(check.connect("device", LINGYUN).detail()).contains("现场 MQTT 10.8.0.5:8883，TLS").contains("设备主题已订阅");

        binding.put("subscribed", false);
        assertThat(check.connect("device", LINGYUN).resultCode()).isEqualTo("MQTT_SUBSCRIPTION_PENDING");
        binding.put("subscribed", true);

        binding.put("lease_until", NOW);
        assertThat(check.connect("device", LINGYUN).detail()).contains("会话租约已过期");
        binding.put("lease_until", NOW + 20_000);

        binding.put("connection_state", "DISCONNECTED");
        binding.put("last_error", "BAD_USERNAME_OR_PASSWORD");
        var disconnected = check.connect("device", LINGYUN);
        assertThat(disconnected.success()).isFalse();
        assertThat(disconnected.resultCode()).isEqualTo("MQTT_SESSION_UNAVAILABLE");
        // 原始连接错误只在有设备运维权限的 MQTT 连接管理里看
        assertThat(disconnected.detail()).contains("状态 DISCONNECTED").contains("最近连接错误见 MQTT 连接管理")
                .doesNotContain("BAD_USERNAME_OR_PASSWORD");

        binding.put("connection_state", "CONNECTED");
        binding.put("broker_enabled", false);
        assertThat(check.connect("device", LINGYUN).detail()).contains("MQTT 连接未启用");

        when(information.mqtt("device", false)).thenReturn(Map.of());
        assertThat(check.connect("device", LINGYUN).detail()).isEqualTo("设备未绑定 MQTT 接入身份");
        assertThat(check.endpoint("device", LINGYUN)).isNull();
    }

    @Test
    void endpointSnapshotHoldsOnlyTheBrokerAddress() {
        binding.put("username", "platform");
        binding.put("credential_ref", "env:MQTT_SECRET");
        assertThat(check.endpoint("device", LINGYUN)).containsExactly(Map.entry("transport", "MQTT"),
                Map.entry("broker_name", "现场 MQTT"), Map.entry("host", "10.8.0.5"), Map.entry("port", 8883), Map.entry("tls", true));
    }

    @Test
    void radarWithFreshWorkParametersAndSenseDataPasses() {
        CommissionResult result = check.run("device", LINGYUN);
        assertThat(result.success()).isTrue();
        assertThat(result.resultCode()).isEqualTo("MQTT_COMMISSION_PASSED");
        assertThat(result.items()).extracting(CommissionItem::code)
                .containsExactly("SESSION", "SUBSCRIPTION", "WORK_PARAMETERS", "WORK_STATE", "SENSE", "RECEIVE");
        assertThat(result.items()).extracting(CommissionItem::result).containsOnly("PASSED");
        assertThat(item(result, "SUBSCRIPTION").value()).isEqualTo("已订阅 bridge/field/device/radar/radar-01");
        assertThat(item(result, "SENSE").value()).isEqualTo("1.0 秒前收到感知报文（msgCnt=7）");
        verify(information).receiveProblems("device", NOW - MqttCommissionCheck.RECEIVE_WINDOW_MILLIS);
    }

    @Test
    void missingSenseDataOrAnIdleSensorIsUntestableRatherThanPassed() {
        binding.put("last_sense_at", null);
        CommissionResult quiet = check.run("device", LINGYUN);
        assertThat(quiet.success()).isFalse();
        assertThat(quiet.resultCode()).isEqualTo("MQTT_DATA_UNTESTABLE");
        assertThat(item(quiet, "SENSE").value()).startsWith("尚未收到感知报文").contains("感知链路不可判定");

        binding.put("last_sense_at", NOW - 1_000);
        when(information.reportedWorkState("device")).thenReturn("0");
        CommissionResult idle = check.run("device", LINGYUN);
        assertThat(idle.resultCode()).isEqualTo("MQTT_DATA_UNTESTABLE");
        assertThat(item(idle, "WORK_STATE").value()).contains("workState=0（未工作）");
    }

    @Test
    void staleOrMissingWorkParametersAndASelfReportedFaultFail() {
        binding.put("last_static_at", NOW - 31_000);
        CommissionResult stale = check.run("device", LINGYUN);
        assertThat(stale.resultCode()).isEqualTo("MQTT_WORK_PARAMETERS_FAILED");
        assertThat(stale.detail()).contains("31 秒前").contains("超过 30 秒时效");
        assertThat(stale.items()).extracting(CommissionItem::code).doesNotContain("WORK_STATE");

        binding.put("last_static_at", null);
        assertThat(check.run("device", LINGYUN).detail()).contains("平台尚未收到该设备的有效工参");

        binding.put("last_static_at", NOW - 2_000);
        when(information.reportedWorkState("device")).thenReturn("2");
        assertThat(check.run("device", LINGYUN).resultCode()).isEqualTo("MQTT_WORK_STATE_FAILED");

        binding.put("connection_state", "DISCONNECTED");
        assertThat(check.run("device", LINGYUN).resultCode()).isEqualTo("MQTT_SESSION_FAILED");
    }

    @Test
    void countermeasureAndWeatherNeedOnlyWorkParametersAndAnIdleJammerIsNormal() {
        for (String type : List.of("ifr", "countermeasure", "weather")) {
            binding.put("device_type_abbr", type);
            binding.put("last_sense_at", null);
            when(information.reportedWorkState("device")).thenReturn("countermeasure".equals(type) ? "0" : "1");
            CommissionResult result = check.run("device", LINGYUN);
            assertThat(result.success()).as(type).isTrue();
            assertThat(result.items()).extracting(CommissionItem::code).as(type).doesNotContain("SENSE");
        }
        assertThat(check.thresholds(LINGYUN)).containsEntry("commands_sent", false)
                .containsEntry("work_parameter_fresh_millis", 30_000L).containsEntry("receive_window_millis", 300_000L);
    }

    @Test
    void rejectedMessagesFailWhileEndedTrackingOrDisabledPeriodsAreOnlyNoted() {
        when(information.receiveProblems(anyString(), anyLong())).thenReturn(List.of(
                Map.of("outcome", "REJECTED", "reason", "QOS1_REQUIRED", "message_count", 3L),
                Map.of("outcome", "CONFLICT", "reason", "KEY_PAYLOAD_CONFLICT", "message_count", 1L),
                Map.of("outcome", "REJECTED", "reason", "INVALID_PTTIME", "message_count", 2L)));
        CommissionResult rejected = check.run("device", LINGYUN);
        assertThat(rejected.resultCode()).isEqualTo("MQTT_RECEIVE_FAILED");
        assertThat(item(rejected, "RECEIVE").value()).contains("近 5 分钟有 6 条报文")
                .contains("未使用 QoS 1（QOS1_REQUIRED）×3").contains("同一消息编号内容不同（KEY_PAYLOAD_CONFLICT）×1")
                .contains("字段取值不符合协议（INVALID_PTTIME）×2");

        when(information.receiveProblems(anyString(), anyLong())).thenReturn(List.of(
                Map.of("outcome", "REJECTED", "reason", "DEVICE_DISABLED", "message_count", 2L)));
        CommissionResult noted = check.run("device", LINGYUN);
        assertThat(noted.success()).isTrue();
        assertThat(item(noted, "RECEIVE").value()).contains("另有不影响判定的记录：设备已停用（DEVICE_DISABLED）×2");
    }

    @Test
    void eoUsesItsHeartbeatTimeoutAndAutonomousDetectionIsNotAFault() {
        Map<String, Object> eo = new HashMap<>(binding);
        eo.put("edge_id", "edge-1");
        eo.put("last_heartbeat_at", NOW - 800);
        eo.put("work_state", 2);
        when(information.mqtt("device", true)).thenReturn(eo);
        CommissionResult fresh = check.run("device", EO);
        assertThat(fresh.success()).isTrue();
        assertThat(fresh.items()).extracting(CommissionItem::code).containsExactly("SESSION", "SUBSCRIPTION", "HEARTBEAT", "WORK_STATE", "RECEIVE");
        assertThat(item(fresh, "SUBSCRIPTION").value()).isEqualTo("已订阅 iot-reporting/cmlc/edge/edge-1");
        assertThat(item(fresh, "WORK_STATE").value()).isEqualTo("workState=2（自主探测）");

        eo.put("last_heartbeat_at", NOW - 5_000);
        CommissionResult stale = check.run("device", EO);
        assertThat(stale.resultCode()).isEqualTo("MQTT_HEARTBEAT_FAILED");
        assertThat(stale.detail()).contains("超过 3000 毫秒心跳时效");
        eo.put("last_heartbeat_at", null);
        assertThat(check.run("device", EO).detail()).contains("平台尚未收到该设备的 HeartBeat");
        assertThat(check.thresholds(EO)).containsEntry("heartbeat_fresh_millis", 3_000L).doesNotContainKey("sense_fresh_millis");
    }

    private static CommissionItem item(CommissionResult result, String code) {
        return result.items().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }
}
